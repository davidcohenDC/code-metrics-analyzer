package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import domain.syntax.jid
import domain.ReduceStrategy
import domain.Result
import domain.ResultData

import java.util.UUID

/**
 * The [[ReducerActor]] is responsible for reduction results from multiple processing jobs. It collects results,
 * maintains statistics such as top N files by lines of code and distribution of lines of code, and communicates the
 * reduction state to the UI bridge actor.
 */
object ReducerActor:

  /** Parameters for reduction strategies. */
  final case class ReduceParams(maxFiles: Int, bins: Int, maxLen: Int)

  /** Commands that the ReducerActor can handle. */
  sealed trait Command

  /** Commands that the ReducerActor can handle. */
  object Command:
    /**
     * Represents a reduce command used by the ReducerActor to process or handle a `Result`.
     *
     * @param r The [[Result]] that contains processed data, metadata, and job-specific identifiers.
     */
    final case class Reduce(r: Result) extends Command

    /**
     * Represents a command to update the reduction parameters of the ReducerActor.
     *
     * @param p The new [[ReduceParams]] to be applied for reduction.
     */
    final case class UpdateParams(r: ReduceParams) extends Command

    /**
     * Represents a command to retrieve statistics from the ReducerActor.
     *
     * @param maxFiles The maximum number of top files to include in the statistics.
     * @param numIntervals The number of intervals to use for the distribution of lines of code.
     * @param maxLength The maximum length (in lines of code) to consider for the distribution.
     * @param replyTo The actor reference to which the statistics should be sent.
     */
    final case class GetStats(r: ReduceParams, replyTo: ActorRef[Snapshot]) extends Command

    /**
     * Represents a command to reset the statistics maintained by the ReducerActor.
     */
    case object ResetStats extends Command

  /** Statistics snapshot returned by the ReducerActor. */
  final case class Snapshot(topFiles: Seq[os.Path], distribution: String)

  /**
   * Represents the internal state of the ReducerActor for tracking code analysis and processing.
   *
   * @param locs Tracks a vector of file paths paired with lines of code count. This is used for the reduction of lines
   *   of code statistics (e.g., computing total LOC or other metrics).
   * @param topN Maintains a vector of file paths ordered by their lines of code count, storing the top N files with the
   *   highest LOC. This supports strategies focusing on identifying the files with the most LOC.
   * @param seen A set of unique identifiers (UUID) used to track which files or entities have already been processed to
   *   prevent duplication or redundant computation during analysis.
   */
  private final case class State(
      locs: Vector[(os.Path, Int)] = Vector.empty, // per LinesOfCode
      topN: Vector[(Int, os.Path)] = Vector.empty, // per TopN loc
      seen: Set[UUID] = Set.empty,
      params: ReduceParams = ReduceParams(maxFiles = 10, bins = 10, maxLen = 100),
  )

  def apply(
      uiBridge: ActorRef[UiBridgeActor.Command],
      reduce: ReduceStrategy = ReduceStrategy.TopNByLoc(10),
  ): Behavior[Command] =
    Behaviors.setup: ctx =>
      def snapshotAndPush(s: State): Unit =
        val top = s.topN.sortBy(-_._1).take(s.params.maxFiles).map(_._2)
        val dist = distributionString(s.locs.map(_._2), math.max(2, s.params.bins), math.max(1, s.params.maxLen))
        uiBridge ! UiBridgeActor.Command.PushState(top, dist)

      def onReduce(state: State, r: Result): (State, Boolean) =
        if state.seen.contains(r.jobId) then
          ctx.log.debug(s"[JID=${jid(r.jobId)}] [REDUCER] dedup → ignored")
          (state, false)
        else
          r.data match
            case ResultData.LinesOfCode(path, loc) =>
              ctx.log.info(s"[JID=${jid(r.jobId)}] [REDUCER] accepted loc=$loc from $path")
              (
                state.copy(
                  locs = state.locs :+ (path -> loc),
                  topN = state.topN :+ (loc -> path),
                  seen = state.seen + r.jobId,
                ),
                true,
              )
            case ResultData.Sha256(path, hex) =>
              ctx.log.info(s"[JID=${jid(r.jobId)}] [REDUCER] accepted sha256=$hex from $path")
              (state.copy(seen = state.seen + r.jobId), true)

      def behavior(state: State): Behavior[Command] =
        Behaviors.receiveMessage:
          case Command.UpdateParams(p) =>
            ctx.log.info(s"[REDUCER] params set: maxFiles=${p.maxFiles}, bins=${p.bins}, maxLen=${p.maxLen}")
            // mantieni i dati raccolti ma cambia i parametri di vista/riduzione
            val next = state.copy(params = p)
            snapshotAndPush(next)
            behavior(next)

          case Command.Reduce(r) =>
            val (next, changed) = onReduce(state, r)
            if changed then snapshotAndPush(next)
            behavior(next)

          case Command.GetStats(_, replyTo) =>
            // Ignoriamo i 3 Int passati: usiamo sempre lo stato corrente (modello A)
            val top = state.topN.sortBy(-_._1).take(state.params.maxFiles).map(_._2)
            val dist =
              distributionString(state.locs.map(_._2), math.max(2, state.params.bins), math.max(1, state.params.maxLen))
            replyTo ! Snapshot(top, dist)
            Behaviors.same

          case Command.ResetStats =>
            ctx.log.info("[REDUCER] reset stats")
            val cleared = state.copy(locs = Vector.empty, topN = Vector.empty, seen = Set.empty)
            snapshotAndPush(cleared)
            behavior(cleared)

      behavior(State())

  private def distributionString(values: Iterable[Int], ni: Int, maxL: Int): String =
    val buckets = Array.fill(ni)(0)
    val denom = ni - 1
    val chunk = math.max(1, math.ceil(maxL.toDouble / denom).toInt)

    def bucketOf(loc: Int) =
      if loc >= maxL then ni - 1 else math.min(loc / chunk, ni - 2)

    values.foreach(loc => buckets(bucketOf(loc)) += 1)
    (0 until ni).map { i =>
      if i == ni - 1 then s"[$maxL,+inf]: ${buckets(i)}"
      else
        val start = i * chunk
        val end = math.min(maxL - 1, (i + 1) * chunk - 1)
        s"[$start,$end]: ${buckets(i)}"
    }.mkString("\n")
