package actors.processor

import actors.ProcessorActor
import actors.QueueActor
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.typesafe.config.ConfigFactory
import domain.Job
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.concurrent.duration.*

class ProcessorQueueIntegrationSpec extends AnyWordSpec with Matchers with Eventually:

  private val config = ConfigFactory.parseString("""
    source.processor.timeout = 50ms
    source.processor.max-retries = 5

    source.dispatcher {
      type = Dispatcher
      executor = "thread-pool-executor"
      thread-pool-executor {
        fixed-pool-size = 2
      }
      throughput = 1
    }
  """)

  private val testKit = ActorTestKit(config)

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(50, Millis))

  "Processor + Queue" should {
    "increment attempts when retry is re-enqueued by Queue" in {
      val queue = testKit.spawn(QueueActor(), "queue-it")
      val reductionProbe = testKit.createTestProbe[actors.ReducerActor.Command]()
      val slowFn: os.Path => Int = _ =>
        Thread.sleep(200); 0

      val processor = testKit.spawn(
        ProcessorActor(
          id = "processor-it",
          queue = queue,
          reducer = reductionProbe.ref,
          countFn = slowFn,
        ),
      )

      val job = Job(UUID.randomUUID(), os.pwd / "slow.java", attempts = 0)

      // 1) Il processor si mette in attesa di work
      val pullProbe = testKit.createTestProbe[actors.ProcessorActor.Command]()
      queue ! QueueActor.Pull(pullProbe.ref)

      // 2) Il job entra
      queue ! QueueActor.Push(job)

      // 3) Il processor riceve il job
      val firstWork = pullProbe.expectMessageType[actors.ProcessorActor.Process]
      firstWork.job.attempts shouldBe 0

      // 4) Lo passiamo al processor reale → timeout → Retry → re-enqueue
      processor ! firstWork

      // 5) now eventually the queue will re-dispatch it w/ attempts=1
      eventually {
        val probe2 = testKit.createTestProbe[actors.ProcessorActor.Command]()
        queue ! QueueActor.Pull(probe2.ref)
        val work2 = probe2.receiveMessage(200.millis)

        work2 match
          case actors.ProcessorActor.Process(j2) =>
            j2.id shouldBe job.id
            j2.attempts shouldBe 1
          case other =>
            fail(s"expected Work(job) but got $other")
      }
    }
  }
