package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import domain.Job
import domain.syntax.jid

import java.util.UUID
import scala.annotation.tailrec
import scala.collection.immutable.Queue

/**
 * The [[QueueActor]] serves as an intermediary between the source of jobs and the processing processors. It manages job
 * queuing, dispatching to idle processors, tracking in-flight jobs, handling retries, and notifying the supervisor upon
 * completion of all work.
 */
object QueueActor:

  /**
   * * [[Commands]] that the QueueActor can handle.
   */
  sealed trait Command

  /**
   * [[Command]] to push a new job into the queue.
   *
   * @param job The job to be submitted.
   */
  final case class Push(job: Job) extends Command

  /**
   * [[Command]] for a processor to pull a job from the queue.
   *
   * @param replyTo The actor reference of the processor requesting a job.
   */
  final case class Pull(replyTo: ActorRef[ProcessorActor.Command]) extends Command

  /**
   * [[Command]] to acknowledge the completion of a job.
   *
   * @param jobId The unique identifier of the completed job.
   */
  final case class Ack(jobId: UUID) extends Command

  /**
   * [[Command]] to request a retry for a failed job.
   *
   * @param job The job to be retried.
   */
  final case class Retry(job: Job) extends Command

  /**
   * [[Command]] to indicate that no more jobs will be submitted.
   */
  case object Complete extends Command

  /**
   * [[Command]] to reset the queue state.
   */
  case object Reset extends Command

  /**
   * [[Command]] to register the actor for completion notifications.
   *
   * @param ref The actor reference to the supervisor.
   */
  final case class Register(ref: ActorRef[SupervisorActor.Command]) extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup: ctx =>
      val config = ctx.system.settings.config
      val maxRetries =
        if config.hasPath("source.processor.max-retries") then config.getInt("source.processor.max-retries")
        else 3

      final case class State(
          pending: Queue[Job] = Queue.empty,
          idleProcessors: Queue[ActorRef[ProcessorActor.Command]] = Queue.empty,
          inFlight: Map[UUID, Job] = Map.empty,
          noMoreInput: Boolean = false,
          completedNotified: Boolean = false,
          supervisor: Option[ActorRef[SupervisorActor.Command]] = None,
      )

      inline def maybeAllDone(s: State): State =
        if !s.completedNotified && s.noMoreInput && s.pending.isEmpty && s.inFlight.isEmpty then
          ctx.log.info("[QUEUE] ✅ all work completed (drained)")
          s.supervisor.foreach(_ ! SupervisorActor.Completed)
          s.copy(completedNotified = true)
        else s

      def dispatchOne(s: State, to: ActorRef[ProcessorActor.Command], job: Job): State =
        ctx.log.info(s"[JID=${jid(job)}] [QUEUE] dispatch → processor (inFlight +1)")
        to ! ProcessorActor.Process(job)
        s.copy(pending = s.pending.tail, inFlight = s.inFlight + (job.id -> job))

      @tailrec
      def tryDispatch(s: State): State =
        (s.pending.headOption, s.idleProcessors.headOption) match
          case (Some(job), Some(processor)) =>
            val s1 = dispatchOne(s.copy(idleProcessors = s.idleProcessors.tail), processor, job)
            tryDispatch(s1)
          case _ => s

      def behavior(state: State): Behavior[Command] =
        Behaviors.receiveMessage:

          // ============== SUBMIT =================
          case Push(job) =>
            ctx.log.info(s"[JID=${jid(job)}] [QUEUE] submit (pending enqueue)")
            val s1 = state.copy(pending = state.pending.enqueue(job))
            val s2 = tryDispatch(s1)
            behavior(s2)

          // ============== PULL ===================
          case Pull(replyTo) =>
            val next =
              state.pending.headOption match
                case Some(job) => dispatchOne(state, replyTo, job)
                case None => state.copy(idleProcessors = state.idleProcessors.enqueue(replyTo))
            behavior(next)

          // ============== ACK ====================
          case Ack(jobId) =>
            val existed = state.inFlight.contains(jobId)
            val s1 = state.copy(inFlight = state.inFlight - jobId)
            if !existed then ctx.log.warn(s"[QUEUE] AckDone ignored (unknown jobId=$jobId)")
            behavior(maybeAllDone(s1))

          // ============== RETRY ==================
          case Retry(job) =>
            ctx.log.warn(s"[JID=${jid(job)}] [QUEUE] retry request (attempts=${job.attempts})")

            val cleared = state.copy(inFlight = state.inFlight - job.id)
            val nextAttempts = job.attempts + 1

            if nextAttempts > maxRetries then
              ctx.log.warn(s"[JID=${jid(job)}] [QUEUE] drop (exceeded max retries = $maxRetries)")
              behavior(maybeAllDone(cleared))
            else
              val retried = job.copy(attempts = nextAttempts)
              val s1 = cleared.copy(pending = cleared.pending.enqueue(retried))
              val s2 = tryDispatch(s1)
              ctx.log.info(s"[JID=${jid(job)}] [QUEUE] re-enqueued (attempts=$nextAttempts)")
              behavior(s2)

          // ============== COMPLETE ===============
          case Complete =>
            behavior(maybeAllDone(state.copy(noMoreInput = true)))

          // ============== RESET ==================
          case Reset =>
            behavior(State(supervisor = state.supervisor))

          // ============== SUPERVISOR ============
          case Register(ref) =>
            ctx.log.info("[QUEUE] supervisor registered")
            behavior(state.copy(supervisor = Some(ref)))

      behavior(State())
