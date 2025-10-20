package domain

import java.util.UUID

/**
 * Unità di lavoro end-to-end.
 * @param id correlazione e dedup
 * @param file risorsa da processare
 * @param attempts numero di tentativi già effettuati
 */
final case class Job(
    id: UUID,
    file: os.Path,
    attempts: Int = 0,
)

object syntax:
  extension (j: Job) def jid: String = j.id.toString.take(8)

  extension (id: UUID) inline def jid: String = id.toString.take(8)
