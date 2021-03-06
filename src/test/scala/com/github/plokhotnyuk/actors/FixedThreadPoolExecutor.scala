package com.github.plokhotnyuk.actors

import java.util
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import java.util.concurrent.locks.AbstractQueuedSynchronizer

/**
 * An implementation of an `java.util.concurrent.ExecutorService ExecutorService`
 * with fixed number of pooled threads. It efficiently works at high rate of task submission and/or
 * when number of worker threads greater than available processors without overuse of CPU and
 * increasing latency between submission of tasks and starting of execution of them.
 *
 * For applications that require separate or custom pools, a `FixedThreadPoolExecutor`
 * may be constructed with a given pool size, that by default is equal to the number of available processors.
 *
 * All threads are created in constructor call using a `java.util.concurrent.ThreadFactory`.
 * If not otherwise specified, a default thread factory is used, that creates threads with daemon status.
 *
 * When running of tasks an uncaught exception can occurs. All unhandled exception are redirected to handler
 * that if not adjusted, by default, just print stack trace without stopping of execution of worker thread.
 *
 * Number of tasks which submitted but not yet executed is not limited, so
 * `java.util.concurrent.RejectedExecutionException` can occurs only after shutdown
 * when pool was initialized with default implementation of `onReject: Runnable => Unit`.
 *
 * Implementation based on non-intrusive MPSC node-based queue, modified for MPMC access:
 * [[http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue]]
 *
 * Idea of using some implementation of 'java.util.concurrent.locks.AbstractQueuedSynchronizer' from
 * [[https://github.com/laforge49/JActor2/blob/master/jactor2-core/src/main/java/org/agilewiki/jactor2/core/facilities/ThreadManager.java]]
 *
 * @param poolSize       A number of worker threads in pool
 * @param threadFactory  A factory to be used to build worker threads
 * @param onError        The exception handler for unhandled errors during executing of tasks
 * @param onReject       The handler for rejection of task submission after shutdown
 * @param name           A name of the executor service
 */
class FixedThreadPoolExecutor(poolSize: Int = Runtime.getRuntime.availableProcessors,
                              threadFactory: ThreadFactory = FixedThreadPoolExecutor.daemonThreadFactory(),
                              onError: Throwable => Unit = _.printStackTrace(),
                              onReject: Runnable => Unit = t => throw new RejectedExecutionException(t.toString),
                              name: String = FixedThreadPoolExecutor.generateName()) extends AbstractExecutorService {
  private val spin = 512 / Math.min(poolSize, Runtime.getRuntime.availableProcessors)
  private val sync = new AbstractQueuedSynchronizer {
    override protected final def tryReleaseShared(ignore: Int): Boolean = true

    override protected final def tryAcquireShared(ignore: Int): Int = work(spin)
  }
  private val tail = new AtomicReference[TaskNode](new TaskNode)
  private val state = new AtomicInteger // pool state (0 - running, 1 - shutdown, 2 - stop)
  private val head = new AtomicReference[TaskNode](tail.get)
  private val terminations = new CountDownLatch(poolSize)
  private val threads = {
    val tf = threadFactory // to avoid creating of fields for a constructor params
    val ts = terminations // to avoid long field name
    (1 to poolSize).map {
      i =>
        val wt = tf.newThread(new Runnable {
          def run(): Unit =
            try loop() catch {
              case _: InterruptedException => // ignore
            } finally ts.countDown()
        })
        wt.setName(name + "-worker-" + i)
        wt.start()
        wt
    }
  }

  def shutdown(): Unit = {
    checkShutdownAccess()
    setState(1)
  }

  def shutdownNow(): util.List[Runnable] = {
    checkShutdownAccess()
    setState(2)
    threads.filter(_ ne Thread.currentThread).foreach(_.interrupt()) // don't interrupt worker thread due call in task
    drainTo(new util.LinkedList[Runnable])
  }

  def isShutdown: Boolean = state.get != 0

  def isTerminated: Boolean = terminations.getCount == 0

  def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
    if (threads.exists(_ eq Thread.currentThread)) terminations.countDown() // don't hang up due call in task
    terminations.await(timeout, unit)
  }

  def execute(t: Runnable): Unit =
    if (t eq null) throw new NullPointerException
    else if (state.get != 0) onReject(t)
    else {
      val n = new TaskNode(t)
      head.getAndSet(n).set(n)
      sync.releaseShared(1)
    }

  override def toString: String = s"${super.toString}[$status], pool size = ${threads.size}, name = $name]"

  @annotation.tailrec
  private def drainTo(ts: util.List[Runnable]): util.List[Runnable] = {
    val tn = tail.get
    val n = tn.get
    if (n eq null) ts
    else if (tail.compareAndSet(tn, n)) {
      ts.add(n.task)
      n.task = null
      drainTo(ts)
    } else drainTo(ts)
  }

  @annotation.tailrec
  private def loop(): Unit = {
    try sync.acquireSharedInterruptibly(1) catch {
      case _: InterruptedException => return
      case ex: Throwable => onError(ex)
    }
    loop()
  }

  @annotation.tailrec
  private def work(s: Int): Int = {
    val tn = tail.get
    val n = tn.get
    if (n ne null) {
      if (tail.compareAndSet(tn, n)) try n.task.run() finally n.task = null
      if (state.get == 2) throw new InterruptedException
      if (s > 0) work(s - 1) else 1
    } else {
      if (state.get != 0) throw new InterruptedException
      if (s > 0) work(s - 1) else -1
    }
  }

  @annotation.tailrec
  private def setState(newState: Int): Unit = {
    val currState = state.get
    if (newState > currState && !state.compareAndSet(currState, newState)) setState(newState)
  }

  private def status: String =
    if (isTerminated) "Terminated"
    else state.get match {
      case 0 => "Running"
      case 1 => "Shutdown"
      case 2 => "Stop"
    }

  private def checkShutdownAccess(): Unit =
    Option(System.getSecurityManager).foreach {
      sm =>
        sm.checkPermission(FixedThreadPoolExecutor.shutdownPerm)
        threads.foreach(sm.checkAccess)
    }
}

private object FixedThreadPoolExecutor {
  private val poolId = new AtomicInteger
  private val shutdownPerm = new RuntimePermission("modifyThread")

  def daemonThreadFactory(): ThreadFactory = new ThreadFactory {
    def newThread(worker: Runnable): Thread = new Thread(worker) {
      setDaemon(true)
    }
  }

  def generateName(): String = "FixedThreadPool-" + poolId.incrementAndGet()
}

private class TaskNode(var task: Runnable = null) extends AtomicReference[TaskNode]
