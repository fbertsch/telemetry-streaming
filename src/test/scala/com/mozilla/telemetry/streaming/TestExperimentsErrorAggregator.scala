// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package com.mozilla.telemetry.streaming

import java.sql.Timestamp
import java.util.Properties

import kafka.admin.AdminUtils
import kafka.utils.ZkUtils

import com.mozilla.spark.sql.hyperloglog.functions.{hllCreate, hllCardinality}
import com.mozilla.telemetry.streaming.TestUtils.todayDays
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.joda.time.{Duration, DateTime}
import org.json4s.DefaultFormats
import org.scalatest.{BeforeAndAfterAll, AsyncFlatSpec, Matchers, Tag}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.sys.process._

class TestExperimentErrorAggregator extends AsyncFlatSpec with Matchers with BeforeAndAfterAll {

  implicit val formats = DefaultFormats
  val k = TestUtils.scalarValue
  val app = TestUtils.application
  val spark = SparkSession.builder()
    .appName("Error Aggregates")
    .config("spark.streaming.stopGracefullyOnShutdown", "true")
    .master("local[1]")
    .getOrCreate()

  spark.udf.register("HllCreate", hllCreate _)
  spark.udf.register("HllCardinality", hllCardinality _)

  "The aggregator" should "sum metrics over a set of dimensions" in {
    import spark.implicits._
    val messages =
      (TestUtils.generateCrashMessages(k)
        ++ TestUtils.generateMainMessages(k)).map(_.toByteArray).seq
    val df = ExperimentErrorAggregator.aggregate(spark.sqlContext.createDataset(messages).toDF, raiseOnError = true, online = false)

    // 1 for each experiment (there are 2), and one for a null experiment
    df.count() should be (3)
    val inspectedFields = List(
      "submission_date",
      "channel",
      "version",
      "os_name",
      "experiment_id",
      "experiment_branch",
      "main_crashes",
      "content_crashes",
      "gpu_crashes",
      "plugin_crashes",
      "gmplugin_crashes",
      "content_shutdown_crashes",
      "count",
      "subsession_count",
      "usage_hours",
      "window_start",
      "window_end",
      "HllCardinality(client_count) as client_count"
    )

    val query = df.selectExpr(inspectedFields:_*)
    val columns = query.columns
    val results = columns.zip(columns.map(field => query.collect().map(row => row.getAs[Any](field)).toSet)).toMap

    results("submission_date").map(_.toString) should be (Set("2016-04-07"))
    results("channel") should be (Set(app.channel))
    results("os_name") should be (Set("Linux"))
    results("main_crashes") should be (Set(k))
    results("content_crashes") should be (Set(k))
    results("gpu_crashes") should be (Set(k))
    results("plugin_crashes") should be (Set(k))
    results("gmplugin_crashes") should be (Set(k))
    results("content_shutdown_crashes") should be (Set(k))
    results("count") should be (Set(k * 2))
    results("subsession_count") should be (Set(k))
    results("usage_hours") should be (Set(k.toFloat))
    results("experiment_id") should be (Set("experiment1", "experiment2", null))
    results("experiment_branch") should be (Set("control", "chaos", null))
    results("window_start").head.asInstanceOf[Timestamp].getTime should be <= (TestUtils.testTimestampMillis)
    results("window_end").head.asInstanceOf[Timestamp].getTime should be >= (TestUtils.testTimestampMillis)
    results("client_count") should be (Set(1))
  }
}
