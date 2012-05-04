package com.github.plokhotnyuk.actors

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import com.github.plokhotnyuk.actors.Helper._

@RunWith(classOf[JUnitRunner])
class MPSCQueueTest extends Specification {
  val n = 100000000

  "Same producer and consumer" in {
    timed("Same producer and consumer", n) {
      val queue = new MPSCQueue[Data]()
      val data = Data()
      var i = n
      while (i > 0) {
        queue.enqueue(data)
        queue.dequeue()
        i -= 1
      }
    }
  }

  "Single-producer sending" in {
    timed("Single-producer sending", n) {
      val queue = new MPSCQueue[Data]()
      fork {
        val q = queue
        val data = Data()
        var i = n
        while (i > 0) {
          q.enqueue(data)
          i -= 1
        }
      }
      var i = n
      while (i > 0) {
        queue.dequeue()
        i -= 1
      }
    }
  }

  "Multi-producer sending" in {
    timed("Multi-producer sending", n) {
      val queue = new MPSCQueue[Data]()
      val p = availableProcessors
      for (j <- 1 to p) {
        fork {
          val q = queue
          val data = Data()
          var i = n / p
          while (i > 0) {
            q.enqueue(data)
            i -= 1
          }
        }
      }
      var i = n
      while (i > 0) {
        queue.dequeue()
        i -= 1
      }
    }
  }

  "Exchange between queues" in {
    timed("Exchange between queues", n) {
      val queue1 = new MPSCQueue[Data]()
      val queue2 = new MPSCQueue[Data]()
      fork {
        val q1 = queue1
        val q2 = queue2
        var i = n / 2
        while (i > 0) {
          q1.enqueue(q2.dequeue())
          i -= 1
        }
      }
      queue1.enqueue(Data())
      var i = n / 2
      while (i > 0) {
        queue2.enqueue(queue1.dequeue())
        i -= 1
      }
    }
  }
}