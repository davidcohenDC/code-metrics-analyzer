package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import domain.syntax.*

import java.util.UUID

/**
 * The [[SupervisorActor]] is responsible for orchestrating the overall workflow of the application. It coordinates
 * multiple actors, including the source crawler, queue, reducer, processor actors, and a UI bridge, managing their
 * interactions and transitions between different states.
 */
object SupervisorActor:

  /** Commands that the SupervisorActor can handle. */
  sealed trait Command

  /** Commands that the SupervisorActor can handle. */
  object Command:
    /**
     * Represents a command to initiate a process starting from a specified root path.
     *
     * @param root The root path from which to start the operation.
     * @param params The parameters for the reduction process.
     */
    final case class Run(root: os.Path, params: ReducerActor.ReduceParams) extends Command

    /**
     * Represents a command to stop the ongoing process or operation.
     */
    case object Stop extends Command

  /**
   * Represents a command sent by a [[SupervisorActor]] to indicate that all work has been completed.
   */
  case object Completed extends Command

  def apply(
      crawler: ActorRef[SourceActor.Command],
      queue: ActorRef[QueueActor.Command],
      reducer: ActorRef[ReducerActor.Command],
      processors: Seq[ActorRef[ProcessorActor.Command]],
      uiBridge: ActorRef[UiBridgeActor.Command],
  ): Behavior[Command] =
    Behaviors.setup: ctx =>
      def startFlow(root: os.Path, params: ReducerActor.ReduceParams): UUID =
        val scanId = UUID.randomUUID()
        ctx.log.info(s"[SCAN=${scanId.jid}] [SUPERVISOR] start request for root: $root")
        reducer ! ReducerActor.Command.UpdateParams(params)
        reducer ! ReducerActor.Command.ResetStats
        queue ! QueueActor.Reset
        processors.foreach(_ ! ProcessorActor.StartPulling)
        uiBridge ! UiBridgeActor.Command.StatusRunning
        crawler ! SourceActor.Command.Start(root, ctx.system.ignoreRef.narrow)
        scanId

      def stopFlow(scanId: UUID): Unit =
        ctx.log.info(s"[SCAN=${scanId.jid}] [SUPERVISOR] stop requested")
        crawler ! SourceActor.Command.Stop
        uiBridge ! UiBridgeActor.Command.StatusIdle

      def idle: Behavior[Command] =
        Behaviors.receiveMessage:
          case Command.Run(root, params) => running(startFlow(root, params))
          case Command.Stop | Completed => Behaviors.same

      def running(scanId: UUID): Behavior[Command] =
        Behaviors.receiveMessage:
          case Command.Run(_, _) =>
            ctx.log.warn(s"[SCAN=${scanId.jid}] [SUPERVISOR] start ignored (already running)")
            Behaviors.same
          case Command.Stop =>
            stopFlow(scanId)
            idle

          case Completed =>
            ctx.log.info(s"[SCAN=${scanId.jid}] [SUP] all work completed")
            uiBridge ! UiBridgeActor.Command.Completed
            uiBridge ! UiBridgeActor.Command.StatusIdle
            idle
      idle
