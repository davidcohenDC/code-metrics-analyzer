package domain

import java.util.UUID

/**
 * Represents the result of a job execution, encapsulating the job identifier, the data produced by the execution, and
 * any associated metadata.
 *
 * @param jobId the unique identifier of the job whose result is being represented
 * @param data the result data produced from the job execution
 * @param meta optional metadata associated with the result, defaulting to an empty map
 */
final case class Result(
    jobId: UUID,
    data: ResultData,
    meta: Map[String, String] = Map.empty,
)

sealed trait ResultData

object ResultData:

  /**
   * Represents the lines of code result for a specific file path.
   *
   * @param path the file path for which the lines of code are reported
   * @param loc the number of lines of code in the specified file
   */
  final case class LinesOfCode(path: os.Path, loc: Int) extends ResultData

  /**
   * Represents the SHA-256 hash result for a specific file path.
   *
   * @param path the file path for which the SHA-256 hash is reported
   * @param hex the hexadecimal representation of the SHA-256 hash
   */
  final case class Sha256(path: os.Path, hex: String) extends ResultData
