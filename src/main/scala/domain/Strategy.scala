package domain

/**
 * Represents an end-to-end pipeline configuration for processing files.
 */
sealed trait SourceStrategy

object SourceStrategy:
  /**
   * Represents a file-system-based source strategy for processing files.
   *
   * @param extensions a sequence of file extensions to filter the files to be processed (default is `Seq(".java")`)
   * @param recursive a flag indicating whether to traverse directories recursively. If `true`, subdirectories will be
   *   scanned; if `false`, only the top-level directory is scanned (default is `true`).
   */
  final case class FS(extensions: Seq[String] = Seq(".java"), recursive: Boolean = true) extends SourceStrategy

sealed trait ProcessStrategy

/**
 * Represents the strategy for processing files in the pipeline.
 */
object ProcessStrategy:
  /** Conta righe di codice del file (LOC). */
  case object LinesOfCode extends ProcessStrategy

  /** Hash del contenuto (dimostrativa, per estendere in futuro). */
  case object Sha256 extends ProcessStrategy

/**
 * Represents the strategy for reducing or reduction processed results in the pipeline.
 */
sealed trait ReduceStrategy

object ReduceStrategy:
  /**
   * A strategy for reducing processed results by selecting the top N items with the highest lines of code (LOC) values.
   *
   * @param n the number of top results to select, defaulting to 10.
   */
  final case class TopNByLoc(n: Int = 10) extends ReduceStrategy

  /**
   * A reduction strategy that generates a histogram distribution based on lines of code (LOC). The histogram is defined
   * by a fixed number of intervals (bins) and a maximum value (max).
   *
   * @param bins the number of bins or intervals for the histogram, defaulting to 10.
   * @param max the maximum value to be considered for the histogram, defaulting to 100.
   */
  final case class HistogramLoc(bins: Int = 10, max: Int = 100) extends ReduceStrategy
