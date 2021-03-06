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

package org.apache.spark.streaming

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.scalatest.concurrent.Timeouts
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.SpanSugar._

import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.storage.StreamBlockId
import org.apache.spark.streaming.receiver._
import org.apache.spark.streaming.receiver.WriteAheadLogBasedBlockHandler._
import org.apache.spark.util.Utils

/** 
 *  用于测试网络接收行为集
 *  Testsuite for testing the network receiver behavior 
 *  */
class ReceiverSuite extends TestSuiteBase with Timeouts with Serializable {

  test("receiver life cycle") {//接收生命周期

    val receiver = new FakeReceiver
    val executor = new FakeReceiverSupervisor(receiver)
    val executorStarted = new Semaphore(0)

    assert(executor.isAllEmpty)

    // Thread that runs the executor
    //运行执行器的线程
    val executingThread = new Thread() {
      override def run() {
        executor.start()
        executorStarted.release(1)
        executor.awaitTermination()
      }
    }

    // Start the receiver
    //开始接收
    executingThread.start()

    // Verify that the receiver
    //验证接收器
    intercept[Exception] {
      failAfter(200 millis) {
        executingThread.join()
      }
    }

    // Ensure executor is started
    //确保执行开始
    executorStarted.acquire()

    // Verify that receiver was started
    //确认接收器已启动
    assert(receiver.onStartCalled)
    assert(executor.isReceiverStarted)
    assert(receiver.isStarted)
    assert(!receiver.isStopped())
    assert(receiver.otherThread.isAlive)
    eventually(timeout(100 millis), interval(10 millis)) {
      assert(receiver.receiving)
    }

    // Verify whether the data stored by the receiver was sent to the executor
    //验证由接收器存储的数据是否被发送到执行器
    val byteBuffer = ByteBuffer.allocate(100)
    val arrayBuffer = new ArrayBuffer[Int]()
    val iterator = arrayBuffer.iterator
    receiver.store(1)//存储单条小数据
    receiver.store(byteBuffer)//ByteBuffer形式的块数据
    receiver.store(arrayBuffer)//数组块形式的块数据
    receiver.store(iterator)//iterator形式的块数据
    assert(executor.singles.size === 1)
    assert(executor.singles.head === 1)
    assert(executor.byteBuffers.size === 1)
    assert(executor.byteBuffers.head.eq(byteBuffer))
    assert(executor.iterators.size === 1)
    assert(executor.iterators.head.eq(iterator))
    assert(executor.arrayBuffers.size === 1)
    assert(executor.arrayBuffers.head.eq(arrayBuffer))

    // Verify whether the exceptions reported by the receiver was sent to the executor
    //是否将接收到的异常报告发送给执行者
    val exception = new Exception
    receiver.reportError("Error", exception)
    assert(executor.errors.size === 1)
    assert(executor.errors.head.eq(exception))

    // Verify restarting actually stops and starts the receiver
    //验证重新启动实际上停止并启动接收器
    receiver.restart("restarting", null, 100)
    eventually(timeout(50 millis), interval(10 millis)) {
      // receiver will be stopped async
      //接收器将停止异步
      assert(receiver.isStopped)
      assert(receiver.onStopCalled)
    }
    eventually(timeout(1000 millis), interval(100 millis)) {
      // receiver will be started async
      //接收器将启动异步
      assert(receiver.onStartCalled)
      assert(executor.isReceiverStarted)
      assert(receiver.isStarted)
      assert(!receiver.isStopped)
      assert(receiver.receiving)
    }

    // Verify that stopping actually stops the thread
    //验证停止实际上停止线程
    failAfter(100 millis) {
      receiver.stop("test")
      assert(receiver.isStopped)
      assert(!receiver.otherThread.isAlive)

      // The thread that started the executor should complete      
      // as stop() stops everything
      //开始执行者的线程应该完成
      executingThread.join()
    }
  }

