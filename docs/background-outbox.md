# Outbox y entrega de eventos

La misma transacción PostgreSQL confirma el cambio, el trabajo y la entrega del outbox. Los eventos son `FormattingRulesChanged`, `LintRulesChanged`, `ParserVersionActivated` y `SnippetUpdated`.

Cada entrada del Redis Stream tiene un campo `event` con el JSON de `DomainEvent` v1: `eventId`, `jobId`, `eventType`, `schemaVersion`, `correlationId`, `occurredAt`, `scope` y `revisions`. Límite de 16 KiB en UTF-8. El sobre lleva identificadores y revisiones. El código y los datos de ejecución quedan en persistencia interna.

El publicador reclama cada entrega con un lease y un token de posesión. Escribe en Redis fuera de la transacción. Marca publicada al confirmar `XADD`; una respuesta perdida puede producir otra entrega con el mismo `eventId`. Ante una falla de Redis programa un reintento con espera exponencial acotada y jitter. El consumidor deduplica por evento y reacción. [Consumo y recuperación](background-consumer.md)

Configurá `SNIPPETS_OUTBOX_ENABLED=true` y un `SNIPPETS_OUTBOX_STREAM` exclusivo por ambiente. Redis usa `spring.data.redis.*`. El outbox queda en PostgreSQL para permitir la reconstrucción. Tras una pérdida de Redis, pausá publicación y consumo, reconciliá las reservas remotas y usá la capacidad interna `OutboxRecovery.requeueBatch(límite)` para reencolar entregas publicadas elegibles de trabajos incompletos. Reencolá por lotes de 1 a 100 hasta que no queden entregas elegibles. Recreá el grupo desde el inicio del stream antes de reanudar. La recuperación no habilita duplicar operaciones remotas inciertas.
