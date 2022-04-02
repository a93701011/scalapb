# Databricks notebook source
# MAGIC %scala
# MAGIC 
# MAGIC package com.test
# MAGIC 
# MAGIC import org.apache.spark.sql.expressions.UserDefinedFunction
# MAGIC import org.apache.spark.sql.functions._
# MAGIC import java.io.ByteArrayInputStream
# MAGIC import java.util.zip.GZIPInputStream
# MAGIC import scalapb.spark.Implicits._
# MAGIC import scalapb.json4s.JsonFormat
# MAGIC import com.trendmicro.sae.datatypes.protos.wrapper
# MAGIC import scala.collection.mutable.ArrayBuffer
# MAGIC import org.json4s._
# MAGIC import org.json4s.DefaultFormats._
# MAGIC import org.json4s.jackson.JsonMethods._
# MAGIC 
# MAGIC object ScalaPySparkUDFs {
# MAGIC   
# MAGIC val gpb_to_json = (raw_gpb: Array[Byte]) => {
# MAGIC 
# MAGIC   implicit val formats = DefaultFormats
# MAGIC 
# MAGIC   val inputStream = new GZIPInputStream(new ByteArrayInputStream(raw_gpb))
# MAGIC   val json_res = ArrayBuffer[String]()
# MAGIC   var isvalue =  true
# MAGIC   var is_parsed_failure_occurred = false
# MAGIC   var error_type_set = ArrayBuffer[String]()
# MAGIC   while(isvalue){
# MAGIC     val out = wrapper.Data.parseDelimitedFrom(inputStream)
# MAGIC     if(out != None){
# MAGIC       //val out_data = JsonFormat.toJsonString(out.get) 
# MAGIC       val out_json = JsonFormat.toJson(out.get)
# MAGIC       try{
# MAGIC               var out_tele = compact(render(out_json.children.head))
# MAGIC               json_res += out_tele // string
# MAGIC         
# MAGIC       }catch{
# MAGIC            case _: Throwable => {
# MAGIC              is_parsed_failure_occurred = true
# MAGIC              error_type_set += "Got some other kind of exception"
# MAGIC            }
# MAGIC        }
# MAGIC     }else{
# MAGIC       isvalue = false
# MAGIC     }  
# MAGIC    }
# MAGIC   if(is_parsed_failure_occurred){
# MAGIC     json_res += f"""{ "packagePath": "path", "customerId": "customertest", "productCode": "Got some other kind of exception"}"""
# MAGIC   }
# MAGIC   json_res
# MAGIC }
# MAGIC // Register custom UDF
# MAGIC // val proto_udf = udf[ArrayBuffer[String],Array[Byte],String](gpb_to_json)
# MAGIC // val proto_udf_scala = udf(gpb_to_json)
# MAGIC 
# MAGIC def proto_udf_scala(): UserDefinedFunction = udf(gpb_to_json)
# MAGIC 
# MAGIC }

# COMMAND ----------

# MAGIC %python
# MAGIC 
# MAGIC import pyspark.sql.functions as F
# MAGIC from pyspark.sql.column import Column, _to_java_column, _to_seq
# MAGIC 
# MAGIC def test_udf(col):
# MAGIC     sc = spark.sparkContext
# MAGIC     _test_udf = sc._jvm.com.test.ScalaPySparkUDFs.proto_udf_scala()
# MAGIC     return Column(_test_udf.apply(_to_seq(sc, [col], _to_java_column)))
# MAGIC 
# MAGIC 
# MAGIC parsedf = (spark.read.format("binaryFile").load('/testdata/1mb.pb.gz')
# MAGIC            .select("path", test_udf("content").alias("gpb_json"))
# MAGIC            .withColumn("gpb_json", F.explode(F.col("gpb_json")))
# MAGIC           )
# MAGIC display(parsedf)

# COMMAND ----------

