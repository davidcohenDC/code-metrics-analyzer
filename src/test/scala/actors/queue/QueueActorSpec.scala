package actors.queue

import actors.ProcessorActor
import actors.QueueActor
import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox
import domain.Job
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class QueueActorSpec extends AnyFlatSpec with Matchers:

  private def mkJob(path: os.Path, attempts: Int = 0): Job =
    Job(UUID.randomUUID(), path, attempts)

  "A QueueActor" should "dispatch immediately when a processor is waiting" in {
    val testKit = BehaviorTestKit(QueueActor())
    val processorInbox = TestInbox[ProcessorActor.Command]()

    // processor asks first => goes idle
    testKit.run(QueueActor.Pull(processorInbox.ref))

    val job = mkJob(os.Path("/tmp/FileA.java"))
    testKit.run(QueueActor.Push(job))

    processorInbox.expectMessage(ProcessorActor.Process(job))
  }

  it should "enqueue work when no processor is waiting" in {
    val testKit = BehaviorTestKit(QueueActor())
    val processorInbox = TestInbox[ProcessorActor.Command]()

    val job = mkJob(os.Path("/tmp/FileB.java"))
    testKit.run(QueueActor.Push(job))

    // now the processor asks
    testKit.run(QueueActor.Pull(processorInbox.ref))
    processorInbox.expectMessage(ProcessorActor.Process(job))
  }

  it should "park multiple idle processors in FIFO order" in {
    val testKit = BehaviorTestKit(QueueActor())

    val w1 = TestInbox[ProcessorActor.Command]()
    val w2 = TestInbox[ProcessorActor.Command]()

    // both processors ask but no jobs yet
    testKit.run(QueueActor.Pull(w1.ref))
    testKit.run(QueueActor.Pull(w2.ref))

    // submit 2 jobs => dispatch to w1 then w2
    val j1 = mkJob(os.Path("/tmp/A.java"))
    val j2 = mkJob(os.Path("/tmp/B.java"))

    testKit.run(QueueActor.Push(j1))
    testKit.run(QueueActor.Push(j2))

    w1.expectMessage(ProcessorActor.Process(j1))
    w2.expectMessage(ProcessorActor.Process(j2))
  }

  it should "mark completion without pending" in {
    val testKit = BehaviorTestKit(QueueActor())
    // just ensure no crash
    testKit.run(QueueActor.Complete)
  }

  it should "re-enqueue a job on Retry incrementing attempts" in {
    val testKit = BehaviorTestKit(QueueActor())
    val processorInbox = TestInbox[ProcessorActor.Command]()

    val job = mkJob(os.Path("/tmp/retry.java"), attempts = 0)

    // enqueue first
    testKit.run(QueueActor.Push(job))

    // consume by a processor (so now it's in-flight)
    testKit.run(QueueActor.Pull(processorInbox.ref))
    val _ = processorInbox.receiveMessage() // consume Work(job)

    // simulate failure / timeout => ask retry
    testKit.run(QueueActor.Retry(job))

    // now retry must be in pending again
    val processor2 = TestInbox[ProcessorActor.Command]()
    testKit.run(QueueActor.Pull(processor2.ref))

    val retried = processor2.receiveMessage() match
      case ProcessorActor.Process(j) => j
      case other => fail(s"expected Work(job) but got $other")

    retried.id shouldBe job.id
    retried.attempts shouldBe 1
  }
