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

package org.apache.spark.util

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean

import com.google.common.annotations.VisibleForTesting
import org.apache.spark.SparkContext

/**
 * Asynchronously passes events to registered listeners.
 * 内部维护了一个queue,事件都会先放到这个queue,然后通过一个线程来让Listener处理Event
 * Until `start()` is called, all posted events are only buffered. Only after this listener bus
 * has started will events be actually propagated to all attached listeners. This listener bus
 * is stopped when `stop()` is called, and it will drop further events after stopping.
 *
 * @param name name of the listener bus, will be the name of the listener thread.
 * @tparam L type of listener
 * @tparam E type of event
 */
private[spark] abstract class AsynchronousListenerBus[L <: AnyRef, E](name: String)
  extends ListenerBus[L, E] {

  self =>

  private var sparkContext: SparkContext = null

  /* Cap the capacity of the event queue so we get an explicit error (rather than
   * an OOM exception) if it's perpetually being added to more quickly than it's being drained. */
  private val EVENT_QUEUE_CAPACITY = 10000

  //线程阻塞,不接受null,固定大小10000,先进先出
  private val eventQueue = new LinkedBlockingQueue[E](EVENT_QUEUE_CAPACITY)

  // Indicate if `start()` is called
  
  private val started = new AtomicBoolean(false)
  // Indicate if `stop()` is called
  private val stopped = new AtomicBoolean(false)

  // Indicate if we are processing some event
  // Guarded by `self`
  //事件正在处理
  private var processingEvent = false

  // A counter that represents the number of events produced and consumed in the queue
  //Semaphore负责协调各个线程以保证它们能够正确,合理的使用公共资源
  //Semaphore(0)只能几个线程同时访问
  private val eventLock = new Semaphore(0)
/**
 * 事件匹配监听器的线程:此Thread不断拉取LinkedBlockingQueue中的事件,遍历监听器,调用监听器的方法,任何事件都会在
 * LinkedBlockingQueue中存一段时间,然后Thread处理了些事件后,将其清除,因些使用ListenerBus这个名字再适合不过了,
 * 到站就下车
 */
  private val listenerThread = new Thread(name) {
    setDaemon(true)//设置守护线程
    override def run(): Unit = Utils.tryOrStopSparkContext(sparkContext) {
      while (true) {
        eventLock.acquire()//获取许可
        self.synchronized {
          processingEvent = true
        }
        try {
          val event = eventQueue.poll//检索并移除此队列的头,如果队列为空,则返回null
          if (event == null) {
            // Get out of the while loop and shutdown the daemon thread
            if (!stopped.get) {
              throw new IllegalStateException("Polling `null` from eventQueue means" +
                " the listener bus has been stopped. So `stopped` must be true")
            }
            return
          }
          postToAll(event)
        } finally {
          self.synchronized {
            processingEvent = false
          }
        }
      }
    }
  }

  /**
   * Start sending events to attached listeners.
   * 开始发送事件给连接的侦听器
   * This first sends out all buffered events posted before this listener bus has started, then
   * listens for any additional events asynchronously while the listener bus is still running.
   * This should only be called once.
   *
   * @param sc Used to stop the SparkContext in case the listener thread dies.
   */
  def start(sc: SparkContext) {
    //如果当前值与期望值(第一个参数)相等，则设置为新值(第二个参数)，设置成功返回true
    if (started.compareAndSet(false, true)) {
      sparkContext = sc
      listenerThread.start()
    } else {
      throw new IllegalStateException(s"$name already started!")
    }
  }

  def post(event: E) {
    if (stopped.get) {
      // Drop further events to make `listenerThread` exit ASAP
      logError(s"$name has already stopped! Dropping event $event")
      return
    }
    val eventAdded = eventQueue.offer(event)//在尾部插入一个元素,返回true表示插入成功
    if (eventAdded) {
      eventLock.release()//释放资源
    } else {
      onDropEvent(event)
    }
  }

  /**
   * For testing only. Wait until there are no more events in the queue, or until the specified
   * time has elapsed. Throw `TimeoutException` if the specified time elapsed before the queue
   * emptied.
   * 仅用于测试,等待直到没有的事件在队列中,或者或直到指定的时间超时,
   */
  @VisibleForTesting
  @throws(classOf[TimeoutException])
  def waitUntilEmpty(timeoutMillis: Long): Unit = {
    val finishTime = System.currentTimeMillis + timeoutMillis
    while (!queueIsEmpty) {
      if (System.currentTimeMillis > finishTime) {
        throw new TimeoutException(
          s"The event queue is not empty after $timeoutMillis milliseconds")
      }
      /* Sleep rather than using wait/notify, because this is used only for testing and
       * wait/notify add overhead in the general case. */
      Thread.sleep(10)
    }
  }

  /**
   * For testing only. Return whether the listener daemon thread is still alive.
   * 返回是否听者守护线程仍然活着 
   */
  @VisibleForTesting
  def listenerThreadIsAlive: Boolean = listenerThread.isAlive

  /**
   * Return whether the event queue is empty.
   * 返回事件队列是否为空 
   * The use of synchronized here guarantees that all events that once belonged to this queue
   * have already been processed by all attached listeners, if this returns true.
   */
  private def queueIsEmpty: Boolean = synchronized { eventQueue.isEmpty && !processingEvent }

  /**
   * Stop the listener bus. It will wait until the queued events have been processed, but drop the
   * new events after stopping.
   * 暂停监听事件,将等待事件队列已经被处理,
   */
  def stop() {
    if (!started.get()) {
      throw new IllegalStateException(s"Attempted to stop $name that has not yet started!")
    }
    if (stopped.compareAndSet(false, true)) {
      // Call eventLock.release() so that listenerThread will poll `null` from `eventQueue` and know
      // `stop` is called.
      eventLock.release() //释放资源
      listenerThread.join()//thread.join()应该是让当前线程block住，等thread执行完之后，再继续执行
    } else {
      // Keep quiet
    }
  }

  /**
   * If the event queue exceeds its capacity, the new events will be dropped. The subclasses will be
   * notified with the dropped events.
   * 如果事件队列超过其容量,新的事件将被删除,子类将被通知丢弃事件
   * 
   * Note: `onDropEvent` can be called in any thread.
   */
  def onDropEvent(event: E): Unit
}
