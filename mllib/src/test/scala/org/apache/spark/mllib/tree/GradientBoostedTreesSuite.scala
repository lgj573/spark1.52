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

package org.apache.spark.mllib.tree

import org.apache.spark.{Logging, SparkFunSuite}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.configuration.Algo._
import org.apache.spark.mllib.tree.configuration.{BoostingStrategy, Strategy}
import org.apache.spark.mllib.tree.impurity.Variance
import org.apache.spark.mllib.tree.loss.{AbsoluteError, SquaredError, LogLoss}
import org.apache.spark.mllib.tree.model.GradientBoostedTreesModel
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.util.Utils


/**
 * Test suite for [[GradientBoostedTrees]].
 * 梯度提升决策树
 */
class GradientBoostedTreesSuite extends SparkFunSuite with MLlibTestSparkContext with Logging {

  test("Regression with continuous features: SquaredError") {//连续特征的回归:平方误差
    GradientBoostedTreesSuite.testCombinations.foreach {
      case (numIterations, learningRate, subsamplingRate) =>
        val rdd = sc.parallelize(GradientBoostedTreesSuite.data, 2)

        val treeStrategy = new Strategy(algo = Regression, impurity = Variance, maxDepth = 2,
          categoricalFeaturesInfo = Map.empty, subsamplingRate = subsamplingRate)
        val boostingStrategy =
          new BoostingStrategy(treeStrategy, SquaredError, numIterations, learningRate)

        val gbt = GradientBoostedTrees.train(rdd, boostingStrategy)

        assert(gbt.trees.size === numIterations)
        try {
          EnsembleTestHelper.validateRegressor(gbt, GradientBoostedTreesSuite.data, 0.06)
        } catch {
          case e: java.lang.AssertionError =>
            logError(s"FAILED for numIterations=$numIterations, learningRate=$learningRate," +
              s" subsamplingRate=$subsamplingRate")
            throw e
        }

        val remappedInput = rdd.map(x => new LabeledPoint((x.label * 2) - 1, x.features))
        val dt = DecisionTree.train(remappedInput, treeStrategy)

        // Make sure trees are the same.确保树是一样的
        assert(gbt.trees.head.toString == dt.toString)
    }
  }
  //具有连续特征的回归:绝对误差
  test("Regression with continuous features: Absolute Error") {
    GradientBoostedTreesSuite.testCombinations.foreach {
      case (numIterations, learningRate, subsamplingRate) =>
        val rdd = sc.parallelize(GradientBoostedTreesSuite.data, 2)

        val treeStrategy = new Strategy(algo = Regression, impurity = Variance, maxDepth = 2,
          categoricalFeaturesInfo = Map.empty, subsamplingRate = subsamplingRate)
        val boostingStrategy =
          new BoostingStrategy(treeStrategy, AbsoluteError, numIterations, learningRate)

        val gbt = GradientBoostedTrees.train(rdd, boostingStrategy)

        assert(gbt.trees.size === numIterations)
        try {
          EnsembleTestHelper.validateRegressor(gbt, GradientBoostedTreesSuite.data, 0.85, "mae")
        } catch {
          case e: java.lang.AssertionError =>
            logError(s"FAILED for numIterations=$numIterations, learningRate=$learningRate," +
              s" subsamplingRate=$subsamplingRate")
            throw e
        }

        val remappedInput = rdd.map(x => new LabeledPoint((x.label * 2) - 1, x.features))
        val dt = DecisionTree.train(remappedInput, treeStrategy)

        // Make sure trees are the same.确保树是一样的
        assert(gbt.trees.head.toString == dt.toString)
    }
  }
  //具有连续特征的二元分类:日志丢失
  test("Binary classification with continuous features: Log Loss") {
    GradientBoostedTreesSuite.testCombinations.foreach {
      case (numIterations, learningRate, subsamplingRate) =>
        val rdd = sc.parallelize(GradientBoostedTreesSuite.data, 2)

        val treeStrategy = new Strategy(algo = Classification, impurity = Variance, maxDepth = 2,
          numClasses = 2, categoricalFeaturesInfo = Map.empty,
          subsamplingRate = subsamplingRate)
        val boostingStrategy =
          new BoostingStrategy(treeStrategy, LogLoss, numIterations, learningRate)

        val gbt = GradientBoostedTrees.train(rdd, boostingStrategy)

        assert(gbt.trees.size === numIterations)
        try {
          EnsembleTestHelper.validateClassifier(gbt, GradientBoostedTreesSuite.data, 0.9)
        } catch {
          case e: java.lang.AssertionError =>
            logError(s"FAILED for numIterations=$numIterations, learningRate=$learningRate," +
              s" subsamplingRate=$subsamplingRate")
            throw e
        }

        val remappedInput = rdd.map(x => new LabeledPoint((x.label * 2) - 1, x.features))
        val ensembleStrategy = treeStrategy.copy
        ensembleStrategy.algo = Regression
        ensembleStrategy.impurity = Variance
        val dt = DecisionTree.train(remappedInput, ensembleStrategy)

        // Make sure trees are the same.
        assert(gbt.trees.head.toString == dt.toString)
    }
  }
  //提升策略的默认参数应识别分类
  test("SPARK-5496: BoostingStrategy.defaultParams should recognize Classification") {
    for (algo <- Seq("classification", "Classification", "regression", "Regression")) {
      BoostingStrategy.defaultParams(algo)
    }
  }

