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

package org.apache.spark.ml.feature

import org.apache.spark.SparkFunSuite
import org.apache.spark.ml.param.ParamsSuite
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.sql.{DataFrame, Row}
/**
 * 二元
 */
class BinarizerSuite extends SparkFunSuite with MLlibTestSparkContext {

  @transient var data: Array[Double] = _

  override def beforeAll(): Unit = {
    super.beforeAll()
   val data = Array(0.1, -0.5, 0.2, -0.3, 0.8, 0.7, -0.1, -0.4)
  }

  test("params") {//参数
    ParamsSuite.checkParams(new Binarizer)
  }
//默认参数
  test("Binarize continuous features with default parameter") {//默认参数进行连续的特征
    //defaultBinarized=Array(1.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0)
    val defaultBinarized: Array[Double] = data.map(x => if (x > 0.0) 1.0 else 0.0)
    val dataFrame: DataFrame = sqlContext.createDataFrame(
      //res1= Array((0.1,1.0), (-0.5,0.0), (0.2,1.0),(-0.3,0.0), (0.8,1.0),(0.7,1.0),(-0.1,0.0),(-0.4,0.0))
      data.zip(defaultBinarized)).toDF("feature", "expected")
    //二元分类,输入字段feature,输出字段binarized_feature
    val binarizer: Binarizer = new Binarizer().setInputCol("feature").setOutputCol("binarized_feature")
    binarizer.transform(dataFrame).select("binarized_feature", "expected").collect().foreach {
      case Row(x: Double, y: Double) =>
        //println(x+"||||||"+y)
        assert(x === y, "The feature value is not correct after binarization.")
    }
  }
   //设置阀值setThreshold,设置二元连续特征
  test("Binarize continuous features with setter") {
    //阀值
    val threshold: Double = 0.2
    //thresholdBinarized: Array[Double] = Array(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0)
    val thresholdBinarized: Array[Double] = data.map(x => if (x > threshold) 1.0 else 0.0)
    val dataFrame: DataFrame = sqlContext.createDataFrame(
        //res2= Array((0.1,0.0),(-0.5,0.0),(0.2,0.0),(-0.3,0.0),(0.8,1.0),(0.7,1.0),(-0.1,0.0),(-0.4,0.0))
        data.zip(thresholdBinarized)).toDF("feature", "expected")

    val binarizer: Binarizer = new Binarizer().setInputCol("feature").setOutputCol("binarized_feature").setThreshold(threshold)

    binarizer.transform(dataFrame).select("binarized_feature", "expected").collect().foreach {
      case Row(x: Double, y: Double) =>
        //println(x+"||||||"+y)
        assert(x === y, "The feature value is not correct after binarization.")
    }
  }
}
