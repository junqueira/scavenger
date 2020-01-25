/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.python

import scala.collection.mutable

import org.apache.commons.lang.RandomStringUtils

import org.apache.spark.SparkException
import org.apache.spark.internal.Logging
import org.apache.spark.python.ScavengerConf._
import org.apache.spark.sql.catalyst.plans.logical.LeafNode
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

import io.github.maropu.Utils._

object IntegrityConstraintDiscovery extends Logging {

  val tableConstraintsKey = "__TABLE_CONSTRAINTS_KEY__"

  private val MAX_TARGET_FIELD_NUM = 100
  private val NUM_INTEGERESTINGNESS_SYMBOLS = 3
  private val METADATA_EQUAL = ("EQ", "=", 1)
  private val METADATA_NOT_EQUAL = ("IQ", "!=", 0)
  private val METADATA_GREATER_THAN = ("GT", ">", 2)
  private val METADATA_LESS_THAN = ("LT", "<", 4)
  private val METADATA_PREDICATES = Seq(METADATA_EQUAL, METADATA_GREATER_THAN, METADATA_LESS_THAN)
  private val TUPLE_IDENTIFIERS = ("t1", "t2")

  private def isDebugEnabled = log.isDebugEnabled

  private def negateSymbol(s: String) = s match {
    case "EQ" => "IQ"
    case "IQ" => "EQ"
    case "GT" => "LTE"
    case "LT" => "GTE"
  }

  private def withSQLConf[T](pairs: (String, String)*)(f: => T): T= {
    val conf = SQLConf.get
    val (keys, values) = pairs.unzip
    val currentValues = keys.map { key =>
      if (conf.contains(key)) {
        Some(conf.getConfString(key))
      } else {
        None
      }
    }
    (keys, values).zipped.foreach { (k, v) =>
      assert(!SQLConf.staticConfKeys.contains(k))
      conf.setConfString(k, v)
    }
    try f finally {
      keys.zip(currentValues).foreach {
        case (key, Some(value)) => conf.setConfString(key, value)
        case (key, None) => conf.unsetConf(key)
      }
    }
  }

  private def withTempView[T](spark: SparkSession, df: DataFrame)(f: String => T): T = {
    val tempView = getRandomString("tempView_")
    df.createOrReplaceTempView(tempView)
    val ret = f(tempView)
    spark.sql(s"DROP VIEW $tempView")
    ret
  }

  private def getRandomString(prefix: String = ""): String = {
    s"$prefix${Utils.getFormattedClassName(this)}_${RandomStringUtils.randomNumeric(12)}"
  }

  private def catalogStats(sparkSession: SparkSession, table: String): Seq[(String, String)] = {
    val df = sparkSession.table(table)
    val tableNode = df.queryExecution.analyzed.collectLeaves().head.asInstanceOf[LeafNode]
    tableNode.computeStats().attributeStats.map { case (a, stats) =>
      val statStrings = Seq(
        stats.distinctCount.map(v => s"distinctCnt=$v"),
        stats.min.map(v => s"min=$v"),
        stats.max.map(v => s"max=$v"),
        stats.nullCount.map(v => s"nullCnt=$v"),
        stats.avgLen.map(v => s"avgLen=$v"),
        stats.maxLen.map(v => s"maxLen=$v")
      ).flatten
      a.name -> s"STATS(${statStrings.mkString(",")})"
    }.toSeq
  }

  private def checkConstraints(sparkSession: SparkSession, table: String): Seq[(String, String)] = {
    val fields = sparkSession.table(table).schema.filter { f =>
      f.dataType match {
        case IntegerType => true
        case FloatType | DoubleType => true
        case _ => false
      }
    }
    if (fields.nonEmpty) {
      val tableStats = {
        val df = sparkSession.table(table)
        val tableNode = df.queryExecution.analyzed.collectLeaves().head.asInstanceOf[LeafNode]
        tableNode.computeStats()
      }
      val minMaxStatMap = tableStats.attributeStats.map {
        kv => (kv._1.name, (kv._2.min, kv._2.max))
      }
      val minMaxAggExprs = fields.map(_.name).flatMap { columnName =>
        def aggFunc(f: String, c: String) = s"CAST($f($c) AS STRING)"
        val minFuncExpr = aggFunc("MIN", columnName)
        val maxFuncExpr = aggFunc("MAX", columnName)
        minMaxStatMap.get(columnName).map { case (minOpt, maxOpt) =>
          val minExpr = minOpt.map(v => s"STRING($v)").getOrElse(minFuncExpr)
          val maxExpr = maxOpt.map(v => s"STRING($v)").getOrElse(maxFuncExpr)
          minExpr :: maxExpr :: Nil
        }.getOrElse {
          minFuncExpr :: maxFuncExpr :: Nil
        }
      }

      val queryToComputeStats =
        s"""
           |SELECT ${minMaxAggExprs.mkString(", ")}
           |FROM $table
         """.stripMargin

      logDebug(queryToComputeStats)

      val minMaxStats = sparkSession.sql(queryToComputeStats).take(1).head
      fields.zipWithIndex.map { case (f, i) =>
        val minValue = minMaxStats.getString(2 * i)
        val maxValue = minMaxStats.getString(2 * i + 1)
        (f.name, s"CHK($minValue,$maxValue)")
      }
    } else {
      Seq.empty
    }
  }

