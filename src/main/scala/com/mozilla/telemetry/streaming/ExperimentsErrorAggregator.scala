// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package com.mozilla.telemetry.streaming

import java.sql.{Date, Timestamp}

import com.mozilla.spark.sql.hyperloglog.aggregates._
import com.mozilla.spark.sql.hyperloglog.functions._
import com.mozilla.telemetry.heka.{Dataset, Message}
import com.mozilla.telemetry.pings._
import com.mozilla.telemetry.timeseries._
import org.apache.spark.sql.types.{BinaryType, StructField, StructType}
import org.apache.spark.sql.functions.{sum, window, col, expr}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.catalyst.encoders.{ExpressionEncoder, RowEncoder}
import org.json4s._
import org.rogach.scallop.{ScallopConf, ScallopOption}
import org.joda.time.DateTime
import org.joda.time.{DateTime, Days, format}

object ExperimentErrorAggregator {

  val kafkaTopic = "telemetry"
  val outputPrefix = "experiment_error_aggregates/v1"
  val queryName = "experiment_error_aggregates"
  val defaultNumFiles = 20

  private val allowedDocTypes = List("main", "crash")
  private val allowedAppNames = List("Firefox")
  private val kafkaCacheMaxCapacity = 1000

  private class Opts(args: Array[String]) extends ScallopConf(args) {
    val kafkaBroker: ScallopOption[String] = opt[String](
      "kafkaBroker",
      descr = "Kafka broker (streaming mode only)",
      required = false)
    val from: ScallopOption[String] = opt[String](
      "from",
      descr = "Start submission date (batch mode only). Format: YYYYMMDD",
      required = false)
    val to: ScallopOption[String] = opt[String](
      "to",
      descr = "End submission date (batch mode only). Default: yesterday. Format: YYYYMMDD",
      required = false)
    val fileLimit: ScallopOption[Int] = opt[Int](
      "fileLimit",
      descr = "Max number of files to retrieve (batch mode only). Default: All files",
      required = false)
    val outputPath: ScallopOption[String] = opt[String](
      "outputPath",
      descr = "Output path",
      required = false,
      default = Some("/tmp/parquet"))
    val raiseOnError: ScallopOption[Boolean] = opt[Boolean](
      "raiseOnError",
      descr = "Whether the program should exit on a data processing error or not.")
    val failOnDataLoss: ScallopOption[Boolean] = opt[Boolean](
      "failOnDataLoss",
      descr = "Whether to fail the query when it’s possible that data is lost.")
    val checkpointPath: ScallopOption[String] = opt[String](
      "checkpointPath",
      descr = "Checkpoint path (streaming mode only)",
      required = false,
      default = Some("/tmp/checkpoint"))
    val startingOffsets: ScallopOption[String] = opt[String](
      "startingOffsets",
      descr = "Starting offsets (streaming mode only)",
      required = false,
      default = Some("latest"))
    val numParquetFiles: ScallopOption[Int] = opt[Int](
      "numParquetFiles",
      descr = "Number of parquet files per submission_date",
      required = false,
      default = Some(defaultNumFiles)
      )

    requireOne(kafkaBroker, from)
    conflicts(kafkaBroker, List(from, to, fileLimit, numParquetFiles))
    verify()
  }

  private val countHistogramErrorsSchema = (new SchemaBuilder()).build
  private val thresholdHistogramsSchema = (new SchemaBuilder()).build

  private val dimensionsSchema = new SchemaBuilder()
    .add[Timestamp]("timestamp")  // Windowed
    .add[Date]("submission_date")
    .add[String]("channel")
    .add[String]("version")
    .add[String]("os_name")
    .add[String]("experiment_id")
    .add[String]("experiment_branch")
    .build

  private val metricsSchema = new SchemaBuilder()
    .add[Float]("usage_hours")
    .add[Int]("count")
    .add[Int]("subsession_count")
    .add[Int]("main_crashes")
    .add[Int]("content_crashes")
    .add[Int]("gpu_crashes")
    .add[Int]("plugin_crashes")
    .add[Int]("gmplugin_crashes")
    .add[Int]("content_shutdown_crashes")
    .build

  private val statsSchema = metricsSchema

  // this part of the schema is used to temporarily hold
  // data that will not be part of the final schema
  private val tempSchema = new SchemaBuilder()
    .add[String]("client_id")
    .build

