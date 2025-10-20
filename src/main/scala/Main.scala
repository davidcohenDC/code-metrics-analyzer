import actors.*
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import domain.*
import org.slf4j.LoggerFactory
import utils.MyUtils

object Main:

  private object Constants:
    val DefaultSystemName = "CodeMetricsSystem"
    val ReducerName = "reducer"
    val QueueName = "queue"
    val SourceName = "source"
    val ProcessorName = "processor"
    val SupervisorName = "supervisor-actor"
    val UiBridgeName = "ui-bridge"

  @main def runMain(): Unit =
    import Constants.*

    val raw = com.typesafe.config.ConfigFactory.load()
    val desired = raw.getString("source.log-level").toUpperCase()

    val target = LoggerFactory.getLogger("actors") match
      case l: Logger => l
      case other =>
        throw new IllegalStateException(s"Logger 'actors' is not a ch.qos.logback.classic.Logger: ${other.getClass}")
    target.setLevel(Level.valueOf(desired))

    val system = ActorSystem(
      Behaviors.setup[Nothing] { ctx =>
        val config = ctx.system.settings.config
        val parallelism = MyUtils.Parallelism.getParallelism(config)
        ctx.log.info(s"[BOOT] Starting Analyzer with parallelism = $parallelism")

        val pipeline = Pipeline(
          source = SourceStrategy.FS(extensions = Seq(".java")),
          process = ProcessStrategy.LinesOfCode,
          reduce = ReduceStrategy.TopNByLoc(),
        )

        val uiBridge = ctx.spawn(UiBridgeActor(), UiBridgeName)
        val reducer = ctx.spawn(ReducerActor(uiBridge, pipeline.reduce), ReducerName)
        val queue = ctx.spawn(QueueActor(), QueueName)
        val crawler = ctx.spawn(SourceActor(queue, pipeline.source), SourceName)

        val processors =
          (1 to parallelism).map { i =>
            ctx.spawn(
              ProcessorActor(
                id = s"$ProcessorName-$i",
                queue = queue,
                reducer = reducer,
                processStrategy = pipeline.process,
              ),
              s"$ProcessorName-$i",
            )
          }

        val supervisor = ctx.spawn(
          SupervisorActor(
            crawler = crawler,
            queue = queue,
            reducer = reducer,
            processors = processors,
            uiBridge = uiBridge,
          ),
          SupervisorName,
        )

        queue ! QueueActor.Register(supervisor)

        new gui.AnalyzerGUI(
          supervisorRef = supervisor,
          uiBridgeRef = uiBridge,
          system = ctx.system,
        ).initGUI()
        Behaviors.empty
      },
      DefaultSystemName,
    )