# MAGIC %python
# MAGIC 
# MAGIC from google.protobuf import json_format
# MAGIC import json, sys, gzip
# MAGIC import pyspark.sql.functions as F
# MAGIC from pyspark.sql.types import StructField, StructType, StringType, ArrayType, LongType, TimestampType, BinaryType, IntegerType
# MAGIC sys.path.append('/databricks/driver/')
# MAGIC sys.path.append('/databricks/driver/common')
# MAGIC 
# MAGIC SCHEMA_FILE_PATH = "/dbfs/schema/sdltelemetryschema.json"
# MAGIC # GPB transform method
# MAGIC with open(SCHEMA_FILE_PATH, 'r') as schema_file:
# MAGIC     schema_str = schema_file.read()
# MAGIC if not schema_str:
# MAGIC     raise ValueError('The schema_str could not be empty')
# MAGIC 
# MAGIC json_schema = json.loads(schema_str)
# MAGIC # append enrichGpbPath schema mapping
# MAGIC json_schema.get('fields').append({"name": "enrichGpbPath", "type": "string", "nullable": True, "metadata": {}})
# MAGIC # append impReceivedTime
# MAGIC json_schema.get('fields').append({"name": "impReceiveTime", "type": "long", "nullable": True, "metadata": {}})
# MAGIC 
# MAGIC data_schema = StructType.fromJson(json_schema)
# MAGIC array_data_schema = ArrayType(data_schema)
# MAGIC 
# MAGIC 
# MAGIC def gpb_to_json(raw_gpb, path):
# MAGIC     # protobuf gen library will be import to dbfs by infra deployment in advance
# MAGIC     sys.path.append('/dbfs/databricks/driver/')
# MAGIC     sys.path.append('/dbfs/databricks/driver/common')
# MAGIC 
# MAGIC     # Do not declare it outside the UDF function, it will raise cloudPickle exception
# MAGIC     from common.gen.wrapper_pb2 import Data
# MAGIC     from google.protobuf.internal.decoder import _DecodeVarint32
# MAGIC     if path.split('.')[-1] == 'gz':
# MAGIC         message = gzip.decompress(raw_gpb)
# MAGIC     else:
# MAGIC         message = bytes(raw_gpb)
# MAGIC     n = 0
# MAGIC     json_res = []
# MAGIC     # Length delimited protobuf streams
# MAGIC     # https://medium.com/@seb.nyberg/length-delimited-protobuf-streams-a39ebc4a4565
# MAGIC     is_parsed_failure_occurred = False
# MAGIC     error_type_set = set()
# MAGIC     while n < len(message):
# MAGIC         try:
# MAGIC             msg_len, new_pos = _DecodeVarint32(message, n)
# MAGIC         except Exception as e:
# MAGIC             is_parsed_failure_occurred = True
# MAGIC             error_type_set.add(type(e).__name__)
# MAGIC             break
# MAGIC         n = new_pos
# MAGIC         msg_buf = message[n:n+msg_len]
# MAGIC         n += msg_len
# MAGIC         log_dict = {}
# MAGIC         try:
# MAGIC             data_gpb = Data()
# MAGIC             data_gpb.ParseFromString(msg_buf)
# MAGIC             log_dict = json_format.MessageToDict(data_gpb,
# MAGIC                                                 use_integers_for_enums=True,
# MAGIC                                                 preserving_proto_field_name=True)
# MAGIC         except Exception as e:
# MAGIC             is_parsed_failure_occurred = True
# MAGIC             error_type_set.add(type(e).__name__)
# MAGIC 
# MAGIC         event_keys = list(log_dict.keys())
# MAGIC         if event_keys:
# MAGIC             event_name = event_keys[0]
# MAGIC             json_res.append(log_dict.get(event_name))
# MAGIC 
# MAGIC     # generate a failed records while parsing failed
# MAGIC     if is_parsed_failure_occurred:
# MAGIC         for error_type in error_type_set:
# MAGIC             json_res.append({ 'packagePath': path, 'customerId': PARSED_FAILED_CODE, 'productCode': error_type})
# MAGIC     return json_res
# MAGIC 
# MAGIC # Register custom UDF
# MAGIC proto_udf_python = udf(gpb_to_json, array_data_schema)

# COMMAND ----------

# MAGIC %sh
# MAGIC ls -al /dbfs/databricks/driver/common

# COMMAND ----------

# MAGIC %python
# MAGIC parsedf = (spark.read.format("binaryFile").load('/testdata/1mb2.pb.gz')
# MAGIC            .select("path", proto_udf_python("content","path").alias("gpb_json"))
# MAGIC            .withColumn("gpb_json", F.explode(F.col("gpb_json")))
# MAGIC           )
# MAGIC display(parsedf)

# COMMAND ----------

# MAGIC %sh
# MAGIC ls -al /dbfs/output/

# COMMAND ----------

# MAGIC %python
# MAGIC spark.conf.set("spark.sql.parquet.compression.codec", "snappy")
# MAGIC parsedf = (spark.read.format("parquet").load('/testdata/userdata1.parquet'))
# MAGIC # parsedf
# MAGIC parsedf.select("id", "first_name").write.parquet("output5")

# COMMAND ----------

# MAGIC %scala
# MAGIC val parsedf = spark.read.format("parquet").load("/testdata/1647215713544.snappy.parquet")
# MAGIC println(parsedf.schema)

# COMMAND ----------

from pyspark.sql.types import *

schema = StructType([
  StructField("User", IntegerType()),
  StructField("My_array", ArrayType(
      StructType([
          StructField("user", StringType()),
          StructField("product", StringType()),
          StructField("rating", DoubleType())
      ])
   ))
])

print(schema)

# COMMAND ----------

import json
from pyspark.sql.types import StructField, StructType, StringType, ArrayType, LongType, TimestampType, BinaryType, IntegerType

SCHEMA_FILE_PATH = "/dbfs/schema/sdltelemetryschema.json"
with open(SCHEMA_FILE_PATH, 'r') as schema_file:
    schema_str = schema_file.read()
if not schema_str:
    raise ValueError('The schema_str could not be empty')

json_schema = json.loads(schema_str)
print(json_schema)
data_schema = StructType.fromJson(json_schema)
# array_data_schema = ArrayType(data_schema)
print(data_schema)
