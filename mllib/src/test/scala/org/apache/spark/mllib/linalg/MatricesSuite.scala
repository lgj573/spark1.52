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

package org.apache.spark.mllib.linalg

import java.util.Random

import org.mockito.Mockito.when
import org.scalatest.mock.MockitoSugar._
import scala.collection.mutable.{Map => MutableMap}

import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.util.TestingUtils._
/**
 * 本地矩阵也有密度矩阵（Dense Matrix）、稀疏矩阵（Sparse Matrix）两种存储方法
 */
class MatricesSuite extends SparkFunSuite {
  /**局部矩阵使用整型行列索引和浮点(double)数值,存储在单机上**/
  test("dense matrix construction") {//构建密集矩阵
    val m = 3
    val n = 2
    val values = Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
    //会被存储为一维数组[1.0, 3.0, 5.0, 2.0, 4.0, 6.0] ,矩阵的大小是(3行, 2列)
    val mat = Matrices.dense(m, n, values).asInstanceOf[DenseMatrix]
    assert(mat.numRows === m)
    assert(mat.numCols === n)
    assert(mat.values.eq(values), "should not copy data")
  }

  test("dense matrix construction with wrong dimension") {//构建错误维数的稠密矩阵
    intercept[RuntimeException] {
      Matrices.dense(3, 2, Array(0.0, 1.0, 2.0))
    }
  }

  test("sparse matrix construction") {//构造稀疏矩阵
    //稀疏矩阵(Sparse Matrix)
    val m = 3 //行数
    val n = 4 //列数
    //实际存储值
    val values = Array(1.0, 2.0, 4.0, 5.0)
    //列起始位置索引：colPointers=[0, 2, 2, 4, 4]
    val colPtrs = Array(0, 2, 2, 4, 4)
     //矩阵元素对应的行索引
    val rowIndices = Array(1, 2, 1, 2)
   
    val mat = Matrices.sparse(m, n, colPtrs, rowIndices, values).asInstanceOf[SparseMatrix]
    assert(mat.numRows === m)
    assert(mat.numCols === n)
    assert(mat.values.eq(values), "should not copy data")//不应该复制数据
    assert(mat.colPtrs.eq(colPtrs), "should not copy data")
    assert(mat.rowIndices.eq(rowIndices), "should not copy data")

    val entries: Array[(Int, Int, Double)] = Array((2, 2, 3.0), (1, 0, 1.0), (2, 0, 2.0),
        (1, 2, 2.0), (2, 2, 2.0), (1, 2, 2.0), (0, 0, 0.0))

    val mat2 = SparseMatrix.fromCOO(m, n, entries)
    assert(mat.toBreeze === mat2.toBreeze)
    assert(mat2.values.length == 4)
  }
  //构造用错误数元素的稀疏矩阵
  test("sparse matrix construction with wrong number of elements") {
    intercept[IllegalArgumentException] {
      Matrices.sparse(3, 2, Array(0, 1), Array(1, 2, 1), Array(0.0, 1.0, 2.0))
    }

    intercept[IllegalArgumentException] {
      Matrices.sparse(3, 2, Array(0, 1, 2), Array(1, 2), Array(0.0, 1.0, 2.0))
    }
  }

  test("equals") {//相等
    val dm1 = Matrices.dense(2, 2, Array(0.0, 1.0, 2.0, 3.0))
    assert(dm1 === dm1)
    assert(dm1 !== dm1.transpose)//转置阵

    val dm2 = Matrices.dense(2, 2, Array(0.0, 2.0, 1.0, 3.0))
    assert(dm1 === dm2.transpose)

    val sm1 = dm1.asInstanceOf[DenseMatrix].toSparse
    assert(sm1 === sm1)
    assert(sm1 === dm1)
    assert(sm1 !== sm1.transpose)

    val sm2 = dm2.asInstanceOf[DenseMatrix].toSparse
    assert(sm1 === sm2.transpose)
    assert(sm1 === dm2.transpose)
  }