  private val HllMerge = new HyperLogLogMerge

  private[streaming] def aggregate(pings: DataFrame, raiseOnError: Boolean = false, online: Boolean = true): DataFrame = {
    import pings.sparkSession.implicits._

    // A custom row encoder is needed to use Rows within a Spark Dataset
    val mergedSchema = SchemaBuilder.merge(dimensionsSchema, statsSchema, tempSchema)
    implicit val rowEncoder = RowEncoder(mergedSchema).resolveAndBind()
    implicit val optEncoder = ExpressionEncoder.tuple(rowEncoder)

    var parsedPings = pings
      .flatMap( v => {
        try {
          parsePing(Message.parseFrom(v.get(0).asInstanceOf[Array[Byte]]))
        } catch {
          case _: Throwable if !raiseOnError => Array[Row]()
        }
      })

    if (online) {
      parsedPings = parsedPings.withWatermark("timestamp", "1 minute")
    }

    val dimensionsCols = List(
      window($"timestamp", "5 minute").as("window"),
      col("window.start").as("window_start"),
      col("window.end").as("window_end")
    ) ++ dimensionsSchema.fieldNames.filter(_ != "timestamp").map(col(_))

    val stats = statsSchema.fieldNames.map(_.toLowerCase)
    val sumCols = stats.map(s => sum(s).as(s))
    val aggCols = HllMerge($"client_hll").as("client_count") :: Nil ++ sumCols

    /*
    * The resulting DataFrame will contain the grouping columns + the columns aggregated.
    * Everything else gets dropped by .agg()
    * */
    parsedPings
      .withColumn("client_hll", expr("HllCreate(client_id, 12)"))
      .groupBy(dimensionsCols: _*)
      .agg(aggCols.head, aggCols.tail: _*)
      .drop("window")
      .coalesce(1)
  }

  private def buildDimensions(meta: Meta): Array[Row] = {
    // add a null experiment_id and experiment_branch for each ping
    val experiments = (meta.experiments :+ (None, None)).toSet.toArray

    experiments.map{ case (experiment_id, experiment_branch) =>
      val dimensions = new RowBuilder(dimensionsSchema)
      dimensions("timestamp") = Some(meta.normalizedTimestamp())
      dimensions("submission_date") = Some(new Date(meta.normalizedTimestamp().getTime))
      dimensions("channel") = Some(meta.normalizedChannel)
      dimensions("version") = meta.`environment.build`.flatMap(_.version)
      dimensions("os_name") = meta.`environment.system`.map(_.os.name)
      dimensions("experiment_id") = experiment_id
      dimensions("experiment_branch") = experiment_branch
      dimensions.build
    }
  }

  implicit class ExperimentErrorAggregatorCrashPing(ping: CrashPing) {
    def parse(): Array[Row] = {
      // Non-main crashes are already retrieved from main pings
      if(!ping.isMainCrash) throw new Exception("Only Crash pings of type `main` are allowed")
      val dimensions = buildDimensions(ping.meta)
      val stats = new RowBuilder(SchemaBuilder.merge(statsSchema, tempSchema))
      stats("count") = Some(1)
      stats("client_id") = ping.meta.clientId
      stats("main_crashes") = Some(1)

      dimensions.map(RowBuilder.merge(_, stats.build))
    }
  }

  implicit class ExperimentErrorAggregatorMainPing(ping: MainPing) {
    def parse(): Array[Row] = {
      // If a main ping has no usage hours discard it.
      val usageHours = ping.usageHours
      if (usageHours.isEmpty) throw new Exception("Main pings should have a  number of usage hours != 0")

      val dimensions = buildDimensions(ping.meta)
      val stats = new RowBuilder(SchemaBuilder.merge(statsSchema, tempSchema))
      stats("count") = Some(1)
      stats("subsession_count") = Some(1)
      stats("client_id") = ping.meta.clientId
      stats("usage_hours") = usageHours
      stats("content_crashes") = ping.getCountKeyedHistogramValue("SUBPROCESS_CRASHES_WITH_DUMP", "content")
      stats("gpu_crashes") = ping.getCountKeyedHistogramValue("SUBPROCESS_CRASHES_WITH_DUMP", "gpu")
      stats("plugin_crashes") = ping.getCountKeyedHistogramValue("SUBPROCESS_CRASHES_WITH_DUMP", "plugin")
      stats("gmplugin_crashes") = ping.getCountKeyedHistogramValue("SUBPROCESS_CRASHES_WITH_DUMP", "gmplugin")
      stats("content_shutdown_crashes") = ping.getCountKeyedHistogramValue("SUBPROCESS_KILL_HARD", "ShutDownKill")
      dimensions.map(RowBuilder.merge(_, stats.build))
    }
  }

