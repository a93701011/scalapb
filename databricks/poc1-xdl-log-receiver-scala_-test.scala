// Databricks notebook source
// Design document:
// https://wiki.jarvis.trendmicro.com/display/LUW/XDL+Log+Receiver+design+-+logs+integration+from+SAE+storage
// Configuration:
// https://wiki.jarvis.trendmicro.com/display/LUW/SDL+Configuration//SDLConfiguration-Databricksimporter(xdl-log-receiver)
org.apache.log4j.Logger.getRootLogger.getAppender("publicFile").setLayout(new org.apache.log4j.PatternLayout("%d{yy/MM/dd HH:mm:ss} %p |%t| %c{2}: %m%n"))

// Widget for configuration parameters
// When runing by Databricks Jobs, these parameter will be changed by Databrick jobs
dbutils.widgets.text("secret_scope", "secret_scope_Label")   // Databricks Secret Scope Name
dbutils.widgets.text("trigger_queue_landing_storage_mappings", "trigger_queue_landing_storage_mappings_Label") // mapping of queues and source storages
dbutils.widgets.text("target_file_storage_accounts", "target_file_storage_accounts_Label")    // Azure Storage Account Name for target(output) files
dbutils.widgets.text("target_file_folders", "target_file_folders_Label")  // Target files location
dbutils.widgets.text("checkpoint_file_storage_account", "checkpoint_file_storage_account_Label") // Check Point Storage for Spark Structured Streaming
dbutils.widgets.text("check_point_location", "check_point_location_Label") // Check Point location for Spark Structured Streaming
dbutils.widgets.text("bad_records_path", "bad_records_path_Label") // Bad Record Path for Spark Structured Streaming
dbutils.widgets.text("trigger_process_time", "trigger_process_time_Label") // Trigger Process interval for Spark Structured Streaming
dbutils.widgets.text("max_bytes_per_trigger", "max_bytes_per_trigger_label")  // Max Process bytes per trigger of Spark Structgured Streaming
dbutils.widgets.text("max_files_per_trigger", "max_files_per_trigger_label")  // Max Process file count per trigger of Spark Structgured Streaming
dbutils.widgets.text("shuffle_partitions", "shuffle_partitions_Label") // Bad Record Path for Spark Structured Streaming
dbutils.widgets.text("supported_product_codes", "supported_product_codes_Label") // XDL supported product codes list
dbutils.widgets.text("schema_file_path", "schema_file_path_Label") // XDL schema file path
dbutils.widgets.text("log_type", "log_type_Label") // XDL log type
dbutils.widgets.text("is_split_big_customer_data", "is_split_big_customer_data_Label") // Switch if need to split skew data, it will change databricks out folder structures
dbutils.widgets.text("customer_data_size_index", "{'productcode_customerid':10}", "customer_data_size_index_Label") // Bad Record Path for Spark Structured Streaming
dbutils.widgets.text("visibility_timeout", "visibility_timeout_label")  // Autoloader queue msg visibility timeout seconds
dbutils.widgets.text("fetch_parallelism", "fetch_parallelism_label")  // Autoloader queue fetch parallel
dbutils.widgets.text("max_file_age_enabled", "max_file_age_enabled_Label") // Switch if need to enable max file age
dbutils.widgets.text("max_file_age", "max_file_age_label")  // Autoloader max file age in rocksDB
dbutils.widgets.text("queue_list", "queue_list_Label") // Queue URLs for Spark Cloud files, multiple queues are supported by comma delimited
dbutils.widgets.text("queue_region", "queue_region_Label") // Queue Region (AWS only)

// COMMAND ----------

