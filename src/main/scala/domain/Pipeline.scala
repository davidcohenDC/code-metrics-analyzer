package domain

/**
 * Represents an end-to-end pipeline configuration for processing files.
 *
 * @param source the strategy used for sourcing input data.
 * @param process the strategy applied to process each input file.
 * @param reduce the strategy for aggregating or reducing the processed results.
 */
final case class Pipeline(
    source: SourceStrategy,
    process: ProcessStrategy,
    reduce: ReduceStrategy,
)
