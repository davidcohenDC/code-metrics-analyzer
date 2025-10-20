package utils

/**
 * Utility object for handling parallelism configuration.
 */
object MyUtils:

  /**
   * Utility object for determining the level of parallelism based on configuration.
   */
  object Parallelism:

    /**
     * Determines the level of parallelism based on the provided configuration. If the configured number of processors
     * is less than or equal to zero, it defaults to twice the number of available CPU cores.
     *
     * @param config The configuration object containing the parallelism settings.
     * @return The determined level of parallelism.
     */
    def getParallelism(config: com.typesafe.config.Config): Int =
      val configuredProcessors =
        if config.hasPath("source.processors") then scala.util.Try(config.getInt("source.processors")).getOrElse(0)
        else 0
      if configuredProcessors <= 0 then Runtime.getRuntime.availableProcessors() * 2
      else configuredProcessors