import scala.util.Try
import org.apache.spark.sql.{ DataFrame, functions => F }
import org.apache.spark.sql.types._
import java.time.LocalDateTime 
import java.time.format.DateTimeFormatter
import org.apache.spark.sql.streaming.Trigger
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import scalapb.spark.Implicits._
import scalapb.json4s.JsonFormat
import com.trendmicro.sae.datatypes.protos.wrapper
import scala.collection.mutable.ArrayBuffer
import org.json4s._
import org.json4s.DefaultFormats._
import org.json4s.jackson.JsonMethods._
implicit val formats = DefaultFormats
// COMMAND ----------
// Configurations
// val SECRET_SCOPE = dbutils.widgets.get("secret_scope")
// val TRIGGER_QUEUE_LANDING_STORAGE_MAPPINGS = dbutils.widgets.get("trigger_queue_landing_storage_mappings").split(',')
// val TARGET_FILE_STORAGE_ACCOUNTS = dbutils.widgets.get("target_file_storage_accounts").split(',')
// val TARGET_FILE_FOLDERS = dbutils.widgets.get("target_file_folders").split(',')
// val TARGET_FILE_SECRETS = dbutils.secrets.get(SECRET_SCOPE, "target-files-secrets").split(',') // Secret to access Target Files
// // LUWA-16123: [SSTFileIssue] Separate checkpoint folder with databricks-out blob
// val CHECKPOINT_FILE_STORAGE_ACCOUNT = dbutils.widgets.get("checkpoint_file_storage_account")
// val CHECKPOINT_FILE_SECRET = dbutils.secrets.get(SECRET_SCOPE, "checkpoint-files-secrets")
// val CHECK_POINT_LOCATION = dbutils.widgets.get("check_point_location")
// val BAD_RECORDS_PATH = dbutils.widgets.get("bad_records_path")
// val TRIGGER_PROCESS_TIME = dbutils.widgets.get("trigger_process_time")
// val SCHEMA_FILE_PATH = dbutils.widgets.get("schema_file_path")
// val MAX_BYTES_PER_TRIGGER = dbutils.widgets.get("max_bytes_per_trigger")
// val MAX_FILES_PER_TRIGGER = dbutils.widgets.get("max_files_per_trigger")
// val FETCH_PARALLELISM = dbutils.widgets.get("fetch_parallelism")
// val VISIBILITY_TIMEOUT = dbutils.widgets.get("visibility_timeout")
// val SHUFFLE_PARTITIONS = Try(dbutils.widgets.get("shuffle_partitions").toInt).getOrElse(-1)
// val SUPPORTED_PRODUCT_CODES = dbutils.widgets.get("supported_product_codes").split('/').map(_.trim).toList // delimited by slash
// val LOG_TYPE = dbutils.widgets.get("log_type")
// val CUSTOMER_DATA_SIZE_INDEX = dbutils.widgets.get("customer_data_size_index").toString
// val IS_SPLIT_BIG_CUSTOMER_DATA = Try(dbutils.widgets.get("is_split_big_customer_data").toBoolean).getOrElse(false)
// val MAX_FILE_AGE_ENABLED = Try(dbutils.widgets.get("max_file_age_enabled").toBoolean).getOrElse(false)
// val MAX_FILE_AGE = dbutils.widgets.get("max_file_age")
// val QUEUE_LIST = dbutils.widgets.get("queue_list").split(',')
// val AWS_QUEUE_REGION = dbutils.widgets.get("queue_region")

val TRIGGER_QUEUE_LANDING_STORAGE_MAPPINGS = "https://sqs.us-east-1.amazonaws.com/623158188184/xdl-az-p-dev-013-t-log-event-main-queue_s3://xdl-az-p-dev-013-t-fake-sae-bucket/,https://sqs.us-east-1.amazonaws.com/623158188184/xdl-az-p-dev-013-t-log-event-main-queue2_s3://xdl-az-p-dev-013-t-fake-sae-bucket/".split(',')

