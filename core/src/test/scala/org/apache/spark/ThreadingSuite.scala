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

package org.apache.spark

import java.util.concurrent.{TimeUnit, Semaphore}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import org.apache.spark.scheduler._

/**
 * Holds state shared across task threads in some ThreadingSuite tests.
 * 持有共享任务线程的状态
 */
object ThreadingSuiteState {
  val runningThreads = new AtomicInteger
  val failed = new AtomicBoolean

  def clear() {
    runningThreads.set(0)
    failed.set(false)
  }
}

class ThreadingSuite extends SparkFunSuite with LocalSparkContext with Logging {

  test("accessing SparkContext form a different thread") {//不同线程访问sparkcontext
    sc = new SparkContext("local", "test")
    val nums = sc.parallelize(1 to 10, 2)
    val sem = new Semaphore(0)//是负责协调各个线程, 以保证它们能够正确、合理的使用公共资源
    //volatile允许线程访问共享变量，为了确保共享变量能被准确和一致的更新，线程应该确保通过排他锁单独获得这个变量
    //在某些情况下比锁更加方便,如果一个字段被声明成volatile,java线程内存模型确保所有线程看到这个变量的值是一致的
    @volatile var answer1: Int = 0
    @volatile var answer2: Int = 0
    new Thread {
      override def run() {
        answer1 = nums.reduce(_ + _)
        answer2 = nums.first()    // This will run "locally" in the current thread
        sem.release()//释放一个许可
      }
    }.start()
    sem.acquire()//获取一个许可,如果没有就等待
    assert(answer1 === 55)
    assert(answer2 === 1)
  }

  test("accessing SparkContext form multiple threads") {//多线程访问SparkContext
    sc = new SparkContext("local", "test")
    val nums = sc.parallelize(1 to 10, 2)
    val sem = new Semaphore(0)
    //volatile允许线程访问共享变量，为了确保共享变量能被准确和一致的更新，线程应该确保通过排他锁单独获得这个变量
    //在某些情况下比锁更加方便,如果一个字段被声明成volatile,java线程内存模型确保所有线程看到这个变量的值是一致的
    @volatile var ok = true
    for (i <- 0 until 10) {
      new Thread {
        override def run() {
          val answer1 = nums.reduce(_ + _)
          if (answer1 != 55) {
            printf("In thread %d: answer1 was %d\n", i, answer1)
            ok = false
          }
          val answer2 = nums.first()    // This will run "locally" in the current thread
          if (answer2 != 1) {
            printf("In thread %d: answer2 was %d\n", i, answer2)
            ok = false
          }
          sem.release()
        }
      }.start()
    }
    sem.acquire(10)
    if (!ok) {
      fail("One or more threads got the wrong answer from an RDD operation")
    }
  }

  test("accessing multi-threaded SparkContext form multiple threads") {//多线程嵌套多线访问SparkContext
    sc = new SparkContext("local[4]", "test")
    val nums = sc.parallelize(1 to 10, 2)
    val sem = new Semaphore(0)
    @volatile var ok = true
    for (i <- 0 until 10) {
      new Thread {
        override def run() {
          val answer1 = nums.reduce(_ + _)
          if (answer1 != 55) {
            printf("In thread %d: answer1 was %d\n", i, answer1)
            ok = false
          }
          val answer2 = nums.first()    // This will run "locally" in the current thread
          if (answer2 != 1) {
            printf("In thread %d: answer2 was %d\n", i, answer2)
            ok = false
          }
          sem.release()
        }
      }.start()
    }
    sem.acquire(10)
    if (!ok) {
      fail("One or more threads got the wrong answer from an RDD operation")
    }
  }

