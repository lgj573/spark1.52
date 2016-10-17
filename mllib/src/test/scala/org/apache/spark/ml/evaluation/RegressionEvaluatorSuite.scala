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

package org.apache.spark.ml.evaluation

import org.apache.spark.SparkFunSuite
import org.apache.spark.ml.param.ParamsSuite
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.mllib.util.{LinearDataGenerator, MLlibTestSparkContext}
import org.apache.spark.mllib.util.TestingUtils._

class RegressionEvaluatorSuite extends SparkFunSuite with MLlibTestSparkContext {

  test("params") {
    ParamsSuite.checkParams(new RegressionEvaluator)
  }

  test("Regression Evaluator: default params") {//评估回归:默认参数
    /**
     * Here is the instruction describing how to export the test data into CSV format
     * so we can validate the metrics compared with R's mmetric package.
     *
     * import org.apache.spark.mllib.util.LinearDataGenerator
     * val data = sc.parallelize(LinearDataGenerator.generateLinearInput(6.3,
     *   Array(4.7, 7.2), Array(0.9, -1.3), Array(0.7, 1.2), 100, 42, 0.1))
     * data.map(x=> x.label + ", " + x.features(0) + ", " + x.features(1))
     *   .saveAsTextFile("path")
     */
    val dataset = sqlContext.createDataFrame(
      sc.parallelize(LinearDataGenerator.generateLinearInput(
        6.3, Array(4.7, 7.2), Array(0.9, -1.3), Array(0.7, 1.2), 100, 42, 0.1), 2))

    /**
     * Using the following R code to load the data, train the model and evaluate metrics.
     * 使用下面的R代码加载数据，训练模型和评估指标
     * > library("glmnet")
     * > library("rminer")
     * > data <- read.csv("path", header=FALSE, stringsAsFactors=FALSE)
     * > features <- as.matrix(data.frame(as.numeric(data$V2), as.numeric(data$V3)))
     * > label <- as.numeric(data$V1)
     * > model <- glmnet(features, label, family="gaussian", alpha = 0, lambda = 0)
     * > rmse <- mmetric(label, predict(model, features), metric='RMSE')
     * > mae <- mmetric(label, predict(model, features), metric='MAE')
     * > r2 <- mmetric(label, predict(model, features), metric='R2')
     */
    val trainer = new LinearRegression
    val model = trainer.fit(dataset) //转换
    //Prediction 预测
    val predictions = model.transform(dataset)
    predictions.collect()

    // default = rmse
    val evaluator = new RegressionEvaluator()
    assert(evaluator.evaluate(predictions) ~== 0.1019382 absTol 0.001)

    // r2 score 评分
    evaluator.setMetricName("r2")
    assert(evaluator.evaluate(predictions) ~== 0.9998196 absTol 0.001)

    // mae 更多
    evaluator.setMetricName("mae")
    assert(evaluator.evaluate(predictions) ~== 0.08036075 absTol 0.001)
  }
}