val TARGET_FILE_STORAGE_ACCOUNTS = "xdlazpdev013ti0".split(',')
val TARGET_FILE_FOLDERS = "abfss://xdl@xdlazpdev013ti0.dfs.core.windows.net/databricks-out".split(',')
val TARGET_FILE_SECRETS = "HC4aE942w5SzypJm2V3k3CmitRwAXeGFZX4k2+5SOQ4BD8ppdojVtXcBMc1qbzSAdsIUFGA1QHd/G5Yqc38clA==".split(',') // Secret to access Target Files
// LUWA-16123: [SSTFileIssue] Separate checkpoint folder with databricks-out blob
val CHECKPOINT_FILE_STORAGE_ACCOUNT = "xdlazpdev013tdbscp"
val CHECKPOINT_FILE_SECRET = "ac+t5HH3N12DHZ6jMzOK0zkCT6BlDGC6fRAFDRPfxyuvP7LBLDAmUef0d94JJLz4jP8D5tHRUcA+66bxlsWvaw=="
val CHECK_POINT_LOCATION = "abfss://xdl@xdlazpdev013tdbscp.dfs.core.windows.net/checkpoint"
val BAD_RECORDS_PATH = "abfss://xdl@xdlazpdev013ti0.dfs.core.windows.net/badrecords"
val TRIGGER_PROCESS_TIME = "10 seconds"
val SCHEMA_FILE_PATH = "/dbfs/schema/sdltelemetryschema.json"
val MAX_BYTES_PER_TRIGGER = "1mb"
val MAX_FILES_PER_TRIGGER = "100"
val FETCH_PARALLELISM = 1
val VISIBILITY_TIMEOUT = 900
val SHUFFLE_PARTITIONS = 10
val SUPPORTED_PRODUCT_CODES = "sao/sds/xes".split('/').map(_.trim).toList // delimited by slash
val LOG_TYPE = "telemetry"
val CUSTOMER_DATA_SIZE_INDEX = "{ \"021b1fc1-58fa-59d2-9d4d-81131acf4734_sao\": 2 }".toString
val IS_SPLIT_BIG_CUSTOMER_DATA = true
val MAX_FILE_AGE_ENABLED = false
val MAX_FILE_AGE = "1 days"
val AWS_QUEUE_REGION = "us-east-1"

// Allow the parsedFail records to pass through to downstream azure function.
// Azure function will handle this kind of event.
val PARSED_FAILED_CODE = "parsedFail"
println(f"TRIGGER_PROCESS_TIME: $TRIGGER_PROCESS_TIME%s, MAX_BYTES_PER_TRIGGER: $MAX_BYTES_PER_TRIGGER%s, MAX_FILES_PER_TRIGGER: $MAX_FILES_PER_TRIGGER%s, FETCH_PARALLELISM: $FETCH_PARALLELISM%s")
println(f"VISIBILITY_TIMEOUT: $VISIBILITY_TIMEOUT%s, MAX_FILE_AGE: $MAX_FILE_AGE%s")


// COMMAND ----------

import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener._

val metrics_path = "/testdata/stremingmtrics"

import org.apache.spark.sql.types._

val source = scala.io.Source.fromFile("/dbfs/schema/metricsschema.json")
val schema_str = try source.mkString finally source.close()
val data_schema = DataType.fromJson(schema_str).asInstanceOf[StructType]


class EventCollector extends StreamingQueryListener{
  override def onQueryStarted(event: QueryStartedEvent): Unit = {
     println("start")
  }

  override def onQueryProgress(event: QueryProgressEvent): Unit = {
    println("QueryProgress")
    val p = event.progress
    if(p.numInputRows > 0){
//       print(p.json)
      try{
        spark.read.schema(data_schema).json(Seq(p.json).toDS).write.mode("append").format("delta").save(metrics_path) }
      catch{
        case e: Throwable => {print(e.getClass().getName())}
      }
    }
  }
    
  override def onQueryTerminated(event: QueryTerminatedEvent): Unit = {
    println("End")
  }
}

spark.streams.addListener(new EventCollector())

// COMMAND ----------

