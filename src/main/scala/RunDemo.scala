package myexample

import protobuf.test.Person

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.SQLContext
import org.apache.spark.rdd.RDD
import scalapb.spark.Implicits._
import scalapb.spark.ProtoSQL


object RunDemo {

  def main(Args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("ScalaPB Demo").getOrCreate()

    val sc = spark.sparkContext

    val personsDF: DataFrame = ProtoSQL.createDataFrame(spark, testData)

    val personsDS1: Dataset[Person] = personsDF.as[Person]

    val personsDS2: Dataset[Person] = spark.createDataset(testData)

    val binaryDS: Dataset[Array[Byte]] = spark.createDataset(testData.map(_.toByteArray))
    
    val protosDS: Dataset[Person] = binaryDS.map(Person.parseFrom(_))

    binaryDS.show()

    protosDS.show()

    personsDF.createOrReplaceTempView("persons")

    spark.sql("SELECT name, age, gender, size(addresses) FROM persons").show()

    spark.sql("SELECT name, age, gender, size(addresses) FROM persons WHERE age > 30")
      .collect
      .foreach(println)
  }

  val testData = Seq(
   Person(name="chg",id=32),
   Person(name="Andrew", id=29)
)

  
}