  ignore("block generator throttling") {
    val blockGeneratorListener = new FakeBlockGeneratorListener
    val blockIntervalMs = 100
    val maxRate = 1001
    //Spark Streaming接收器将接收数据合并成数据块并存储在Spark里的时间间隔，毫秒
    val conf = new SparkConf().set("spark.streaming.blockInterval", s"${blockIntervalMs}ms").
      set("spark.streaming.receiver.maxRate", maxRate.toString)
    val blockGenerator = new BlockGenerator(blockGeneratorListener, 1, conf)
    val expectedBlocks = 20
    val waitTime = expectedBlocks * blockIntervalMs
    val expectedMessages = maxRate * waitTime / 1000
    val expectedMessagesPerBlock = maxRate * blockIntervalMs / 1000
    val generatedData = new ArrayBuffer[Int]

    // Generate blocks 生成块
    val startTime = System.currentTimeMillis()
    blockGenerator.start()
    var count = 0
    while(System.currentTimeMillis - startTime < waitTime) {
      blockGenerator.addData(count)
      generatedData += count
      count += 1
    }
    blockGenerator.stop()

    val recordedBlocks = blockGeneratorListener.arrayBuffers
    val recordedData = recordedBlocks.flatten
    assert(blockGeneratorListener.arrayBuffers.size > 0, "No blocks received")
    assert(recordedData.toSet === generatedData.toSet, "Received data not same")

    // recordedData size should be close to the expected rate; use an error margin proportional to
    // the value, so that rate changes don't cause a brittle test
    val minExpectedMessages = expectedMessages - 0.05 * expectedMessages
    val maxExpectedMessages = expectedMessages + 0.05 * expectedMessages
    val numMessages = recordedData.size
    assert(
      numMessages >= minExpectedMessages && numMessages <= maxExpectedMessages,
      s"#records received = $numMessages, not between $minExpectedMessages and $maxExpectedMessages"
    )

    // XXX Checking every block would require an even distribution of messages across blocks,
    // which throttling code does not control. Therefore, test against the average.
    val minExpectedMessagesPerBlock = expectedMessagesPerBlock - 0.05 * expectedMessagesPerBlock
    val maxExpectedMessagesPerBlock = expectedMessagesPerBlock + 0.05 * expectedMessagesPerBlock
    val receivedBlockSizes = recordedBlocks.map { _.size }.mkString(",")

    // the first and last block may be incomplete, so we slice them out
    //第一个和最后一个块可能是不完整的，所以我们把它们切片
    val validBlocks = recordedBlocks.drop(1).dropRight(1)
    val averageBlockSize = validBlocks.map(block => block.size).sum / validBlocks.size

    assert(
      averageBlockSize >= minExpectedMessagesPerBlock &&
        averageBlockSize <= maxExpectedMessagesPerBlock,
      s"# records in received blocks = [$receivedBlockSizes], not between " +
        s"$minExpectedMessagesPerBlock and $maxExpectedMessagesPerBlock, on average"
    )
  }

  /**
   * Test whether write ahead logs are generated by received,
   * 测试是否由接收生成写前日志,并自动清理
   * and automatically cleaned up. The clean up must be aware of the
   * remember duration of the input streams. E.g., input streams on which window()
   * has been applied must remember the data for longer, and hence corresponding
   * WALs should be cleaned later.
   * 清理必须知道输入流的记住持续时间
   */
  test("write ahead log - generating and cleaning") {
    val sparkConf = new SparkConf()
      .setMaster("local[4]")  // must be at least 3 as we are going to start 2 receivers
      .setAppName(framework)//必须至少3,因为我们要运行2个接收器
      .set("spark.ui.enabled", "true")
      .set("spark.streaming.receiver.writeAheadLog.enable", "true")
      .set("spark.streaming.receiver.writeAheadLog.rollingIntervalSecs", "1")
    val batchDuration = Milliseconds(500)
    val tempDirectory = Utils.createTempDir()
    val logDirectory1 = new File(checkpointDirToLogDir(tempDirectory.getAbsolutePath, 0))
    val logDirectory2 = new File(checkpointDirToLogDir(tempDirectory.getAbsolutePath, 1))
    val allLogFiles1 = new mutable.HashSet[String]()
    val allLogFiles2 = new mutable.HashSet[String]()
    logInfo("Temp checkpoint directory = " + tempDirectory)

    def getBothCurrentLogFiles(): (Seq[String], Seq[String]) = {
      (getCurrentLogFiles(logDirectory1), getCurrentLogFiles(logDirectory2))
    }

    def getCurrentLogFiles(logDirectory: File): Seq[String] = {
      try {
        if (logDirectory.exists()) {
          logDirectory1.listFiles().filter { _.getName.startsWith("log") }.map { _.toString }
        } else {
          Seq.empty
        }
      } catch {
        case e: Exception =>
          Seq.empty
      }
    }

    def printLogFiles(message: String, files: Seq[String]) {
      logInfo(s"$message (${files.size} files):\n" + files.mkString("\n"))
    }

    withStreamingContext(new StreamingContext(sparkConf, batchDuration)) { ssc =>
      val receiver1 = new FakeReceiver(sendData = true)
      val receiver2 = new FakeReceiver(sendData = true)
      val receiverStream1 = ssc.receiverStream(receiver1)
      val receiverStream2 = ssc.receiverStream(receiver2)
      receiverStream1.register()
      receiverStream2.window(batchDuration * 6).register()  // 3 second window
      ssc.checkpoint(tempDirectory.getAbsolutePath())
      ssc.start()

      // Run until sufficient WAL files have been generated and
      // the first WAL files has been deleted
      //运行直到有足够的WAL文件生成和第一个WAL文件已被删除
      eventually(timeout(20 seconds), interval(batchDuration.milliseconds millis)) {
        val (logFiles1, logFiles2) = getBothCurrentLogFiles()
        allLogFiles1 ++= logFiles1
        allLogFiles2 ++= logFiles2
        if (allLogFiles1.size > 0) {
          assert(!logFiles1.contains(allLogFiles1.toSeq.sorted.head))
        }
        if (allLogFiles2.size > 0) {
          assert(!logFiles2.contains(allLogFiles2.toSeq.sorted.head))
        }
        assert(allLogFiles1.size >= 7)
        assert(allLogFiles2.size >= 7)
      }
      ssc.stop(stopSparkContext = true, stopGracefully = true)

      val sortedAllLogFiles1 = allLogFiles1.toSeq.sorted
      val sortedAllLogFiles2 = allLogFiles2.toSeq.sorted
      val (leftLogFiles1, leftLogFiles2) = getBothCurrentLogFiles()

      printLogFiles("Receiver 0: all", sortedAllLogFiles1)
      printLogFiles("Receiver 0: left", leftLogFiles1)
      printLogFiles("Receiver 1: all", sortedAllLogFiles2)
      printLogFiles("Receiver 1: left", leftLogFiles2)

      // Verify that necessary latest log files are not deleted
      //验证所需的最新日志文件没有被删除
      //   receiverStream1 needs to retain just the last batch = 1 log file
      //   receiverStream2 needs to retain 3 seconds (3-seconds window) = 3 log files
      assert(sortedAllLogFiles1.takeRight(1).forall(leftLogFiles1.contains))
      assert(sortedAllLogFiles2.takeRight(3).forall(leftLogFiles2.contains))
    }
  }