// COMMAND ----------
// Set Configuration in Spark Config
// Should check whether config is modifiable by following code snippet
// ==> print(spark.conf.isModifiable("<config>"))
val SHUFFLE_PARTITIONS_TIMES_CORE =  (java.lang.Runtime.getRuntime.availableProcessors * (sc.statusTracker.getExecutorInfos.length)) * SHUFFLE_PARTITIONS
spark.conf.set("spark.sql.shuffle.partitions", SHUFFLE_PARTITIONS_TIMES_CORE)
if (!TARGET_FILE_STORAGE_ACCOUNTS.isInstanceOf[Array[String]]){
  throw new Exception("TARGET_FILE_STORAGE_ACCOUNTS should be a list")  
}
for ((target_storage_account,idx) <- TARGET_FILE_STORAGE_ACCOUNTS.view.zipWithIndex){
    val target_file_secret = TARGET_FILE_SECRETS(idx)
    spark.conf.set(f"fs.azure.account.key.$target_storage_account%s.dfs.core.windows.net", target_file_secret)
}
spark.conf.set(f"fs.azure.account.key.$CHECKPOINT_FILE_STORAGE_ACCOUNT%s.dfs.core.windows.net",CHECKPOINT_FILE_SECRET)
if (MAX_FILE_AGE_ENABLED){
    // spark.databricks.cloudFiles.maxFileAge.unsafe
    // LUWA-18936: this parameter configured to allow minimum checkpoint file age to be less than 14 days.
    // It is only available after DBR runtime 9.1. 
    // Databricks PG team provide us a custom image first for this feature built on 8.4. 
    spark.conf.set("spark.databricks.cloudFiles.maxFileAge.unsafe", "true")
}
// COMMAND ----------
//json_schema = json.loads(schema_str)
// append enrichGpbPath schema mapping
//json_schema.get('fields').append({"name": "enrichGpbPath", "type": "string", "nullable": True, "metadata": {}})
// append impReceivedTime
//json_schema.get('fields').append({"name": "impReceiveTime", "type": "long", "nullable": True, "metadata": {}})
// GPB transform method
val source = scala.io.Source.fromFile(SCHEMA_FILE_PATH)
val schema_str = try source.mkString finally source.close()
val data_schema = DataType.fromJson(schema_str).asInstanceOf[StructType]
val gpb_to_json = (raw_gpb: Array[Byte], path : String) => {
  
  implicit val formats = DefaultFormats
  
  val inputStream = new GZIPInputStream(new ByteArrayInputStream(raw_gpb))
  val json_res = ArrayBuffer[String]()
  var isvalue =  true
  var is_parsed_failure_occurred = false
  var error_type_set = ArrayBuffer[String]()
  while(isvalue){
    val out = wrapper.Data.parseDelimitedFrom(inputStream)
    if(out != None){
      //val out_data = JsonFormat.toJsonString(out.get) 
      val out_json = JsonFormat.toJson(out.get)
      try{
              var out_tele = compact(render(out_json.children.head))
              json_res += out_tele // string
        
      }catch{
           case e: Throwable => {
             is_parsed_failure_occurred = true
             error_type_set += e.getClass().getName()
           }
       }
    }else{
      isvalue = false
    }  
   }
  if(is_parsed_failure_occurred){
    for(error <- error_type_set){
      json_res += f"""{ "packagePath": $path%s, "customerId": "parsedFail", "productCode": $error%s}"""
    }
  }
  json_res
}
// Register custom UDF
// val proto_udf = udf[ArrayBuffer[String],Array[Byte],String](gpb_to_json)
val proto_udf = udf(gpb_to_json)

// COMMAND ----------CUSTOMER_DATA_SIZE_INDEX
val get_dbs_process_time = () => {
  
//   implicit val formats = DefaultFormats
  LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'H00"))
}
val get_dbs_process_time_in_ms = () => System.currentTimeMillis 
val get_dbs_process_time_udf = udf(get_dbs_process_time)
val get_dbs_process_time_in_ms_udf = udf(get_dbs_process_time_in_ms)

