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

package org.apache.spark.ml.recommendation

import java.io.File
import java.util.Random

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.language.existentials

import com.github.fommil.netlib.BLAS.{getInstance => blas}

import org.apache.spark.{Logging, SparkException, SparkFunSuite}
import org.apache.spark.ml.recommendation.ALS._
import org.apache.spark.ml.util.MLTestingUtils
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.util.Utils

class ALSSuite extends SparkFunSuite with MLlibTestSparkContext with Logging {

  private var tempDir: File = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    tempDir = Utils.createTempDir()
    sc.setCheckpointDir(tempDir.getAbsolutePath)
  }

  override def afterAll(): Unit = {
    Utils.deleteRecursively(tempDir)
    super.afterAll()
  }

  test("LocalIndexEncoder") {//本地索引编码
    val random = new Random
    for (numBlocks <- Seq(1, 2, 5, 10, 20, 50, 100)) {
      val encoder = new LocalIndexEncoder(numBlocks)
      val maxLocalIndex = Int.MaxValue / numBlocks
      val tests = Seq.fill(5)((random.nextInt(numBlocks), random.nextInt(maxLocalIndex))) ++
        Seq((0, 0), (numBlocks - 1, maxLocalIndex))
      tests.foreach { case (blockId, localIndex) =>
        val err = s"Failed with numBlocks=$numBlocks, blockId=$blockId, and localIndex=$localIndex."
        val encoded = encoder.encode(blockId, localIndex)
        assert(encoder.blockId(encoded) === blockId, err)
        assert(encoder.localIndex(encoded) === localIndex, err)
      }
    }
  }

  test("normal equation construction") {//构建标准相等
    //线性回归
    val k = 2
    //NormalEquation 另一种线性回归方法
    val ne0 = new NormalEquation(k).add(Array(1.0f, 2.0f), 3.0).add(Array(4.0f, 5.0f), 6.0, 2.0) // weighted
    assert(ne0.k === k)
    assert(ne0.triK === k * (k + 1) / 2)
    // NumPy code that computes the expected values:
    // A = np.matrix("1 2; 4 5")
    // b = np.matrix("3; 6")
    // C = np.matrix(np.diag([1, 2]))
    // ata = A.transpose() * C * A
    // atb = A.transpose() * C * b
    assert(Vectors.dense(ne0.ata) ~== Vectors.dense(33.0, 42.0, 54.0) relTol 1e-8)
    assert(Vectors.dense(ne0.atb) ~== Vectors.dense(51.0, 66.0) relTol 1e-8)

    val ne1 = new NormalEquation(2)
      .add(Array(7.0f, 8.0f), 9.0)
    ne0.merge(ne1)
    // NumPy code that computes the expected values:
    // A = np.matrix("1 2; 4 5; 7 8")
    // b = np.matrix("3; 6; 9")
    // C = np.matrix(np.diag([1, 2, 1]))
    // ata = A.transpose() * C * A
    // atb = A.transpose() * C * b
    assert(Vectors.dense(ne0.ata) ~== Vectors.dense(82.0, 98.0, 118.0) relTol 1e-8)
    assert(Vectors.dense(ne0.atb) ~== Vectors.dense(114.0, 138.0) relTol 1e-8)

    intercept[IllegalArgumentException] {
      ne0.add(Array(1.0f), 2.0)
    }
    intercept[IllegalArgumentException] {
      ne0.add(Array(1.0f, 2.0f, 3.0f), 4.0)
    }
    intercept[IllegalArgumentException] {
      ne0.add(Array(1.0f, 2.0f), 0.0, -1.0)
    }
    intercept[IllegalArgumentException] {
      val ne2 = new NormalEquation(3)
      ne0.merge(ne2)
    }

    ne0.reset()
    assert(ne0.ata.forall(_ == 0.0))
    assert(ne0.atb.forall(_ == 0.0))
  }
 //Cholesky 分解是把一个对称正定的矩阵表示成一个下三角矩阵L和其转置的乘积的分解
  test("CholeskySolver") {
    val k = 2
    val ne0 = new NormalEquation(k)
      .add(Array(1.0f, 2.0f), 4.0)
      .add(Array(1.0f, 3.0f), 9.0)
      .add(Array(1.0f, 4.0f), 16.0)
    val ne1 = new NormalEquation(k)
      .merge(ne0)

    val chol = new CholeskySolver
    val x0 = chol.solve(ne0, 0.0).map(_.toDouble)
    // NumPy code that computes the expected solution:
    // A = np.matrix("1 2; 1 3; 1 4")
    // b = b = np.matrix("3; 6")
    // x0 = np.linalg.lstsq(A, b)[0]
    assert(Vectors.dense(x0) ~== Vectors.dense(-8.333333, 6.0) relTol 1e-6)

    assert(ne0.ata.forall(_ == 0.0))
    assert(ne0.atb.forall(_ == 0.0))

    val x1 = chol.solve(ne1, 1.5).map(_.toDouble)
    // NumPy code that computes the expected solution, where lambda is scaled by n:
    // x0 = np.linalg.solve(A.transpose() * A + 1.5 * np.eye(2), A.transpose() * b)
    assert(Vectors.dense(x1) ~== Vectors.dense(-0.1155556, 3.28) relTol 1e-6)
  }

  test("RatingBlockBuilder") {//评级模块生成器
    val emptyBuilder = new RatingBlockBuilder[Int]()
    assert(emptyBuilder.size === 0)
    val emptyBlock = emptyBuilder.build()
    assert(emptyBlock.srcIds.isEmpty)
    assert(emptyBlock.dstIds.isEmpty)
    assert(emptyBlock.ratings.isEmpty)

    val builder0 = new RatingBlockBuilder()
      .add(Rating(0, 1, 2.0f))
      .add(Rating(3, 4, 5.0f))
    assert(builder0.size === 2)
    val builder1 = new RatingBlockBuilder()
      .add(Rating(6, 7, 8.0f))//
      .merge(builder0.build())
    assert(builder1.size === 3)//合并
    val block = builder1.build()
    val ratings = Seq.tabulate(block.size) { i =>
      (block.srcIds(i), block.dstIds(i), block.ratings(i))
    }.toSet
    assert(ratings === Set((0, 1, 2.0f), (3, 4, 5.0f), (6, 7, 8.0f)))
  }

  test("UncompressedInBlock") {//未压缩的块
    val encoder = new LocalIndexEncoder(10)
    val uncompressed = new UncompressedInBlockBuilder[Int](encoder)
      .add(0, Array(1, 0, 2), Array(0, 1, 4), Array(1.0f, 2.0f, 3.0f))
      .add(1, Array(3, 0), Array(2, 5), Array(4.0f, 5.0f))
      .build()
    assert(uncompressed.length === 5)
    val records = Seq.tabulate(uncompressed.length) { i =>
      val dstEncodedIndex = uncompressed.dstEncodedIndices(i)
      val dstBlockId = encoder.blockId(dstEncodedIndex)
      val dstLocalIndex = encoder.localIndex(dstEncodedIndex)
      (uncompressed.srcIds(i), dstBlockId, dstLocalIndex, uncompressed.ratings(i))
    }.toSet
    val expected =
      Set((1, 0, 0, 1.0f), (0, 0, 1, 2.0f), (2, 0, 4, 3.0f), (3, 1, 2, 4.0f), (0, 1, 5, 5.0f))
    assert(records === expected)

    val compressed = uncompressed.compress()
    assert(compressed.size === 5)
    assert(compressed.srcIds.toSeq === Seq(0, 1, 2, 3))
    assert(compressed.dstPtrs.toSeq === Seq(0, 2, 3, 4, 5))
    var decompressed = ArrayBuffer.empty[(Int, Int, Int, Float)]
    var i = 0
    while (i < compressed.srcIds.size) {
      var j = compressed.dstPtrs(i)
      while (j < compressed.dstPtrs(i + 1)) {
        val dstEncodedIndex = compressed.dstEncodedIndices(j)
        val dstBlockId = encoder.blockId(dstEncodedIndex)
        val dstLocalIndex = encoder.localIndex(dstEncodedIndex)
        decompressed += ((compressed.srcIds(i), dstBlockId, dstLocalIndex, compressed.ratings(j)))
        j += 1
      }
      i += 1
    }
    assert(decompressed.toSet === expected)
  }

  /**
   * Generates an explicit feedback dataset for testing ALS.
   * 生成用于测试的ALS一个明确的反馈数据
   * @param numUsers number of users 用户数
   * @param numItems number of items 项目数
   * @param rank rank 等级
   * @param noiseStd the standard deviation of additive Gaussian noise on training data
   * 				训练数据中加性高斯噪声的标准偏差
   * @param seed random seed 随机种子
   * @return (training, test)
   */
  def genExplicitTestData(
      numUsers: Int,
      numItems: Int,
      rank: Int,
      noiseStd: Double = 0.0,
      seed: Long = 11L): (RDD[Rating[Int]], RDD[Rating[Int]]) = {
    val trainingFraction = 0.6
    val testFraction = 0.3
    val totalFraction = trainingFraction + testFraction
    val random = new Random(seed)
    val userFactors = genFactors(numUsers, rank, random)
    val itemFactors = genFactors(numItems, rank, random)
    val training = ArrayBuffer.empty[Rating[Int]]
    val test = ArrayBuffer.empty[Rating[Int]]
    for ((userId, userFactor) <- userFactors; (itemId, itemFactor) <- itemFactors) {
      val x = random.nextDouble()
      if (x < totalFraction) {
        val rating = blas.sdot(rank, userFactor, 1, itemFactor, 1)
        if (x < trainingFraction) {
          val noise = noiseStd * random.nextGaussian()
          training += Rating(userId, itemId, rating + noise.toFloat)
        } else {
          test += Rating(userId, itemId, rating)
        }
      }
    }
    logInfo(s"Generated an explicit feedback dataset with ${training.size} ratings for training " +
      s"and ${test.size} for test.")
    (sc.parallelize(training, 2), sc.parallelize(test, 2))
  }

  /**
   * Generates an implicit feedback dataset for testing ALS.
   * 生成用于测试的ALS的隐式反馈数据
   * @param numUsers number of users 用户数
   * @param numItems number of items 项目数
   * @param rank rank 等级
   * @param noiseStd the standard deviation of additive Gaussian noise on training data
   * @param seed random seed
   * @return (training, test)
   */
  def genImplicitTestData(
      numUsers: Int,
      numItems: Int,
      rank: Int,
      noiseStd: Double = 0.0,
      seed: Long = 11L): (RDD[Rating[Int]], RDD[Rating[Int]]) = {
    // The assumption of the implicit feedback model is that unobserved ratings are more likely to
    // be negatives.
    //隐式反馈模型的假设是不可观测的评级更可能是消极的
    val positiveFraction = 0.8 //正分数
    val negativeFraction = 1.0 - positiveFraction //负分数
    val trainingFraction = 0.6//训练部分
    val testFraction = 0.3 //测试分数
    val totalFraction = trainingFraction + testFraction
    val random = new Random(seed)
    val userFactors = genFactors(numUsers, rank, random)
    val itemFactors = genFactors(numItems, rank, random)
    val training = ArrayBuffer.empty[Rating[Int]]
    val test = ArrayBuffer.empty[Rating[Int]]
    for ((userId, userFactor) <- userFactors; (itemId, itemFactor) <- itemFactors) {
      val rating = blas.sdot(rank, userFactor, 1, itemFactor, 1)
      val threshold = if (rating > 0) positiveFraction else negativeFraction
      val observed = random.nextDouble() < threshold
      if (observed) {
        val x = random.nextDouble()
        if (x < totalFraction) {
          if (x < trainingFraction) {
            val noise = noiseStd * random.nextGaussian()
            training += Rating(userId, itemId, rating + noise.toFloat)
          } else {
            test += Rating(userId, itemId, rating)
          }
        }
      }
    }
    logInfo(s"Generated an implicit feedback dataset with ${training.size} ratings for training " +
      s"and ${test.size} for test.")
    (sc.parallelize(training, 2), sc.parallelize(test, 2))
  }

  /**
   * Generates random user/item factors, with i.i.d. values drawn from U(a, b).
   * 产生随机的用户/项目因素
   * @param size number of users/items
   * @param rank number of features
   * @param random random number generator
   * @param a min value of the support (default: -1)
   * @param b max value of the support (default: 1)
   * @return a sequence of (ID, factors) pairs
   */
  private def genFactors(
      size: Int,
      rank: Int,
      random: Random,
      a: Float = -1.0f,
      b: Float = 1.0f): Seq[(Int, Array[Float])] = {
    require(size > 0 && size < Int.MaxValue / 3)
    require(b > a)
    val ids = mutable.Set.empty[Int]
    while (ids.size < size) {
      ids += random.nextInt()
    }
    val width = b - a
    ids.toSeq.sorted.map(id => (id, Array.fill(rank)(a + random.nextFloat() * width)))
  }

  /**
   * Test ALS using the given training/test splits and parameters.
   * @param training training dataset
   * @param test test dataset
   * @param rank rank of the matrix factorization
   * @param maxIter max number of iterations
   * @param regParam regularization constant
   * @param implicitPrefs whether to use implicit preference
   * @param numUserBlocks number of user blocks
   * @param numItemBlocks number of item blocks
   * @param targetRMSE target test RMSE
   */
  def testALS(
      training: RDD[Rating[Int]],
      test: RDD[Rating[Int]],
      rank: Int,
      maxIter: Int,
      regParam: Double,
      implicitPrefs: Boolean = false,
      numUserBlocks: Int = 2,
      numItemBlocks: Int = 3,
      targetRMSE: Double = 0.05): Unit = {
    val sqlContext = this.sqlContext
    import sqlContext.implicits._
    val als = new ALS()
      .setRank(rank)//是模型中隐语义因子的个数
      .setRegParam(regParam)
      .setImplicitPrefs(implicitPrefs)//决定了是用显性反馈ALS的版本还是用适用隐性反馈数据集的版本
      .setNumUserBlocks(numUserBlocks)//是用于并行化计算的分块个数,设置为-1,为自动配置
      .setNumItemBlocks(numItemBlocks)//是用于并行化计算的分块个数 (设置为-1,为自动配置)
      .setSeed(0) //随机种子
    //可以调整这些参数，不断优化结果，使均方差变小。比如：iterations越多，lambda较小，均方差会较小，推荐结果较优。
    val alpha = als.getAlpha //是一个针对于隐性反馈 ALS 版本的参数，这个参数决定了偏好行为强度的基准
    val model = als.fit(training.toDF())
    val predictions = model.transform(test.toDF())
      .select("rating", "prediction")
      //实际评级,预测评级
      .map { case Row(rating: Float, prediction: Float) =>
        (rating.toDouble, prediction.toDouble)
      }
    //rmse 均方根误差亦称标准误差,
    //均方根误差常用下式表示：√[∑di^2/n]=Re，式中：n为测量次数；di为一组测量值与真值的偏差
    //均方根值（RMS）、均方根误差（RMSE）
    val rmse =
      if (implicitPrefs) {//隐式
        // TODO: Use a better (rank-based?) evaluation metric for implicit feedback.
        // We limit the ratings and the predictions to interval [0, 1] and compute the weighted RMSE
        // with the confidence scores as weights.
        val (totalWeight, weightedSumSq) = predictions.map { case (rating, prediction) =>
          val confidence = 1.0 + alpha * math.abs(rating)
          val rating01 = math.max(math.min(rating, 1.0), 0.0)
          val prediction01 = math.max(math.min(prediction, 1.0), 0.0)
          val err = prediction01 - rating01
          (confidence, confidence * err * err)
        }.reduce { case ((c0, e0), (c1, e1)) =>
          (c0 + c1, e0 + e1)
        }
        math.sqrt(weightedSumSq / totalWeight)
      } else {//明确
          val mse = predictions.map { case (rating, prediction) =>
          val err = rating - prediction
          err * err //平方
        }.mean()//平均数
        math.sqrt(mse)//平方根
      }
    logInfo(s"Test RMSE is $rmse.")
    //targetRMSE: Double = 0.05
    //均方差越小，推荐结果较优
    assert(rmse < targetRMSE)

    // copied model must have the same parent.
    //复制的模型必须有相同的父
    MLTestingUtils.checkCopy(model)
  }

  test("exact rank-1 matrix") {//准确的秩1矩阵
    val (training, test) = genExplicitTestData(numUsers = 20, numItems = 40, rank = 1)
    testALS(training, test, maxIter = 1, rank = 1, regParam = 1e-5, targetRMSE = 0.001)
    testALS(training, test, maxIter = 1, rank = 2, regParam = 1e-5, targetRMSE = 0.001)
  }

  test("approximate rank-1 matrix") {//近似秩1矩阵
    val (training, test) =
      genExplicitTestData(numUsers = 20, numItems = 40, rank = 1, noiseStd = 0.01)
    testALS(training, test, maxIter = 2, rank = 1, regParam = 0.01, targetRMSE = 0.02)
    testALS(training, test, maxIter = 2, rank = 2, regParam = 0.01, targetRMSE = 0.02)
  }

  test("approximate rank-2 matrix") {//近似似秩2矩阵
    val (training, test) =
      genExplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    testALS(training, test, maxIter = 4, rank = 2, regParam = 0.01, targetRMSE = 0.03)
    testALS(training, test, maxIter = 4, rank = 3, regParam = 0.01, targetRMSE = 0.03)
  }

  test("different block settings") {//不同块设置
    val (training, test) =
      genExplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    for ((numUserBlocks, numItemBlocks) <- Seq((1, 1), (1, 2), (2, 1), (2, 2))) {
      testALS(training, test, maxIter = 4, rank = 3, regParam = 0.01, targetRMSE = 0.03,
        numUserBlocks = numUserBlocks, numItemBlocks = numItemBlocks)
    }
  }

  test("more blocks than ratings") {//多块比评级
    val (training, test) =
      genExplicitTestData(numUsers = 4, numItems = 4, rank = 1)
    testALS(training, test, maxIter = 2, rank = 1, regParam = 1e-4, targetRMSE = 0.002,
     numItemBlocks = 5, numUserBlocks = 5)
  }

  test("implicit feedback") {//隐式反馈
    val (training, test) =
      genImplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    testALS(training, test, maxIter = 4, rank = 2, regParam = 0.01, implicitPrefs = true,
      targetRMSE = 0.3)
  }

  test("using generic ID types") {//使用通用的身份标识类型
    val (ratings, _) = genImplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)

    val longRatings = ratings.map(r => Rating(r.user.toLong, r.item.toLong, r.rating))
    val (longUserFactors, _) = ALS.train(longRatings, rank = 2, maxIter = 4, seed = 0)
    assert(longUserFactors.first()._1.getClass === classOf[Long])

    val strRatings = ratings.map(r => Rating(r.user.toString, r.item.toString, r.rating))
    val (strUserFactors, _) = ALS.train(strRatings, rank = 2, maxIter = 4, seed = 0)
    assert(strUserFactors.first()._1.getClass === classOf[String])
  }

  test("nonnegative constraint") {//非负约束
    val (ratings, _) = genImplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    val (userFactors, itemFactors) =
      ALS.train(ratings, rank = 2, maxIter = 4, nonnegative = true, seed = 0)
    def isNonnegative(factors: RDD[(Int, Array[Float])]): Boolean = {
      factors.values.map { _.forall(_ >= 0.0) }.reduce(_ && _)
    }
    assert(isNonnegative(userFactors))
    assert(isNonnegative(itemFactors))
    // TODO: Validate the solution.
  }

  test("als partitioner is a projection") {//als分区是一个投影
    for (p <- Seq(1, 10, 100, 1000)) {
      val part = new ALSPartitioner(p)
      var k = 0
      while (k < p) {
        assert(k === part.getPartition(k))
        assert(k === part.getPartition(k.toLong))
        k += 1
      }
    }
  }

  test("partitioner in returned factors") {//在返回的因素划分
    val (ratings, _) = genImplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    val (userFactors, itemFactors) = ALS.train(
      ratings, rank = 2, maxIter = 4, numUserBlocks = 3, numItemBlocks = 4, seed = 0)
    for ((tpe, factors) <- Seq(("User", userFactors), ("Item", itemFactors))) {
      assert(userFactors.partitioner.isDefined, s"$tpe factors should have partitioner.")
      val part = userFactors.partitioner.get
      userFactors.mapPartitionsWithIndex { (idx, items) =>
        items.foreach { case (id, _) =>
          if (part.getPartition(id) != idx) {
            throw new SparkException(s"$tpe with ID $id should not be in partition $idx.")
          }
        }
        Iterator.empty
      }.count()
    }
  }

  test("als with large number of iterations") {//als具有大量的迭代
    val (ratings, _) = genExplicitTestData(numUsers = 4, numItems = 4, rank = 1)
    ALS.train(ratings, rank = 1, maxIter = 50, numUserBlocks = 2, numItemBlocks = 2, seed = 0)
    ALS.train(ratings, rank = 1, maxIter = 50, numUserBlocks = 2, numItemBlocks = 2,
      implicitPrefs = true, seed = 0)
  }
}
