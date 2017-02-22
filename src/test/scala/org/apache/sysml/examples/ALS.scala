/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.examples

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SparkSession
import org.apache.sysml.api.mlcontext.MLContext
import org.apache.sysml.api.linalg.api._
import org.apache.sysml.api.linalg._

object ALS extends App {

  val conf = new SparkConf()
    .setMaster("local[2]")
    .setAppName("SystemML Spark App")

  val sc: SparkContext = new SparkContext(conf)
//  val spark = SparkSession.builder().getOrCreate()
//
//  val ratingsText = sc.textFile("/data/netflix/training_set_normalized/mv_000*.txt")
//  val data = ratingsText.map(s => s.split(",")).map(arr => arr(0) + " " + arr(1) + " " + arr(2)).cache
//
//  data.take(5).foreach(println)
//
//  // get size of user-item matrix
//  val rows = data.map(s => s.split(" ")(0).toInt).max()  // number of rows for user-item matrix
//  val cols = data.map(s => s.split(" ")(1).toInt).max()  // number of cols for user-item matrix
//
//  // get train test split for cross-validation
//  // for simplicity, we'll just do random sampling
//  val Array(train, test) = data.randomSplit(Array(0.7, 0.3), 98765)

  //-----------------------------------------------------------------
  // Create kernel in SystemML's DSL using the R-like syntax for ALS
  // Algorithms available at : https://systemml.apache.org/algorithms
  // Below algorithm based on ALS-CG.dml
  //-----------------------------------------------------------------
  implicit val mlcontext = new MLContext(sc)

  val script1 = systemml {
    // parameters
    val r       = 5
    val reg     = "L2"
    val lambda  = 0.1
    val maxIter = 10
    val check   = true
    val thr     = 0.001

    // input data
    val X = read("/data/netflix/training_small", Format.CSV)
    val m = X.nrow
    val n = X.ncol

    // initializing factor matrices
    var U = Matrix.rand(m, r)
    var V = Matrix.rand(n, r)

    var W = ppred(X, 0, "!=")

    val losses = Vector.rand(maxIter * 2)

    println("BEGIN ALS-CG SCRIPT WITH NONZERO SQUARED LOSS + L2 WITH LAMBDA - " + lambda)
    val rowNnz = Vector.ones(W.nrow)
    val colNnz = Vector.ones(W.ncol)

    var isU = true
    var maxInnerIter = r
    var lossInit = 0.0

    if (check) {
      lossInit = 0.5 * sum (W * (U %*% V.t - X) ^ 2)
      lossInit = lossInit + 0.5 * lambda * (sum ((U^2) * rowNnz) + sum ((V^2) * colNnz))
      println("-----   Initial train loss: " + lossInit + " -----")
    }

    var it = 0
    var converged = false
    var G = Matrix.zeros(W.ncol, V.nrow)

    while (it/2 < maxIter-1 && !converged) {
      it = it + 1

      if( isU ) {
        G = (W * (U %*% V.t - X)) %*% V + lambda * U * rowNnz
      }
      else {
        G = (U.t %*% (W * (U %*% V.t - X))).t + lambda * V * colNnz
      }

      var R = -1 * G
      var S = R
      val normG2 = sum(G ^ 2)
      var normR2 = normG2

      var inneriter = 1
      var tt = 0.000000001

      var HS = Matrix.zeros(W.ncol, V.nrow)
      var alpha = 0.0

      while (normR2 > tt * normG2 && inneriter <= maxInnerIter) {
        if( isU ) {
          HS = (W * (S %*% V.t) %*% V) + lambda * S * rowNnz
          alpha = normR2 / sum (S * HS)
          U = U + alpha * S
        }
        else {
          HS = (U.t %*% (W * (U %*% S.t))).t + lambda * S * colNnz
          alpha = normR2 / sum(S * HS)
          V = V + alpha * S
        }

        R = R - alpha * HS
        val oldNormR2 = normR2
        normR2 = sum(R ^ 2)
        S = R + (normR2 / oldNormR2) * S
        inneriter = inneriter + 1
      }

      isU = ! isU

      // check for convergence
      var lossCur = 0.5 * sum(W * (U %*% V.t - X) ^ 2)
      lossCur = lossCur + 0.5 * lambda * (sum ((U ^ 2) * rowNnz) + sum ((V ^ 2) * colNnz))

      losses(it,0) = lossCur

      var lossDec = 0.0
      lossDec = (lossInit - lossCur) / lossInit
      println("Train loss at iteration (" + it + "): " + lossCur + " loss-dec " + lossDec)
      if( lossDec >= 0 && lossDec < thr || lossInit == 0 ) {
        println("----- ALS-CG converged after " + (it/2) + " iterations!")
        converged = true
      }
      lossInit = lossCur
    }

    if( check ) {
      println("----- Final train loss: " + lossInit + " -----")
    }

    if( !converged ) {
      println("Max iteration achieved but not converged!")
    }

    V = V.t

    (U, V, losses)
  }

  val (u, v, l) = script1.run(false)
  println(s"U: $u, V: $v, losses: $l")
}