// COMMAND ----------
val get_data_size_index = (productCode:String, customerId:String, customerindex:String) => {
    
    implicit val formats = DefaultFormats
  
    val customermap = parse(customerindex).extract[Map[String, Int]]
    var size_index = 1
    val product_customer_dict_index:String = productCode + '_' + customerId
    if (customermap.contains(product_customer_dict_index)){  
        size_index = customermap(product_customer_dict_index)
    }
    size_index
}
val get_data_size_index_udf = udf(get_data_size_index)

// COMMAND ----------
val log_transform = (dataframe: DataFrame, log_type: String) => {
    if (log_type == "telemetry"){
        // time fields should be cast to long for ADX ingest transform
        dataframe.withColumn("eventTime", F.col("eventTime").cast(LongType)) 
            .withColumn("firstSeen", F.col("firstSeen").cast(LongType)) 
            .withColumn("lastSeen", F.col("lastSeen").cast(LongType)) 
            .withColumn("processLaunchTime", F.col("processLaunchTime").cast(LongType)) 
            .withColumn("parentLaunchTime", F.col("parentLaunchTime").cast(LongType)) 
            .withColumn("objectFirstSeen", F.col("objectFirstSeen").cast(LongType)) 
            .withColumn("objectLastSeen", F.col("objectLastSeen").cast(LongType)) 
            .withColumn("objectSessionId", F.col("objectSessionId").cast(LongType))
    }
    else if ( log_type == "detection"){
        // ddi provision productCode is sna, log productCode is pdi
        dataframe.withColumn("productCode", F.regexp_replace( F.col("productCode"), "pdi", "sna"))
    }
     dataframe
}
val split_data_stream_batch = (dataframe : DataFrame, checkpoint_location: String, output_location: String) => {    
    val parsed_df = dataframe.withColumn("fileProductCode", F.regexp_extract(F.col("path"), "(.)(productCode=)(\\w+)", 3)) 
        .filter(F.col("fileProductCode").isin(SUPPORTED_PRODUCT_CODES:_*)) 
        .select( proto_udf(F.col("content"), F.col("path")).alias("gpb_json"), F.col("path").alias("enrichGpbPath")) 
        .withColumn("gpb_json", F.explode(F.col("gpb_json"))) 
        .select(F.from_json(F.col("gpb_json"), data_schema).as("data"),  F.col("enrichGpbPath"))
        .select("data.*", "enrichGpbPath")
  
    val transformed_df = log_transform(parsed_df, LOG_TYPE)
    
    val output_df = (
      transformed_df.withColumn("customerIdKey", F.col("customerId"))            
        .withColumn("productCodeKey", F.col("productCode"))
        .withColumn("dbs_process_time", F.lit(get_dbs_process_time_udf()))
        .withColumn("sizeIndex",get_data_size_index_udf(F.col("productCodeKey"), F.col("customerIdKey"), F.lit(CUSTOMER_DATA_SIZE_INDEX)))
        .withColumn("dbsProcessTime", F.lit(get_dbs_process_time_in_ms_udf()))
     )
  
    if (IS_SPLIT_BIG_CUSTOMER_DATA){
        output_df
         .withColumn("splitId", F.col("eventTime") % F.col("sizeIndex")) 
        .drop("sizeIndex") 
        //.repartition("customerIdKey", "productCodeKey", "dbs_process_time", "splitId") 
        .writeStream 
        .partitionBy("customerIdKey", "productCodeKey", "dbs_process_time", "splitId") 
        .format("JSON") 
        .outputMode("append") 
        .option("checkpointLocation", checkpoint_location) 
        .option("compression", "gzip") 
        .trigger(Trigger.ProcessingTime(TRIGGER_PROCESS_TIME)) 
        .start(output_location)
    }
    else{
        output_df
        //.repartition("customerIdKey", "productCodeKey", "dbs_process_time") 
        .writeStream 
        .partitionBy("customerIdKey", "productCodeKey", "dbs_process_time") 
        .format("JSON") 
        .outputMode("append") 
        .option("checkpointLocation", checkpoint_location) 
        .option("compression", "gzip") 
        .trigger(Trigger.ProcessingTime(TRIGGER_PROCESS_TIME))
        .start(output_location)
    }
}
// COMMAND ----------