  /**
   * An implementation of NetworkReceiverExecutor used for testing a NetworkReceiver.
   * 用于测试的一个networkreceiverexecutor接收机的实现,而不是在blockmanager存储数据
   * Instead of storing the data in the BlockManager, it stores all the data in a local buffer
   * that can used for verifying that the data has been forwarded correctly.
   * ReceiverSupervisor 接收监听
   * 所有数据存储在一个本地缓冲区中,该数据用于验证数据是否已正确转发
   */
  class FakeReceiverSupervisor(receiver: FakeReceiver)
    extends ReceiverSupervisor(receiver, new SparkConf()) {
    val singles = new ArrayBuffer[Any]
    val byteBuffers = new ArrayBuffer[ByteBuffer]
    val iterators = new ArrayBuffer[Iterator[_]]
    val arrayBuffers = new ArrayBuffer[ArrayBuffer[_]]
    val errors = new ArrayBuffer[Throwable]

    /** Check if all data structures are clean */
    //检查是否所有的数据结构都是干净的
    def isAllEmpty: Boolean = {
      singles.isEmpty && byteBuffers.isEmpty && iterators.isEmpty &&
        arrayBuffers.isEmpty && errors.isEmpty
    }

    def pushSingle(data: Any) {
      singles += data
    }

    def pushBytes(
        bytes: ByteBuffer,
        optionalMetadata: Option[Any],
        optionalBlockId: Option[StreamBlockId]) {
      byteBuffers += bytes
    }

    def pushIterator(
        iterator: Iterator[_],
        optionalMetadata: Option[Any],
        optionalBlockId: Option[StreamBlockId]) {
      iterators += iterator
    }

    def pushArrayBuffer(
        arrayBuffer: ArrayBuffer[_],
        optionalMetadata: Option[Any],
        optionalBlockId: Option[StreamBlockId]) {
      arrayBuffers +=  arrayBuffer
    }

    def reportError(message: String, throwable: Throwable) {
      errors += throwable
    }

    override protected def onReceiverStart(): Boolean = true

    override def createBlockGenerator(
        blockGeneratorListener: BlockGeneratorListener): BlockGenerator = {
      null
    }
  }

  /**
   * An implementation of BlockGeneratorListener that is used to test the BlockGenerator.
   */
  class FakeBlockGeneratorListener(pushDelay: Long = 0) extends BlockGeneratorListener {
    // buffer of data received as ArrayBuffers
    //作为arraybuffers接收数据缓冲区
    val arrayBuffers = new ArrayBuffer[ArrayBuffer[Int]]
    val errors = new ArrayBuffer[Throwable]

    def onAddData(data: Any, metadata: Any) { }

    def onGenerateBlock(blockId: StreamBlockId) { }

    def onPushBlock(blockId: StreamBlockId, arrayBuffer: ArrayBuffer[_]) {
      val bufferOfInts = arrayBuffer.map(_.asInstanceOf[Int])
      arrayBuffers += bufferOfInts
      Thread.sleep(0)
    }

    def onError(message: String, throwable: Throwable) {
      errors += throwable
    }
  }
}

/**
 * An implementation of Receiver that is used for testing a receiver's life cycle.
 * 用于测试接收机生命周期的接收器的实现。
 */
class FakeReceiver(sendData: Boolean = false) extends Receiver[Int](StorageLevel.MEMORY_ONLY) {
  @volatile var otherThread: Thread = null
  @volatile var receiving = false
  @volatile var onStartCalled = false
  @volatile var onStopCalled = false

  def onStart() {
    otherThread = new Thread() {
      override def run() {
        receiving = true
        var count = 0
        while(!isStopped()) {
          if (sendData) {
            store(count)
            count += 1
          }
          Thread.sleep(10)
        }
      }
    }
    onStartCalled = true
    otherThread.start()
  }

  def onStop() {
    onStopCalled = true
    otherThread.join()
  }

  def reset() {
    receiving = false
    onStartCalled = false
    onStopCalled = false
  }
}