  /*
   * We can't use an Option[Row] because entire rows cannot be null in Spark SQL.
   * The best we can do is to resort to use a container, e.g. Array.
   * This will also give us the ability to parse more than one row from the same ping.
   */
  def parsePing(message: Message): Array[Row] = {
    implicit val formats = DefaultFormats

    val fields = message.fieldsAsMap
    val docType = fields.getOrElse("docType", "").asInstanceOf[String]
    if (!allowedDocTypes.contains(docType)) {
      throw new Exception("Doctype should be one of " + allowedDocTypes.mkString(sep = ","))
    }
    val appName = fields.getOrElse("appName", "").asInstanceOf[String]
    if (!allowedAppNames.contains(appName)) {
      throw new Exception("AppName should be one of " + allowedAppNames.mkString(sep = ","))
    }
    if(docType == "crash") {
      CrashPing(message).parse()
    } else {
      MainPing(message).parse()
    }
  }

  def writeStreamingAggregates(spark: SparkSession, opts: Opts): Unit = {
    val pings = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", opts.kafkaBroker())
      .option("failOnDataLoss", opts.failOnDataLoss())
      .option("kafka.max.partition.fetch.bytes", 8 * 1024 * 1024) // 8MB
      .option("spark.streaming.kafka.consumer.cache.maxCapacity", kafkaCacheMaxCapacity)
      .option("subscribe", kafkaTopic)
      .option("startingOffsets", opts.startingOffsets())
      .load()

    val outputPath = opts.outputPath()

    aggregate(pings.select("value"), raiseOnError = opts.raiseOnError())
      .writeStream
      .queryName(queryName)
      .format("parquet")
      .option("path", s"${outputPath}/${outputPrefix}")
      .option("checkpointLocation", opts.checkpointPath())
      .partitionBy("submission_date")
      .start()
      .awaitTermination()
  }

  def writeBatchAggregates(spark: SparkSession, opts: Opts): Unit = {
    val fmt = format.DateTimeFormat.forPattern("yyyyMMdd")

    val from = fmt.parseDateTime(opts.from())
    val to = opts.to.get match {
      case Some(t) => fmt.parseDateTime(t)
      case _ => DateTime.now.minusDays(1)
    }

    implicit val sc = spark.sparkContext

    for (offset <- 0 to Days.daysBetween(from, to).getDays) {
      val currentDate = from.plusDays(offset).toString("yyyyMMdd")

      val pings = Dataset("telemetry")
        .where("sourceName") {
          case "telemetry" => true
        }.where("sourceVersion") {
          case "4" => true
        }.where("docType") {
          case docType if allowedDocTypes.contains(docType) => true
        }.where("appName") {
          case appName if allowedAppNames.contains(appName) => true
        }.where("submissionDate") {
          case date if date == currentDate => true
        }.records(opts.fileLimit.get)
        .map(m => Row(m.toByteArray))

      val schema = StructType(List(
          StructField("value", BinaryType, true)
      ))

      val pingsDataframe = spark.createDataFrame(pings, schema)
      val outputPath = opts.outputPath()

      aggregate(pingsDataframe, raiseOnError = opts.raiseOnError(), online = false)
        .repartition(opts.numParquetFiles())
        .write
        .mode("overwrite")
        .parquet(s"${outputPath}/${outputPrefix}/submission_date=${currentDate}")
    }

    spark.stop()
  }

  def main(args: Array[String]): Unit = {
    val opts = new Opts(args)

    val spark = SparkSession.builder()
      .appName("Error Aggregates")
      .config("spark.streaming.stopGracefullyOnShutdown", "true")
      .getOrCreate()

    spark.udf.register("HllCreate", hllCreate _)

    opts.kafkaBroker.get match {
      case Some(_) => writeStreamingAggregates(spark, opts)
      case None => writeBatchAggregates(spark, opts)
    }
  }

}
