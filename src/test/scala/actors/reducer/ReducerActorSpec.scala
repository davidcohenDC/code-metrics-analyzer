import actors.ReducerActor.Command.*
import actors.ReducerActor
import actors.UiBridgeActor
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import domain.Result
import domain.ResultData
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import os.Path

import java.util.UUID

class ReducerActorSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()

  "AggregatorActor" should {

    "accumulare risultati e restituire i top N in ordine decrescente" in {
      val uiProbe = testKit.createTestProbe[UiBridgeActor.Command]()
      val agg = testKit.spawn(ReducerActor(uiProbe.ref), "agg-topN")
      val probe = testKit.createTestProbe[ReducerActor.Snapshot]()

      agg ! UpdateParams(ReducerActor.ReduceParams(maxFiles = 3, bins = 5, maxLen = 100))

      val p1 = os.pwd / "file1.java" // 100
      val p2 = os.pwd / "file2.java" // 200
      val p3 = os.pwd / "file3.java" // 50
      val p4 = os.pwd / "file4.java" // 150

      agg ! Reduce(Result(UUID.randomUUID(), ResultData.LinesOfCode(p1, 100)))
      agg ! Reduce(Result(UUID.randomUUID(), ResultData.LinesOfCode(p2, 200)))
      agg ! Reduce(Result(UUID.randomUUID(), ResultData.LinesOfCode(p3, 50)))
      agg ! Reduce(Result(UUID.randomUUID(), ResultData.LinesOfCode(p4, 150)))

      agg ! GetStats(ReducerActor.ReduceParams(maxFiles = 5, bins = 5, maxLen = 100), replyTo = probe.ref)
      val stats = probe.receiveMessage()

      val _ = stats.topFiles shouldBe Seq(p2, p4, p1) // correttamente tagliato a 3
      stats.distribution.nonEmpty shouldBe true
    }

    "ignorare i duplicati dello stesso jobId (dedup effectively-once)" in {
      val uiProbe = testKit.createTestProbe[UiBridgeActor.Command]()
      val agg = testKit.spawn(ReducerActor(uiProbe.ref), "agg-dedup")
      val probe = testKit.createTestProbe[ReducerActor.Snapshot]()

      val jobId = UUID.randomUUID()
      val path = os.pwd / "dup.java"

      // first send -> accepted
      agg ! Reduce(Result(jobId, ResultData.LinesOfCode(path, 999)))

      // waiting for push
      uiProbe.expectMessageType[UiBridgeActor.Command.PushState]

      // duplicate send -> ignored
      agg ! Reduce(Result(jobId, ResultData.LinesOfCode(path, 999)))

      // no further push
      uiProbe.expectNoMessage()

      // final stats check
      agg ! GetStats(ReducerActor.ReduceParams(maxFiles = 5, bins = 5, maxLen = 100), replyTo = probe.ref)
      val stats = probe.receiveMessage()

      val _ = stats.topFiles shouldBe Seq(path)

      val totalCount =
        stats.distribution
          .split("\n")
          .map(_.split(":").last.trim.toInt)
          .sum

      totalCount shouldBe 1
    }

  }