  test("model save/load") {//模型保存/加载
    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString

    val trees = Range(0, 3).map(_ => DecisionTreeSuite.createModel(Regression)).toArray
    val treeWeights = Array(0.1, 0.3, 1.1)

    Array(Classification, Regression).foreach { algo =>
      val model = new GradientBoostedTreesModel(algo, trees, treeWeights)

      // Save model, load it back, and compare.
      //保存模型,加载它回来,并比较
      try {
        model.save(sc, path)
        val sameModel = GradientBoostedTreesModel.load(sc, path)
        assert(model.algo == sameModel.algo)
        model.trees.zip(sameModel.trees).foreach { case (treeA, treeB) =>
          DecisionTreeSuite.checkEqual(treeA, treeB)
        }
        assert(model.treeWeights === sameModel.treeWeights)
      } finally {
        Utils.deleteRecursively(tempDir)
      }
    }
  }

  test("runWithValidation stops early and performs better on a validation dataset") {
    // Set numIterations large enough so that it stops early.
    //集数足够大时,提前停止迭代
    val numIterations = 20
    val trainRdd = sc.parallelize(GradientBoostedTreesSuite.trainData, 2)
    val validateRdd = sc.parallelize(GradientBoostedTreesSuite.validateData, 2)

    val algos = Array(Regression, Regression, Classification)
    val losses = Array(SquaredError, AbsoluteError, LogLoss)
    algos.zip(losses).foreach { case (algo, loss) =>
      val treeStrategy = new Strategy(algo = algo, impurity = Variance, maxDepth = 2,
        categoricalFeaturesInfo = Map.empty)
      val boostingStrategy =
        new BoostingStrategy(treeStrategy, loss, numIterations, validationTol = 0.0)
      val gbtValidate = new GradientBoostedTrees(boostingStrategy)
        .runWithValidation(trainRdd, validateRdd)
      val numTrees = gbtValidate.numTrees
      assert(numTrees !== numIterations)

      // Test that it performs better on the validation dataset.
      //测试,它在验证数据集上表现得更好
      val gbt = new GradientBoostedTrees(boostingStrategy).run(trainRdd)
      val (errorWithoutValidation, errorWithValidation) = {
        if (algo == Classification) {
          val remappedRdd = validateRdd.map(x => new LabeledPoint(2 * x.label - 1, x.features))
          (loss.computeError(gbt, remappedRdd), loss.computeError(gbtValidate, remappedRdd))
        } else {
          (loss.computeError(gbt, validateRdd), loss.computeError(gbtValidate, validateRdd))
        }
      }
      assert(errorWithValidation <= errorWithoutValidation)

      // Test that results from evaluateEachIteration comply with runWithValidation.
      //测试结果评估每个迭代符合运行的验证
      // Note that convergenceTol is set to 0.0
      val evaluationArray = gbt.evaluateEachIteration(validateRdd, loss)
      assert(evaluationArray.length === numIterations)
      assert(evaluationArray(numTrees) > evaluationArray(numTrees - 1))
      var i = 1
      while (i < numTrees) {
        assert(evaluationArray(i) <= evaluationArray(i - 1))
        i += 1
      }
    }
  }

  test("Checkpointing") {//检查点
    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString
    sc.setCheckpointDir(path)

    val rdd = sc.parallelize(GradientBoostedTreesSuite.data, 2)

    val treeStrategy = new Strategy(algo = Regression, impurity = Variance, maxDepth = 2,
      categoricalFeaturesInfo = Map.empty, checkpointInterval = 2)
    val boostingStrategy = new BoostingStrategy(treeStrategy, SquaredError, 5, 0.1)

    val gbt = GradientBoostedTrees.train(rdd, boostingStrategy)

    sc.checkpointDir = None
    Utils.deleteRecursively(tempDir)
  }

}
/**
 * 梯度提高树
 */
private object GradientBoostedTreesSuite {

  // Combinations for estimators, learning rates and subsamplingRate
  //组合估计,利率和subsamplingrate学习
  val testCombinations = Array((10, 1.0, 1.0), (10, 0.1, 1.0), (10, 0.5, 0.75), (10, 0.1, 0.75))

  val data = EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 10, 100)
  val trainData = EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 20, 120)
  val validateData = EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 20, 80)
}