  test("parallel job execution") {//并行job执行
    // This test launches two jobs with two threads each on a 4-core local cluster. Each thread
    // waits until there are 4 threads running at once, to test that both jobs have been launched.
    sc = new SparkContext("local[4]", "test")
    val nums = sc.parallelize(1 to 2, 2)
    val sem = new Semaphore(0)
    ThreadingSuiteState.clear()
    var throwable: Option[Throwable] = None //Option[T]是容器对于给定的类型的零个或一个元件
    for (i <- 0 until 2) {
      new Thread {
        override def run() {
          try {
            val ans = nums.map(number => {
              val running = ThreadingSuiteState.runningThreads
              running.getAndIncrement()
              val time = System.currentTimeMillis()
              while (running.get() != 4 && System.currentTimeMillis() < time + 1000) {
                Thread.sleep(100)
              }
              if (running.get() != 4) {
                ThreadingSuiteState.failed.set(true)
              }
              number
            }).collect()
            assert(ans.toList === List(1, 2))
          } catch {
            case t: Throwable =>
              throwable = Some(t) //存一个元素
          } finally {
            sem.release()
          }
        }
      }.start()
    }
    sem.acquire(2)
    throwable.foreach { t => throw improveStackTrace(t) }
    if (ThreadingSuiteState.failed.get()) {
      logError("Waited 1 second without seeing runningThreads = 4 (it was " +
                ThreadingSuiteState.runningThreads.get() + "); failing test")
      fail("One or more threads didn't see runningThreads = 4")
    }
  }

  test("set local properties in different thread") {//在不同的线程中设置本地属性
    sc = new SparkContext("local", "test")
    val sem = new Semaphore(0)
    var throwable: Option[Throwable] = None//Option[T]是容器对于给定的类型的零个或一个元件
    val threads = (1 to 5).map { i =>
      new Thread() {
        override def run() {
          try {
            sc.setLocalProperty("test", i.toString)
            assert(sc.getLocalProperty("test") === i.toString)
          } catch {
            case t: Throwable =>
              throwable = Some(t)
          } finally {
            sem.release()
          }
        }
      }
    }

    threads.foreach(_.start())

    sem.acquire(5)
    throwable.foreach { t => throw improveStackTrace(t) }
    assert(sc.getLocalProperty("test") === null)
  }

  test("set and get local properties in parent-children thread") {//在父线程中设置和获取本地属性
    sc = new SparkContext("local", "test")
    sc.setLocalProperty("test", "parent")
    val sem = new Semaphore(0)
    var throwable: Option[Throwable] = None
    val threads = (1 to 5).map { i =>
      new Thread() {
        override def run() {
          try {
            assert(sc.getLocalProperty("test") === "parent")
            sc.setLocalProperty("test", i.toString)
            assert(sc.getLocalProperty("test") === i.toString)
          } catch {
            case t: Throwable =>
              throwable = Some(t)
          } finally {
            sem.release()
          }
        }
      }
    }

    threads.foreach(_.start())

    sem.acquire(5)
    throwable.foreach { t => throw improveStackTrace(t) }
    assert(sc.getLocalProperty("test") === "parent")
    assert(sc.getLocalProperty("Foo") === null)
  }
    //父本地属性的突变不影响子
  test("mutation in parent local property does not affect child (SPARK-10563)") {
    sc = new SparkContext("local", "test")
    sc.conf.set("spark.localProperties.clone", "true")
    val originalTestValue: String = "original-value"
    var threadTestValue: String = null
    sc.setLocalProperty("test", originalTestValue)
    var throwable: Option[Throwable] = None
    val thread = new Thread {
      override def run(): Unit = {
        try {
          threadTestValue = sc.getLocalProperty("test")
        } catch {
          case t: Throwable =>
            throwable = Some(t)
        }
      }
    }
    sc.setLocalProperty("test", "this-should-not-be-inherited")
    thread.start()
    thread.join()
    throwable.foreach { t => throw improveStackTrace(t) }
    assert(threadTestValue === originalTestValue)
  }

  /**
   * Improve the stack trace of an error thrown from within a thread.
   * Otherwise it's difficult to tell which line in the test the error came from.
   */
  private def improveStackTrace(t: Throwable): Throwable = {
    t.setStackTrace(t.getStackTrace ++ Thread.currentThread.getStackTrace)
    t
  }

}