// spark.conf.set(f"fs.azure.account.key.databricksdatalakekaren.dfs.core.windows.net", "Hub3sv4vA7AEPGEzCrZeqmWagzMmcIMTPBeSFp1HzLLyAXGDN30GBMMB4TVrNt7dnbDlkpo6b27tMJDfxymgNQ==")
// val source_stream_folder = "abfss://trendmicro@databricksdatalakekaren.dfs.core.windows.net/pb"

val trigger_event_schema = (new StructType)
    .add(StructField("path", StringType,true))
    .add(StructField("modificationTime", TimestampType,true))
    .add(StructField("length", LongType,true))
    .add(StructField("content", BinaryType,true))

val run = (num: Integer, source_queue: String, source_stream_folder: String) =>{
    spark.sparkContext.setLocalProperty("spark.scheduler.pool", f"pool-$num%s")
    var spark_stream = (
        spark.readStream
            .format("cloudFiles")
            .schema(trigger_event_schema)
            .option("cloudFiles.queueUrl", source_queue)
            .option("cloudFiles.region", AWS_QUEUE_REGION)
            .option("cloudFiles.format", "binaryFile")
            .option("cloudFiles.useNotifications", "true")
            .option("cloudFiles.validateOptions", "false")
            .option("cloudFiles.maxBytesPerTrigger", MAX_BYTES_PER_TRIGGER)
            .option("cloudFiles.maxFilesPerTrigger", MAX_FILES_PER_TRIGGER)
            .option("cloudFiles.includeExistingFiles", false)
            .option("cloudFiles.partitionColumns", "")
            .option("cloudFiles.fetchParallelism", FETCH_PARALLELISM)
            .option("cloudFiles.visibilityTimeout", VISIBILITY_TIMEOUT)
            .option("badRecordsPath", BAD_RECORDS_PATH)
    )
    if (MAX_FILE_AGE_ENABLED){
        spark_stream.option("cloudFiles.maxFileAge", MAX_FILE_AGE)
    }
    var xdl_log_df = spark_stream.load(source_stream_folder)
    var checkpoint_path = f"$CHECK_POINT_LOCATION%s//queue$num%s"
    var target_blob_folder_path = TARGET_FILE_FOLDERS(num % TARGET_FILE_FOLDERS.size) + f"//queue$num%s" 
    
//    checkpoint_path = "abfss://trendmicro@databricksdatalakekaren.dfs.core.windows.net/checkpointpb"
//    target_blob_folder_path = "abfss://trendmicro@databricksdatalakekaren.dfs.core.windows.net/pbout"

    var run_df = split_data_stream_batch(xdl_log_df,
                                     checkpoint_path,
                                     target_blob_folder_path)
    println(f"Start run $num%s to listening queue $source_queue%s: source: $source_stream_folder%s, destination: $target_blob_folder_path%s")
    run_df
}

// run(0,"", source_stream_folder)

var runs: List[org.apache.spark.sql.streaming.StreamingQuery] = List()
for ((mapping, idx)<- TRIGGER_QUEUE_LANDING_STORAGE_MAPPINGS.view.zipWithIndex){
  var mppingarray = mapping.split('_')
  var trigger_queue = mppingarray(0)
  var landing_storage = mppingarray(1)
  runs = runs :+ run(idx, trigger_queue, landing_storage)
}

// COMMAND ----------

val df = spark.read.format("delta")
  .load(metrics_path)
  .select(F.col("batchId"),F.col("timestamp"),F.explode(F.col("sources")).alias("sources"))
  .select("batchId","timestamp","sources.*")
  .select("batchId","timestamp","inputRowsPerSecond","metrics","numInputRows","processedRowsPerSecond")
