package com.test

import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import scalapb.spark.Implicits._
import scalapb.json4s.JsonFormat
import com.trendmicro.sae.datatypes.protos.wrapper
import scala.collection.mutable.ArrayBuffer
import org.json4s._
import org.json4s.DefaultFormats._
import org.json4s.jackson.JsonMethods._

object ScalaPySparkUDFs {
  
val gpb_to_json = (raw_gpb: Array[Byte]) => {

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
           case _: Throwable => {
             is_parsed_failure_occurred = true
             error_type_set += "Got some other kind of exception"
           }
       }
    }else{
      isvalue = false
    }  
   }
  if(is_parsed_failure_occurred){
    json_res += f"""{ "packagePath": "path", "customerId": "customertest", "productCode": "Got some other kind of exception"}"""
  }
  json_res
}
// Register custom UDF
// val proto_udf = udf[ArrayBuffer[String],Array[Byte],String](gpb_to_json)
// val proto_udf_scala = udf(gpb_to_json)

def proto_udf_scala(): UserDefinedFunction = udf(gpb_to_json)

}