  test("matrix copies are deep copies") {//矩阵复制是深刻复制
    val m = 3
    val n = 2

    val denseMat = Matrices.dense(m, n, Array(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
    val denseCopy = denseMat.copy

    assert(!denseMat.toArray.eq(denseCopy.toArray))

    val values = Array(1.0, 2.0, 4.0, 5.0)
    val colPtrs = Array(0, 2, 4)
    val rowIndices = Array(1, 2, 1, 2)
    val sparseMat = Matrices.sparse(m, n, colPtrs, rowIndices, values)
    val sparseCopy = sparseMat.copy

    assert(!sparseMat.toArray.eq(sparseCopy.toArray))
  }

  test("matrix indexing and updating") {//矩阵索引和更新
    val m = 3//行数
    val n = 2//列数
    
    val allValues = Array(0.0, 1.0, 2.0, 3.0, 4.0, 0.0)

    val denseMat = new DenseMatrix(m, n, allValues)

    assert(denseMat(0, 1) === 3.0)
    assert(denseMat(0, 1) === denseMat.values(3))
    assert(denseMat(0, 1) === denseMat(3))
    assert(denseMat(0, 0) === 0.0)

    denseMat.update(0, 0, 10.0)
    assert(denseMat(0, 0) === 10.0)
    assert(denseMat.values(0) === 10.0)
    //实际存储值
    val sparseValues = Array(1.0, 2.0, 3.0, 4.0)
    //列起始位置索引
    val colPtrs = Array(0, 2, 4)
    //矩阵元素对应的行索引
    val rowIndices = Array(1, 2, 0, 1)
    //稀疏矩阵(Sparse Matrix)
    val sparseMat = new SparseMatrix(m, n, colPtrs, rowIndices, sparseValues)
    assert(sparseMat(0, 1) === 3.0)
    assert(sparseMat(0, 1) === sparseMat.values(2))
    assert(sparseMat(0, 0) === 0.0)

    intercept[NoSuchElementException] {
      sparseMat.update(0, 0, 10.0)
    }

    sparseMat.update(0, 1, 10.0)
    assert(sparseMat(0, 1) === 10.0)
    assert(sparseMat.values(2) === 10.0)
  }

  test("toSparse, toDense") {//转换稀疏,转换密集
    val m = 3
    val n = 2
    val values = Array(1.0, 2.0, 4.0, 5.0)
    val allValues = Array(1.0, 2.0, 0.0, 0.0, 4.0, 5.0)
    val colPtrs = Array(0, 2, 4)
    val rowIndices = Array(0, 1, 1, 2)

    val spMat1 = new SparseMatrix(m, n, colPtrs, rowIndices, values)
    val deMat1 = new DenseMatrix(m, n, allValues)

    val spMat2 = deMat1.toSparse
    val deMat2 = spMat1.toDense

    assert(spMat1.toBreeze === spMat2.toBreeze)
    assert(deMat1.toBreeze === deMat2.toBreeze)
  }

  test("map, update") {//Mpa,更新
    val m = 3
    val n = 2
    val values = Array(1.0, 2.0, 4.0, 5.0)//值
    val allValues = Array(1.0, 2.0, 0.0, 0.0, 4.0, 5.0)//所有值
    val colPtrs = Array(0, 2, 4)//按列从小到大顺序对target_vertex进行排序后生成的序列数组
    val rowIndices = Array(0, 1, 1, 2)//行索引

    val spMat1 = new SparseMatrix(m, n, colPtrs, rowIndices, values)
    val deMat1 = new DenseMatrix(m, n, allValues)
    val deMat2 = deMat1.map(_ * 2)
    val spMat2 = spMat1.map(_ * 2)
    deMat1.update(_ * 2)
    spMat1.update(_ * 2)

    assert(spMat1.toArray === spMat2.toArray)
    assert(deMat1.toArray === deMat2.toArray)
  }

  test("transpose") {//转置
    val dA =
      new DenseMatrix(4, 3, Array(0.0, 1.0, 0.0, 0.0, 2.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 3.0))
    val sA = new SparseMatrix(4, 3, Array(0, 1, 3, 4), Array(1, 0, 2, 3), Array(1.0, 2.0, 1.0, 3.0))

    val dAT = dA.transpose.asInstanceOf[DenseMatrix]
    val sAT = sA.transpose.asInstanceOf[SparseMatrix]
    val dATexpected =
      new DenseMatrix(3, 4, Array(0.0, 2.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 3.0))
    val sATexpected =
      new SparseMatrix(3, 4, Array(0, 1, 2, 3, 4), Array(1, 0, 1, 2), Array(2.0, 1.0, 1.0, 3.0))

    assert(dAT.toBreeze === dATexpected.toBreeze)
    assert(sAT.toBreeze === sATexpected.toBreeze)
    assert(dA(1, 0) === dAT(0, 1))
    assert(dA(2, 1) === dAT(1, 2))
    assert(sA(1, 0) === sAT(0, 1))
    assert(sA(2, 1) === sAT(1, 2))

    assert(!dA.toArray.eq(dAT.toArray), "has to have a new array")
    assert(dA.values.eq(dAT.transpose.asInstanceOf[DenseMatrix].values), "should not copy array")

    assert(dAT.toSparse.toBreeze === sATexpected.toBreeze)
    assert(sAT.toDense.toBreeze === dATexpected.toBreeze)
  }

  test("foreachActive") {//每一个活动
    val m = 3
    val n = 2
    val values = Array(1.0, 2.0, 4.0, 5.0)
    val allValues = Array(1.0, 2.0, 0.0, 0.0, 4.0, 5.0)
    val colPtrs = Array(0, 2, 4)
    val rowIndices = Array(0, 1, 1, 2)

    val sp = new SparseMatrix(m, n, colPtrs, rowIndices, values)
    val dn = new DenseMatrix(m, n, allValues)

    val dnMap = MutableMap[(Int, Int), Double]()
    dn.foreachActive { (i, j, value) =>
      dnMap.put((i, j), value)
    }
    assert(dnMap.size === 6)
    assert(dnMap(0, 0) === 1.0)
    assert(dnMap(1, 0) === 2.0)
    assert(dnMap(2, 0) === 0.0)
    assert(dnMap(0, 1) === 0.0)
    assert(dnMap(1, 1) === 4.0)
    assert(dnMap(2, 1) === 5.0)

    val spMap = MutableMap[(Int, Int), Double]()
    sp.foreachActive { (i, j, value) =>
      spMap.put((i, j), value)
    }
    assert(spMap.size === 4)
    assert(spMap(0, 0) === 1.0)
    assert(spMap(1, 0) === 2.0)
    assert(spMap(1, 1) === 4.0)
    assert(spMap(2, 1) === 5.0)
  }

  test("horzcat, vertcat, eye, speye") {
    val m = 3
    val n = 2
    val values = Array(1.0, 2.0, 4.0, 5.0)
    val allValues = Array(1.0, 2.0, 0.0, 0.0, 4.0, 5.0)
    val colPtrs = Array(0, 2, 4)
    val rowIndices = Array(0, 1, 1, 2)
    // transposed versions 转换的版本
    val allValuesT = Array(1.0, 0.0, 2.0, 4.0, 0.0, 5.0)
    val colPtrsT = Array(0, 1, 3, 4)
    val rowIndicesT = Array(0, 0, 1, 1)

    val spMat1 = new SparseMatrix(m, n, colPtrs, rowIndices, values)
    val deMat1 = new DenseMatrix(m, n, allValues)
    val spMat1T = new SparseMatrix(n, m, colPtrsT, rowIndicesT, values)
    val deMat1T = new DenseMatrix(n, m, allValuesT)

    // should equal spMat1 & deMat1 respectively
    //应该等于spmat1和demat1分别
    val spMat1TT = spMat1T.transpose
    val deMat1TT = deMat1T.transpose

    val deMat2 = Matrices.eye(3)
    val spMat2 = Matrices.speye(3)
    val deMat3 = Matrices.eye(2)
    val spMat3 = Matrices.speye(2)

    val spHorz = Matrices.horzcat(Array(spMat1, spMat2))
    val spHorz2 = Matrices.horzcat(Array(spMat1, deMat2))
    val spHorz3 = Matrices.horzcat(Array(deMat1, spMat2))
    val deHorz1 = Matrices.horzcat(Array(deMat1, deMat2))
    val deHorz2 = Matrices.horzcat(Array[Matrix]())

    assert(deHorz1.numRows === 3)
    assert(spHorz2.numRows === 3)
    assert(spHorz3.numRows === 3)
    assert(spHorz.numRows === 3)
    assert(deHorz1.numCols === 5)
    assert(spHorz2.numCols === 5)
    assert(spHorz3.numCols === 5)
    assert(spHorz.numCols === 5)
    assert(deHorz2.numRows === 0)
    assert(deHorz2.numCols === 0)
    assert(deHorz2.toArray.length === 0)

    assert(deHorz1 ~== spHorz2.asInstanceOf[SparseMatrix].toDense absTol 1e-15)
    assert(spHorz2 ~== spHorz3 absTol 1e-15)
    assert(spHorz(0, 0) === 1.0)
    assert(spHorz(2, 1) === 5.0)
    assert(spHorz(0, 2) === 1.0)
    assert(spHorz(1, 2) === 0.0)
    assert(spHorz(1, 3) === 1.0)
    assert(spHorz(2, 4) === 1.0)
    assert(spHorz(1, 4) === 0.0)
    assert(deHorz1(0, 0) === 1.0)
    assert(deHorz1(2, 1) === 5.0)
    assert(deHorz1(0, 2) === 1.0)
    assert(deHorz1(1, 2) == 0.0)
    assert(deHorz1(1, 3) === 1.0)
    assert(deHorz1(2, 4) === 1.0)
    assert(deHorz1(1, 4) === 0.0)

    // containing transposed matrices
    //包含转置矩阵
    val spHorzT = Matrices.horzcat(Array(spMat1TT, spMat2))
    val spHorz2T = Matrices.horzcat(Array(spMat1TT, deMat2))
    val spHorz3T = Matrices.horzcat(Array(deMat1TT, spMat2))
    val deHorz1T = Matrices.horzcat(Array(deMat1TT, deMat2))

    assert(deHorz1T ~== deHorz1 absTol 1e-15)
    assert(spHorzT ~== spHorz absTol 1e-15)
    assert(spHorz2T ~== spHorz2 absTol 1e-15)
    assert(spHorz3T ~== spHorz3 absTol 1e-15)

    intercept[IllegalArgumentException] {
      Matrices.horzcat(Array(spMat1, spMat3))
    }

    intercept[IllegalArgumentException] {
      Matrices.horzcat(Array(deMat1, spMat3))
    }

    val spVert = Matrices.vertcat(Array(spMat1, spMat3))
    val deVert1 = Matrices.vertcat(Array(deMat1, deMat3))
    val spVert2 = Matrices.vertcat(Array(spMat1, deMat3))
    val spVert3 = Matrices.vertcat(Array(deMat1, spMat3))
    val deVert2 = Matrices.vertcat(Array[Matrix]())

    assert(deVert1.numRows === 5)
    assert(spVert2.numRows === 5)
    assert(spVert3.numRows === 5)
    assert(spVert.numRows === 5)
    assert(deVert1.numCols === 2)
    assert(spVert2.numCols === 2)
    assert(spVert3.numCols === 2)
    assert(spVert.numCols === 2)
    assert(deVert2.numRows === 0)
    assert(deVert2.numCols === 0)
    assert(deVert2.toArray.length === 0)

    assert(deVert1 ~== spVert2.asInstanceOf[SparseMatrix].toDense absTol 1e-15)
    assert(spVert2 ~== spVert3 absTol 1e-15)
    assert(spVert(0, 0) === 1.0)
    assert(spVert(2, 1) === 5.0)
    assert(spVert(3, 0) === 1.0)
    assert(spVert(3, 1) === 0.0)
    assert(spVert(4, 1) === 1.0)
    assert(deVert1(0, 0) === 1.0)
    assert(deVert1(2, 1) === 5.0)
    assert(deVert1(3, 0) === 1.0)
    assert(deVert1(3, 1) === 0.0)
    assert(deVert1(4, 1) === 1.0)

    // containing transposed matrices
    val spVertT = Matrices.vertcat(Array(spMat1TT, spMat3))
    val deVert1T = Matrices.vertcat(Array(deMat1TT, deMat3))
    val spVert2T = Matrices.vertcat(Array(spMat1TT, deMat3))
    val spVert3T = Matrices.vertcat(Array(deMat1TT, spMat3))

    assert(deVert1T ~== deVert1 absTol 1e-15)
    assert(spVertT ~== spVert absTol 1e-15)
    assert(spVert2T ~== spVert2 absTol 1e-15)
    assert(spVert3T ~== spVert3 absTol 1e-15)

    intercept[IllegalArgumentException] {
      Matrices.vertcat(Array(spMat1, spMat2))
    }

    intercept[IllegalArgumentException] {
      Matrices.vertcat(Array(deMat1, spMat2))
    }
  }

  test("zeros") {//是生成一个零矩阵,可以相当于一个空矩阵
    val mat = Matrices.zeros(2, 3).asInstanceOf[DenseMatrix]
    assert(mat.numRows === 2)
    assert(mat.numCols === 3)
    assert(mat.values.forall(_ == 0.0))
  }

  test("ones") {//是生成一个单位矩阵
    val mat = Matrices.ones(2, 3).asInstanceOf[DenseMatrix]
    assert(mat.numRows === 2)
    assert(mat.numCols === 3)
    assert(mat.values.forall(_ == 1.0))
  }

  test("eye") {//是生成一个对角矩阵
    val mat = Matrices.eye(2).asInstanceOf[DenseMatrix]
    assert(mat.numCols === 2)
    assert(mat.numCols === 2)
    assert(mat.values.toSeq === Seq(1.0, 0.0, 0.0, 1.0))
  }

  test("rand") {//函数产生由在(0, 1)之间均匀分布的随机数组成的数组
    val rng = mock[Random]
    when(rng.nextDouble()).thenReturn(1.0, 2.0, 3.0, 4.0)
    val mat = Matrices.rand(2, 2, rng).asInstanceOf[DenseMatrix]
    assert(mat.numRows === 2)
    assert(mat.numCols === 2)
    assert(mat.values.toSeq === Seq(1.0, 2.0, 3.0, 4.0))
  }

  test("randn") {//产生正态分布的随机数或矩阵的函数
    val rng = mock[Random]
    when(rng.nextGaussian()).thenReturn(1.0, 2.0, 3.0, 4.0)
    val mat = Matrices.randn(2, 2, rng).asInstanceOf[DenseMatrix]
    assert(mat.numRows === 2)
    assert(mat.numCols === 2)
    assert(mat.values.toSeq === Seq(1.0, 2.0, 3.0, 4.0))
  }

  test("diag") {//以向量v的元素作为矩阵X的第k条对角线元素
    val mat = Matrices.diag(Vectors.dense(1.0, 2.0)).asInstanceOf[DenseMatrix]
    assert(mat.numRows === 2)
    assert(mat.numCols === 2)
    assert(mat.values.toSeq === Seq(1.0, 0.0, 0.0, 2.0))
  }

  test("sprand") {//生成与S具有相同稀疏结构的均匀分布随机矩阵
    val rng = mock[Random]
    when(rng.nextInt(4)).thenReturn(0, 1, 1, 3, 2, 2, 0, 1, 3, 0)
    when(rng.nextDouble()).thenReturn(1.0, 2.0, 3.0, 4.0, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
    val mat = SparseMatrix.sprand(4, 4, 0.25, rng)
    assert(mat.numRows === 4)
    assert(mat.numCols === 4)
    assert(mat.rowIndices.toSeq === Seq(3, 0, 2, 1))
    assert(mat.values.toSeq === Seq(1.0, 2.0, 3.0, 4.0))
    val mat2 = SparseMatrix.sprand(2, 3, 1.0, rng)
    assert(mat2.rowIndices.toSeq === Seq(0, 1, 0, 1, 0, 1))
    assert(mat2.colPtrs.toSeq === Seq(0, 2, 4, 6))
  }

  test("sprandn") {//成与S具有相同稀疏结构的正态分布随机矩阵
    val rng = mock[Random]
    when(rng.nextInt(4)).thenReturn(0, 1, 1, 3, 2, 2, 0, 1, 3, 0)
    when(rng.nextGaussian()).thenReturn(1.0, 2.0, 3.0, 4.0)
    val mat = SparseMatrix.sprandn(4, 4, 0.25, rng)
    assert(mat.numRows === 4)
    assert(mat.numCols === 4)
    assert(mat.rowIndices.toSeq === Seq(3, 0, 2, 1))
    assert(mat.values.toSeq === Seq(1.0, 2.0, 3.0, 4.0))
  }

  test("MatrixUDT") {
    val dm1 = new DenseMatrix(2, 2, Array(0.9, 1.2, 2.3, 9.8))
    val dm2 = new DenseMatrix(3, 2, Array(0.0, 1.21, 2.3, 9.8, 9.0, 0.0))
    val dm3 = new DenseMatrix(0, 0, Array())
    val sm1 = dm1.toSparse
    val sm2 = dm2.toSparse
    val sm3 = dm3.toSparse
    val mUDT = new MatrixUDT()
    Seq(dm1, dm2, dm3, sm1, sm2, sm3).foreach {
        mat => assert(mat.toArray === mUDT.deserialize(mUDT.serialize(mat)).toArray)
    }
    assert(mUDT.typeName == "matrix")
    assert(mUDT.simpleString == "matrix")
  }

  test("toString") {
    val empty = Matrices.ones(0, 0)
    empty.toString(0, 0)

    val mat = Matrices.rand(5, 10, new Random())
    mat.toString(-1, -5)
    mat.toString(0, 0)
    mat.toString(Int.MinValue, Int.MinValue)
    mat.toString(Int.MaxValue, Int.MaxValue)
    var lines = mat.toString(6, 50).lines.toArray
    assert(lines.size == 5 && lines.forall(_.size <= 50))

    lines = mat.toString(5, 100).lines.toArray
    assert(lines.size == 5 && lines.forall(_.size <= 100))
  }

  test("numNonzeros and numActives") {
    val dm1 = Matrices.dense(3, 2, Array(0, 0, -1, 1, 0, 1))
    assert(dm1.numNonzeros === 3)
    assert(dm1.numActives === 6)

    val sm1 = Matrices.sparse(3, 2, Array(0, 2, 3), Array(0, 2, 1), Array(0.0, -1.2, 0.0))
    assert(sm1.numNonzeros === 1)
    assert(sm1.numActives === 3)
  }
}
