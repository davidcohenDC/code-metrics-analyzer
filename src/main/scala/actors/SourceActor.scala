package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import domain.Job
import domain.SourceStrategy
import domain.syntax.jid

import java.util.UUID

/**
 * The [[SourceActor]] is responsible for scanning the file system starting from a specified root path. It discovers
 * files that match the criteria defined in the [[SourceStrategy]] and submits jobs to the [[QueueActor]] for further
 * processing.
 */
object SourceActor:

  /** Commands that the SourceActor can handle. */
  sealed trait Command

  /** Commands that the SourceActor can handle. */
  object Command:

    /**
     * Represents a [[Command]] to start the process from a specified root path.
     *
     * @param root The root path from which to start the scan.
     * @param replyTo The actor reference to which a confirmation message will be sent once the scan starts.
     */
    final case class Start(root: os.Path, replyTo: ActorRef[Done]) extends Command

    /**
     * Represents a [[Command]] to stop the scanning process.
     */
    case object Stop extends Command

  /** Messages sent by the SourceActor to indicate scan status. */
  sealed trait Done

  /**
   * A [[Done]] message sent by the [[SourceActor]] to confirm that the scan has started.
   */
  case object ScanStarted extends Done

  /**
   * A [[Command]] used internally by the [[SourceActor]] to signal and process the next action or step in a sequence of
   * operations.
   */
  private[SourceActor] case object Next extends Command

  def apply(
      queue: ActorRef[QueueActor.Command],
      sourceStrategy: SourceStrategy = SourceStrategy.FS(),
  ): Behavior[Command] =
    Behaviors.setup: ctx =>
      ctx.log.info("[SRC] ready")

      def isAcceptedFile(p: os.Path): Boolean =
        sourceStrategy match
          case SourceStrategy.FS(exts, _) =>
            val name = p.toString.toLowerCase
            exts.exists(e => name.endsWith(e.toLowerCase))

      def pushDir(stack: collection.mutable.Stack[os.Path], dir: os.Path): Unit =
        sourceStrategy match
          case SourceStrategy.FS(_, recursive) =>
            if recursive then os.list(dir).foreach(stack.push)
            else os.list(dir).filter(os.isFile).foreach(stack.push)

      def idle: Behavior[Command] =
        Behaviors.receiveMessage:
          case Command.Start(root, replyTo) =>
            ctx.log.info(s"[SRC] start from: $root")
            if !os.exists(root) then
              ctx.log.warn(s"[SRC] root does not exist: $root")
              replyTo ! ScanStarted
              queue ! QueueActor.Complete
              Behaviors.same
            else
              val stack = collection.mutable.Stack[os.Path](root)
              replyTo ! ScanStarted
              ctx.self ! Next
              scanning(stack)

          case Command.Stop =>
            Behaviors.same

      def scanning(stack: collection.mutable.Stack[os.Path]): Behavior[Command] =
        Behaviors.receiveMessage:
          case Command.Stop =>
            ctx.log.info("[SRC] stopped cooperatively")
            idle

          case Next =>
            if stack.nonEmpty then
              val cur = stack.pop()
              ctx.log.debug(s"[SRC] scanning: $cur")

              if os.isDir(cur) then pushDir(stack, cur)
              else if os.isFile(cur) && isAcceptedFile(cur) then
                val job = Job(UUID.randomUUID(), cur, attempts = 0)
                ctx.log.info(s"[JID=${jid(job)}] [SRC] discovered ${job.file}")
                queue ! QueueActor.Push(job)

              ctx.self ! Next
              Behaviors.same
            else
              ctx.log.info("[SRC] scan complete")
              queue ! QueueActor.Complete
              idle

      idle
