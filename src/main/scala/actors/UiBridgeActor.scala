package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior

/**
 * The [[UiBridgeActor]] serves as a communication bridge between the backend processing actors and the user interface
 * (UI) components. It implements a publish-subscribe pattern, allowing multiple UI listeners to subscribe for updates
 * on the processing state and status.
 */
object UiBridgeActor:

  /** Commands that the UiBridgeActor can handle. */
  sealed trait Command

  /** Commands that the UiBridgeActor can handle. */
  object Command:

    /**
     * Represents a command to subscribe an actor reference to receive events.
     *
     * @param ref The [[ActorRef]] that should receive events of type [[Event]].
     */
    final case class Subscribe(ref: ActorRef[Event]) extends Command

    /**
     * Represents a command to unsubscribe an actor reference from receiving events.
     *
     * @param ref The [[ActorRef]] that should no longer receive events of type [[Event]].
     */
    final case class Unsubscribe(ref: ActorRef[Event]) extends Command

    /**
     * Represents a command to push the current state, including top files and distribution information.
     *
     * @param topFiles A sequence of [[os.Path]] representing the top files.
     * @param distribution A string representing the distribution information.
     */
    final case class PushState(topFiles: Seq[os.Path], distribution: String) extends Command

    /**
     * [[StatusMsg]] is a sealed trait representing status-related messages.
     */
    sealed trait StatusMsg extends Command

    /**
     * [[StatusRunning]] represents a message indicating that the system or a specific actor is currently in a running
     * state.
     */
    case object StatusRunning extends StatusMsg

    /**
     * [[StatusIdle]] represents a message indicating that the system or a specific actor is currently in an idle state.
     */
    case object StatusIdle extends StatusMsg

    /**
     * [[Completed]] represents a message indicating that a specific process or operation has been completed.
     */
    case object Completed extends Command

  /**
   * [[Event]] representing events that can be published to subscribers.
   */
  sealed trait Event

  object Event:
    /**
     * Represents the UI state event containing top files and distribution information.
     *
     * @param topFiles A sequence of [[os.Path]] representing the top files.
     * @param distribution A string representing the distribution information.
     */
    final case class UiState(topFiles: Seq[os.Path], distribution: String) extends Event

    /**
     * Represents a status event with a label.
     *
     * @param label A string representing the status label.
     */
    final case class Status(label: String) extends Event

    /**
     * Represents an event indicating that all processing is done.
     */
    case object AllDone extends Event

  import Command.*
  import Event.*

  /**
   * Represents the state of the UiBridgeActor, maintaining information about subscribers and various parameters that
   * track the latest state of the system.
   *
   * @param subscribers Set of actor references subscribed to receive [[Event]] updates.
   * @param lastTop A sequence of paths representing the last processed or displayed top-level items.
   * @param lastDist The last distribution identifier or value processed by the actor.
   * @param lastStatus The most recent status of the actor, represented by a string.
   */
  final case class State(
      subscribers: Set[ActorRef[Event]] = Set.empty,
      lastTop: Seq[os.Path] = Seq.empty,
      lastDist: String = "",
      lastStatus: String = "IDLE",
  )

  def apply(): Behavior[Command] =
    Behaviors.setup: ctx =>
      ctx.log.info("[UI] ready (pub/sub)")

      def deliverSnapshot(s: State, to: ActorRef[Event]): Unit = to ! UiState(s.lastTop, s.lastDist)

      def deliverStatus(s: State, to: ActorRef[Event]): Unit = to ! Status(s.lastStatus)

      def publishSnapshotAll(s: State): Unit = s.subscribers.foreach(deliverSnapshot(s, _))

      def publishStatusAll(s: State): Unit = s.subscribers.foreach(deliverStatus(s, _))

      def behavior(s: State): Behavior[Command] =
        Behaviors.receiveMessage:

          case Subscribe(ref) =>
            ctx.log.info(s"[UI] + subscribe ${ref.path.name}")
            deliverSnapshot(s, ref)
            deliverStatus(s, ref)
            behavior(s.copy(subscribers = s.subscribers + ref))

          case Unsubscribe(ref) =>
            ctx.log.info(s"[UI] - unsubscribe ${ref.path.name}")
            behavior(s.copy(subscribers = s.subscribers - ref))

          case PushState(top, dist) =>
            val updated = s.copy(lastTop = top, lastDist = dist)
            ctx.log.debug(s"[UI] broadcast snapshot (top=${top.size})")
            publishSnapshotAll(updated)
            behavior(updated)

          case StatusRunning =>
            ctx.log.info("[UI] scan started (RUNNING)")
            val updated = s.copy(lastStatus = "RUNNING")
            publishStatusAll(updated)
            behavior(updated)

          case StatusIdle =>
            ctx.log.info("[UI] status=IDLE")
            val updated = s.copy(lastStatus = "IDLE")
            publishStatusAll(updated)
            behavior(updated)

          case Completed =>
            ctx.log.info("[UI] scan completed → notifying subscribers")
            s.subscribers.foreach(_ ! AllDone)
            Behaviors.same

      behavior(State())