display(df)

// COMMAND ----------

// val CHECK_POINT_LOCATION = "abfss://xdl@xdlazpdev013tdbscp.dfs.core.windows.net/checkpoint"

// spark.sparkContext.setLocalProperty("spark.scheduler.pool", f"pool-0")
// var spark_stream0 = (
//     spark.readStream
//         .format("cloudFiles")
//         .schema(trigger_event_schema)
//         .option("cloudFiles.queueUrl", "https://sqs.us-east-1.amazonaws.com/623158188184/xdl-az-p-dev-013-t-log-event-main-queue")
//         .option("cloudFiles.region", AWS_QUEUE_REGION)
//         .option("cloudFiles.format", "binaryFile")
//         .option("cloudFiles.useNotifications", "true")
//         .option("cloudFiles.validateOptions", "false")
//         .option("cloudFiles.maxBytesPerTrigger", MAX_BYTES_PER_TRIGGER)
//         .option("cloudFiles.maxFilesPerTrigger", MAX_FILES_PER_TRIGGER)
//         .option("cloudFiles.includeExistingFiles", false)
//         .option("cloudFiles.partitionColumns", "")
//         .option("cloudFiles.fetchParallelism", FETCH_PARALLELISM)
//         .option("cloudFiles.visibilityTimeout", VISIBILITY_TIMEOUT)
//         .option("badRecordsPath", BAD_RECORDS_PATH)
// )
// var df0 = spark_stream0.load("s3://xdl-az-p-dev-013-t-fake-sae-bucket/")
// df0
//   .writeStream 
//   .format("JSON") 
//   .outputMode("append") 
//   .option("checkpointLocation", f"$CHECK_POINT_LOCATION%s//queue0") 
//   .option("compression", "gzip") 
//   .trigger(Trigger.ProcessingTime(TRIGGER_PROCESS_TIME))
//   .start("abfss://xdl@xdlazpdev013ti0.dfs.core.windows.net/databricks-out/queue0")


// spark.sparkContext.setLocalProperty("spark.scheduler.pool", f"pool-1")
// var spark_stream1 = (
//     spark.readStream
//         .format("cloudFiles")
//         .schema(trigger_event_schema)
//         .option("cloudFiles.queueUrl", "https://sqs.us-east-1.amazonaws.com/623158188184/xdl-az-p-dev-013-t-log-event-main-queue2")
//         .option("cloudFiles.region", AWS_QUEUE_REGION)
//         .option("cloudFiles.format", "binaryFile")
//         .option("cloudFiles.useNotifications", "true")
//         .option("cloudFiles.validateOptions", "false")
//         .option("cloudFiles.maxBytesPerTrigger", MAX_BYTES_PER_TRIGGER)
//         .option("cloudFiles.maxFilesPerTrigger", MAX_FILES_PER_TRIGGER)
//         .option("cloudFiles.includeExistingFiles", false)
//         .option("cloudFiles.partitionColumns", "")
//         .option("cloudFiles.fetchParallelism", FETCH_PARALLELISM)
//         .option("cloudFiles.visibilityTimeout", VISIBILITY_TIMEOUT)
//         .option("badRecordsPath", BAD_RECORDS_PATH)
// )
// var df1 = spark_stream1.load("s3://xdl-az-p-dev-013-t-fake-sae-bucket/")
// df1
//   .writeStream 
//   .format("JSON") 
//   .outputMode("append") 
//   .option("checkpointLocation", f"$CHECK_POINT_LOCATION%s//queue1") 
//   .option("compression", "gzip") 
//   .trigger(Trigger.ProcessingTime(TRIGGER_PROCESS_TIME))
//   .start("abfss://xdl@xdlazpdev013ti0.dfs.core.windows.net/databricks-out/queue1")
