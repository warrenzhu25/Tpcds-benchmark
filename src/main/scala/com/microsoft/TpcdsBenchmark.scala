package com.microsoft

import org.apache.spark.sql.{SQLContext, SparkSession}

/**
 * Usage: TpcdsBenchmark [dataDir] [result] [databaseName] [queries] [iterations]
 */
object TpcdsBenchmark {
  def main(args: Array[String]) {
    if (args.length < 2) {
      printUsage()
      System.exit(1)
    }

    val appConf = new AppConf(args)

    val dataDir = appConf.dataDir()
    val resultDir = appConf.resultDir()
    val databaseName = appConf.databaseName()
    val queries = appConf.queries()
    val iterations = appConf.iterations()

    println(s"AppConf are ${appConf.summary}")
    appConf.printHelp()

    val spark = SparkSession
      .builder
      .appName("TPC-DS Benchmark")
      .master("local")
      .config("spark.driver.bindAddress", "localhost")
      .config("spark.sql.adaptive.enabled", "true")
      .config("spark.sql.adaptive.forceApply", "true")
      .getOrCreate()

    val sqlContext = new SQLContext(spark.sparkContext)

    val tpcds = new TPCDS(sqlContext = sqlContext)
    val excludes = queries.split(",").toSet
    val queryMaps = if (queries.isEmpty) {
      tpcds.tpcds2_4QueriesMap.filterKeys(k => !excludes.contains(k))
    } else {
      val queryNames = queries.split(",").toSet
      tpcds.tpcds2_4QueriesMap.filterKeys(queryNames.contains)
    }

    println(s"Queries to Run: ${queryMaps.keys.mkString(",")}")

    val queriesToRun = queryMaps.values.toSeq
    // Run:
    val tables = new TPCDSTables(sqlContext,
      scaleFactor = "1",
      useDoubleForDecimal = false, // true to replace DecimalType with DoubleType
      useStringForDate = false) // true to replace DateType with StringType

    println(s"Cost base optimization is enabled. Create external table")
    // Create the specified database
    sqlContext.sql(s"create database $databaseName")
    // Create metastore tables in a specified database for your data.
    // Once tables are created, the current database will be switched to the specified database.
    tables.createExternalTables(dataDir, "parquet", databaseName, overwrite = true, discoverPartitions = true)
    // Create temporary tables to avoid concurrent benchmark run affecting each other
    // tables.createTemporaryTables(dataDir, "parquet")

    if(appConf.cbo()) {
      tables.analyzeTables(databaseName, analyzeColumns = true)
    }
    val timeout = 48 * 60 * 60 // timeout, in seconds.

    val experiment = tpcds.runExperiment(
      queriesToRun,
      iterations = iterations.toInt,
      resultLocation = resultDir,
      forkThread = true)
    experiment.waitForFinish(timeout)
  }

  private def printUsage(): Unit = {
    val usage = """ TpcdsBenchmark
                  |Usage: dataDir resultDir
                  |dataDir - (string) Directory contains tpcds dataset in parquet format
                  |resultDir - (string) Directory for writing benchmark result
                  |databaseName - (string) database name
                  |queries - (string) Queries to run separated by comma, such as 'q4,q5'. Default all
                  |iterations - (int) The number of iterations for each query. Default 1"""

    println(usage)
  }
}