  private def isValidMetadata(md: (StructField, (String, String, Int))): Boolean = md match {
    case (StructField(_, StringType, _, _), METADATA_EQUAL | METADATA_NOT_EQUAL) => true
    case (StructField(_, _: NumericType, _, _), _) => true
    case _ => false
  }

  private def denialConstraints(sparkSession: SparkSession, table: String): Seq[(String, String)] = {
    withSQLConf(SQLConf.CROSS_JOINS_ENABLED.key -> "true") {
      val fields = sparkSession.table(table).schema.filter { f =>
        f.dataType match {
          case IntegerType => true
          case StringType => true
          case _ => false
        }
      }
      if (fields.nonEmpty) {
        if (fields.length > MAX_TARGET_FIELD_NUM) {
          throw new SparkException(s"Maximum field length is $MAX_TARGET_FIELD_NUM, " +
            s"but ${fields.length} found.")
        }
        val (l, r) = ("leftTable", "rightTable")
        val colNames = fields.map(_.name)
        val (evExprs, whereExprs) = {
          val exprs = colNames.map { colName =>
            val (lc, rc) = (s"$l.$colName", s"$r.$colName")
            val evs = METADATA_PREDICATES.zipWithIndex.map { case ((_, cmp, _), pos) =>
              s"SHIFTLEFT(CAST($lc $cmp $rc AS BYTE), $pos)"
            }
            (s"(${evs.mkString(" | ")}) AS $colName",
              s"$lc != $rc")
          }
          (exprs.map(_._1).mkString(",\n"), exprs.map(_._2).mkString(" OR\n"))
        }
        val tableRowCount = sparkSession.table(table).count()
        val sampleRatio = {
          val samplingSize = sparkSession.sessionState.conf.samplingSize
          Math.min(samplingSize.toDouble / tableRowCount, 1.0)
        }
        // TODO: Since most of tuple pairs are redundant to enumerate approximate denial constraints,
        // we might be able to have a smarter sampling algorithm (e.g., focused sampling in Hydra)
        // than random sampling used here.
        //  * Tobias Bleifuß, Sebastian Kruse, and Felix Naumann, Efficient Denial Constraint Discovery
        //    with Hydra, Proceedings of VLDB Endowment, 11(3), 2017.
        val sampleTableDf = sparkSession.table(table).sample(sampleRatio)
        withTempView(sparkSession, sampleTableDf) { inputView =>
          val evQuery =
            s"""
               |SELECT
               |  ${colNames.mkString(", ")}, COUNT(1) AS cnt
               |FROM (
               |  SELECT
               |    $evExprs
               |  FROM
               |    /* table row count: $tableRowCount */
               |    /* sampling ratio: $sampleRatio */
               |    $inputView $l,
               |    $inputView $r
               |  WHERE
               |    $whereExprs
               |)
               |GROUP BY
               |  ${colNames.mkString(", ")}
             """.stripMargin

          val evVectors = sparkSession.sql(evQuery)
          val totalEvCount = evVectors.groupBy().sum("cnt").collect.map { case Row(l: Long) => l }.head
          logDebug(
            s"""
               |Number of evidences: $totalEvCount
               |Number of aggregated evidences: ${evVectors.count}
               |Query to compute evidences:
               |$evQuery
             """.stripMargin)

          if (isDebugEnabled) {
            val exprs = colNames.map { colName =>
              val masks = METADATA_PREDICATES.map { md => s"CAST($colName & ${md._3} > 0 AS INT)" }
              s"(${masks.mkString(" || ")}) AS $colName"
            }
            withTempView(sparkSession, evVectors.selectExpr(exprs :+ "cnt": _*)) { tempView =>
              // Aggregated evidence bitmap
              sparkSession.sql(
                s"""
                   |SELECT
                   |  ${colNames.mkString(", ")}, SUM(cnt) AS rt
                   |FROM
                   |  $tempView
                   |GROUP BY
                   |  ${colNames.mkString(", ")}
                 """.stripMargin
              ).show(numRows = 300, truncate = false)
            }
          }

          // Holds minimal denial constrains;
          //  - keys have a type of a set (comparator name, field name), e.g., ("EQ", "ZipCode")
          //  - values have denial constrains as strings, e.g.,
          //    "EQ(t1.HospitalName,t2.HospitalName)&IQ(t1.CountyName,t2.CountyName)"
          //
          // TODO: We might be able to support the case of different attributes,
          // e.g., "EQ(t1.columnName1, t2.columnName2)".
          //
          // "Predicates across two different attributes are regarded only as long as their attributes
          // have the same type and share at least 30% of common values [8]." cited by a paper below;
          //  * Eduardo H. M. Pena, Eduardo C. de Almeida, and Felix Naumann, Discovery of Approximate (and Exact)
          //    Denial Constraints, Proceedings of the VLDB Endowment , 13(3), p266-278, 2019.
          val minimalMap = mutable.Map[Set[(String, String)], String]()

          val numSymbols = NUM_INTEGERESTINGNESS_SYMBOLS
          val approxEpsilon = sparkSession.sessionState.conf.constraintInferenceApproximateEpilon
          val threshold = totalEvCount * (1.0 - approxEpsilon)

          (2 until numSymbols + 1).foreach { level =>
            outputConsole(s"Starts processing level $level/$numSymbols...")
            // TODO: We need to split a query into multiple ones if `exprToValidateConstraints`
            // has many expressions.
            val (metadataSeq, exprToComputeValidEvNum, aliasNames) = fields.combinations(level).flatMap { fs =>
              (METADATA_NOT_EQUAL +: METADATA_PREDICATES).combinations(level).flatMap { metadataSeq =>
                // Checks if all the subsets are not minimal
                def hasNoMinimalSubset = {
                  val fields = fs.map(_.name).zip(metadataSeq.map(_._1))
                  (1 until level).forall { subsetSize  =>
                    fields.combinations(subsetSize).forall(key => !minimalMap.contains(key.toSet))
                  }
                }
                val exprs = fs.zip(metadataSeq).filter(isValidMetadata)
                if (exprs.length == level && hasNoMinimalSubset) {
                  // The format of denial constraints refers to the HoloClean one:
                  //  - https://github.com/HoloClean/holoclean/blob/master/testdata/hospital_constraints.txt
                  val aliasName = exprs.map { v =>
                    s"${negateSymbol(v._2._1)}(${TUPLE_IDENTIFIERS._1}.${v._1.name}," +
                      s"${TUPLE_IDENTIFIERS._2}.${v._1.name})"
                  }.mkString("&")
                  val exprToValidateConstraint = exprs.map { case (f, metadata) =>
                    metadata match {
                      case ("IQ", _, _) => s"(${f.name} != ${METADATA_EQUAL._3})"
                      case (_, _, mask) => s"((${f.name} & $mask) > 0)"
                    }
                  }.mkString(" OR ")
                  Some((metadataSeq.map(_._1).zip(fs.map(_.name)),
                    s"SUM(IF($exprToValidateConstraint, cnt, 0))",
                    aliasName))
                } else {
                  None
                }
              }
            }.toSeq.unzip3

            // Computes minimal covers of the evidence set for searching denial constraints
            outputConsole(s"Starts processing ${exprToComputeValidEvNum.size} exprs to validate constraints...")
            if (exprToComputeValidEvNum.nonEmpty) {
              if (isDebugEnabled) {
                evVectors.selectExpr({
                  exprToComputeValidEvNum.zip(aliasNames).map(v => s"${v._1} AS `${v._2}`")
                }: _*).show(numRows = 1, truncate = 0, vertical = true)
              }
              val cDf = evVectors.selectExpr({
                exprToComputeValidEvNum.zip(aliasNames).map { case (expr, name) =>
                  s"$expr >= $threshold AS `$name`"
                }
              }: _*)
              // Sets false at NULL cells
              val cRow = cDf.na.fill(false).collect().head
              cDf.schema.zipWithIndex.foreach { case (f, i) =>
                if (cRow.getBoolean(i)) {
                  minimalMap += metadataSeq(i).toSet -> f.name
                }
              }
            }
          }

          minimalMap.values.map { c =>
            tableConstraintsKey -> s"${TUPLE_IDENTIFIERS._1}&${TUPLE_IDENTIFIERS._2}&$c"
          }.toSeq ++
            (if (sparkSession.sessionState.conf.constraintInferenceDc2fdConversionEnabled) {
              minimalMap.keys.map(_.toSeq).flatMap {
                case Seq(("EQ", x), ("IQ", y)) => y -> s"FD($y->$x)" :: Nil
                case Seq(("IQ", x), ("EQ", y)) => x -> s"FD($x->$y)" :: Nil
                case _ => Nil
              }
            } else {
              Nil
            })
        }
      } else {
        Seq.empty
      }
    }
  }

  def exec(sparkSession: SparkSession, table: String): Map[String, Seq[String]] = {
    if (sparkSession.table(table).schema.map(_.name).contains(tableConstraintsKey)) {
      throw new SparkException(s"$tableConstraintsKey cannot be used as a column name.")
    }
    val constraints = denialConstraints(sparkSession, table) ++ checkConstraints(sparkSession, table) ++
      catalogStats(sparkSession, table)
    constraints.groupBy(_._1).map { case (k, v) =>
      (k, v.map(_._2))
    }
  }
}
