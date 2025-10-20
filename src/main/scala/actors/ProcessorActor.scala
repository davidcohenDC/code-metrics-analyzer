package actors

import actors.ReducerActor.Command as ReducerCommand
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import domain.syntax.jid
import domain.Job
import domain.ProcessStrategy
import domain.Result
import domain.ResultData

import java.security.MessageDigest
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/**
 * The [[ProcessorActor]] is responsible for processing jobs pulled from the [[QueueActor]]. It executes the processing
 * logic based on the specified strategy (e.g., counting lines of code or computing SHA-256 hash) and sends the results
 * to the [[ReducerActor]] for reduction.
 */
object ProcessorActor:

  /**
   * * [[Command]]s that the ProcessorActor can handle.
   */
  sealed trait Command

  /**
   * [[Command]] to start pulling jobs from the queue.
   */
  case object StartPulling extends Command

  /**
   * [[Command]] to stop the processor actor.
   */
  case object Stop extends Command

  /**
   * [[Command]] to process a specific job.
   *
   * @param job The job to be processed.
   */
  final case class Process(job: Job) extends Command

  /**
   * [[Command]] indicating that a job has been completed successfully.
   *
   * @param job The job that was processed.
   * @param result The result of the processing.
   */
  private final case class Done(job: Job, result: Result) extends Command

  /**
   * [[Command]] indicating that a job has failed during processing.
   *
   * @param job The job that failed.
   * @param ex The exception that caused the failure.
   */
  private final case class ProcessFailed(job: Job, ex: Throwable) extends Command

  /**
   * [[Command]] indicating that a job has timed out.
   *
   * @param job The job that timed out.
   */
  private final case class ProcessingTimeout(job: Job) extends Command

  def apply(
      id: String,
      queue: ActorRef[QueueActor.Command],
      reducer: ActorRef[ReducerActor.Command],
      processStrategy: ProcessStrategy = ProcessStrategy.LinesOfCode,
      blockingDispatcherName: String = "source.dispatcher",
      countFn: os.Path => Int = countLOC,
  ): Behavior[Command] =
    Behaviors.withStash(256): stash =>
      Behaviors.setup: ctx =>
        given ExecutionContext =
          ctx.system.dispatchers.lookup(DispatcherSelector.fromConfig(blockingDispatcherName))

        val config = ctx.system.settings.config
        val timeout: FiniteDuration =
          if config.hasPath("source.processor.timeout") then
            config.getDuration("source.processor.timeout").toMillis.millis
          else 5.seconds

        var currentTimer: Option[Cancellable] = None

        def clearTimer(): Unit =
          currentTimer.foreach(_.cancel())
          currentTimer = None

        def requestMore(): Unit = queue ! QueueActor.Pull(ctx.self)

        def compute(job: Job): Future[Result] = processStrategy match
          case ProcessStrategy.LinesOfCode =>
            Future(countFn(job.file)).map { loc =>
              Result(job.id, ResultData.LinesOfCode(job.file, loc))
            }
          case ProcessStrategy.Sha256 =>
            Future {
              val bytes = os.read.bytes(job.file)
              val md = MessageDigest.getInstance("SHA-256")
              md.update(bytes)
              val hex = md.digest().map("%02x".format(_)).mkString
              Result(job.id, ResultData.Sha256(job.file, hex))
            }

        def runJob(job: Job): Unit =
          ctx.log.info(s"[JID=${jid(job)}] [PROC-$id] start ${job.file}")
          clearTimer()
          currentTimer = Some(ctx.scheduleOnce(timeout, ctx.self, ProcessingTimeout(job)))
          compute(job).onComplete {
            case Success(res) => ctx.self ! Done(job, res)
            case Failure(ex) => ctx.self ! ProcessFailed(job, ex)
          }

        def onDone(job: Job, result: Result): Behavior[Command] =
          clearTimer()
          result.data match
            case ResultData.LinesOfCode(_, loc) =>
              ctx.log.info(s"[JID=${jid(job)}] [PROC-$id] ✓ done (loc=$loc)")
            case ResultData.Sha256(_, hex) =>
              ctx.log.info(s"[JID=${jid(job)}] [PROC-$id] ✓ done (sha256=$hex)")

          reducer ! ReducerCommand.Reduce(result)
          queue ! QueueActor.Ack(job.id)
          requestMore()
          stash.unstashAll(waiting)

        def onFailed(job: Job, ex: Throwable): Behavior[Command] =
          clearTimer()
          ctx.log.warn(s"[JID=${jid(job)}] [PROC-$id] ✗ failed cause=${ex.getMessage}")
          queue ! QueueActor.Retry(job)
          requestMore()
          stash.unstashAll(waiting)

        def onTimeout(job: Job): Behavior[Command] =
          clearTimer()
          ctx.log.warn(s"[JID=${jid(job)}] [PROC-$id] ⏱ timeout after $timeout")
          queue ! QueueActor.Retry(job)
          requestMore()
          stash.unstashAll(waiting)

        def handleCommon(msg: Command): Option[Behavior[Command]] = msg match
          case ProcessingTimeout(job) => Some(onTimeout(job))
          case _ => None

        def idle: Behavior[Command] =
          Behaviors.receiveMessage: msg =>
            handleCommon(msg).getOrElse:
              msg match
                case StartPulling => requestMore(); waiting
                case Process(job) => runJob(job); working
                case Stop => ctx.log.info(s"[PROC-$id] stopping"); Behaviors.stopped
                case other => stash.stash(other); Behaviors.same

        def waiting: Behavior[Command] =
          Behaviors.receiveMessage: msg =>
            handleCommon(msg).getOrElse:
              msg match
                case StartPulling => requestMore(); Behaviors.same
                case Process(job) => runJob(job); working
                case Stop => ctx.log.info(s"[PROC-$id] stopping"); Behaviors.stopped
                case other => stash.stash(other); Behaviors.same

        def working: Behavior[Command] =
          Behaviors.receiveMessage: msg =>
            handleCommon(msg).getOrElse:
              msg match
                case Done(job, res) => onDone(job, res)
                case ProcessFailed(job, ex) => onFailed(job, ex)
                case Process(job) => stash.stash(Process(job)); Behaviors.same
                case StartPulling => requestMore(); Behaviors.same
                case Stop => stash.stash(Stop); Behaviors.same
                case other => stash.stash(other); Behaviors.same

        idle

  private def countLOC(filePath: os.Path): Int =
    try os.read.lines(filePath).size
    catch case _ => 0
