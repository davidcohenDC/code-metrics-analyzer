package actors.source

import actors.QueueActor
import actors.SourceActor
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.concurrent.duration.*

class SourceActorSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()

  "A SourceActor" should {

    "emit jobs for matching files and then signal completion" in {
      val queueProbe = testKit.createTestProbe[QueueActor.Command]()
      val actor = testKit.spawn(
        SourceActor(
          queue = queueProbe.ref,
          sourceStrategy = domain.SourceStrategy.FS(extensions = Seq(".scala"), recursive = false),
        ),
        name = s"source-${UUID.randomUUID()}",
      )

      val dir = os.temp.dir(prefix = "source-actor-")
      try
        val matching = dir / "Match.scala"
        val ignored = dir / "Ignore.java"
        os.write.over(matching, "object Match")
        os.write.over(ignored, "public class Ignore {}")

        val ackProbe = testKit.createTestProbe[SourceActor.Done]()
        actor ! SourceActor.Command.Start(dir, ackProbe.ref)

        ackProbe.expectMessage(SourceActor.ScanStarted)

        val pushed = queueProbe.expectMessageType[QueueActor.Push]
        pushed.job.file shouldBe matching

        queueProbe.expectMessage(QueueActor.Complete)
        queueProbe.expectNoMessage(200.millis)
      finally os.remove.all(dir)

      testKit.stop(actor)
    }

    "notify completion immediately when the root does not exist" in {
      val queueProbe = testKit.createTestProbe[QueueActor.Command]()
      val actor = testKit.spawn(SourceActor(queueProbe.ref), name = s"source-missing-${UUID.randomUUID()}")

      val missingRoot = os.pwd / UUID.randomUUID().toString
      val ackProbe = testKit.createTestProbe[SourceActor.Done]()

      actor ! SourceActor.Command.Start(missingRoot, ackProbe.ref)

      ackProbe.expectMessage(SourceActor.ScanStarted)
      queueProbe.expectMessage(QueueActor.Complete)
      queueProbe.expectNoMessage(200.millis)

      testKit.stop(actor)
    }
  }
