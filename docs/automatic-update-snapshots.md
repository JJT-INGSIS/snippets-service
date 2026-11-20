# SNI-68 — snapshots de cada actualización

Cada PUT confirmado registra, en el mismo commit PostgreSQL, el reemplazo,
una revisión de update distinta, el test-set ordenado con hashes, un trabajo raíz
y SnippetUpdated/outbox. Usa la transacción real de MetadataTransaction y los
registros de SNI-57. Un rollback no deja intención publicable. No hay HTTP ni
storage dentro de la transacción; la escritura de contenido ocurre antes y el
tratamiento de commit incierto conserva su referencia, como antes.

PrepareUpdate conserva el actor ya autorizado en UpdateCandidate. El evento
contiene IDs/revisiones, nunca código, inputs ni credenciales. El productor no
ejecuta programas, no emite lint/formato y no altera la respuesta pública de PUT.
El handler US16 de SNI-69 procesa el conjunto, incluido el conjunto vacío.

La fila del snippet bloquea tanto el reemplazo como la creación de tests existente:
el snapshot fija las definiciones presentes en ese punto. Dos PUT confirmados,
aunque tengan el mismo texto, generan revisiones/trabajos distintos; reentregar
un evento conserva su ID y usa la deduplicación de SNI-58.

## Retención y recuperación

El código sigue en el blob inmutable original; no se introduce otro almacén de
código autoritativo. Cada snapshot mantiene su referencia mientras el trabajo esté
pendiente, en ejecución, reintentando o UNKNOWN. La auditoría de las ejecuciones
seguirá reutilizando los modelos de SNI-48 al ejecutar, no al producir el evento.

La liberación es explícita e idempotente. Sólo admite un terminal durable
COMPLETED/PARTIAL/FAILED/SUPERSEDED sin permisos remotos retenidos. La recuperación
reintenta liberaciones de terminales y limpieza en lotes de 100, cada 30 segundos
(`execution.retention.delay-ms`). No purga auditoría ni definiciones históricas.

Antes de borrar se bloquea el registro de retención y se comprueba que ninguna
referencia activa o snapshot retenido lo usa. Un tombstone DELETING impide que una
escritura vuelva a enlazar ese ID. El DELETE ocurre fuera de la transacción; su
resultado se confirma con token de posesión. Si falla storage o se pierde la
confirmación, queda recuperable y se repite el DELETE idempotente. Un intento viejo
no puede confirmar por otro token ni resucitar el blob. Los adaptadores existentes
generan IDs nuevos para cada store; una referencia tombstoned nunca se reutiliza.

El costo aceptado de la trazabilidad/recuperación es almacenamiento mientras haya
trabajos pendientes y coordinación adicional. La alternativa de borrar el blob al
reemplazarlo pierde el código de un update cuyo worker todavía no empezó. Esta
aplicación sigue la ADR-001 y la prioridad de historial elegida por Thiago; el marco
para explicitar el compromiso es el capítulo 2 del
[índice verificado de Fundamentals of Software Architecture, 2.ª ed.](https://www.oreilly.com/library/view/fundamentals-of-software/9781098175504/).
El libro no prescribe estas tablas ni este endpoint.

## Verificación

UpdateSnapshotsIntegrationTest usa PostgreSQL y storage reales para dos updates,
definiciones exactas, rollback conjunto, referencias compartidas, terminales,
UNKNOWN, recreación del lector, fallo/reintento de limpieza y conjunto vacío.
Los tests de flujo/storage mantienen sus inyecciones de fallo; los HTTP y la
concurrencia existentes ejercitan el commit productivo. Ejecutar ./gradlew build
con JDK 21. No aplicar migraciones en Azure ni activar workers por este documento.

Verificado el 02/11/2026: build completo con JDK 21, ktlint/detekt y 489 tests
(475 ejecutados sin fallos; 14 integraciones opt-in de otros proveedores omitidas).
Los 7 tests de UpdateSnapshotsIntegrationTest se repitieron además contra el Asset
Service oficial y Azurite reales, con PostgreSQL de Testcontainers: todos pasaron.
El puerto de API de Asset es 8080; 9090 es su management/health. Se publicó la API
sólo en localhost para esa prueba y se usó un proyecto Compose aislado.
