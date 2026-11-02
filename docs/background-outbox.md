# SNI-57 — trabajos durables y publicación por outbox

Implementa la base productora de la [ADR-001](https://github.com/JJT-INGSIS/snippet-searcher-infra/blob/dev/docs/architecture/adr-001-async-processing.md).
Los eventos de negocio se conectan en SNI-61/63/68/70; consumidores y resultados
pertenecen a SNI-58 y las historias. No hay nuevos endpoints ni emisiones desde
POST/PUT de snippets en este ticket. No se modifica NDJSON ni Permissions/storage.

## Escritura explícita y atomicidad

`AtomicJobWrite.commit` abre una transacción local `REQUIRES_NEW`. Su operación
realiza exclusivamente cambios PostgreSQL confirmables y devuelve
`ConfirmedJobChange(valor, trabajo)`. Registra trabajo y outbox antes del commit.
Los rechazos salen antes de entrar al límite o mediante excepción que revierta;
no devolver ConfirmedJobChange para Missing/Pending/Denied. No incluir llamadas
a storage, Permissions ni Redis dentro de esta operación.

`JdbcSnippetContentLinks.replaceContent` utiliza `MetadataTransaction.join`
(`REQUIRED`): sin transacción previa confirma su propia transacción; dentro de
esta unidad participa del mismo commit que el outbox. No llamar desde la unidad
a adaptadores que abran `REQUIRES_NEW`: confirmarían por separado. Las escrituras
futuras de reglas y activaciones deberán participar mediante JDBC/`join` también.
`JdbcJobRecords` exige transacción existente (`MANDATORY`). No se usan listeners.

El productor proporciona las revisiones efectivas confirmadas: código/referencia
inmutable, configuración, definición de test y procesador, según el hecho.
Guardar un identificador no conserva el blob; su retención pertenece a SNI-68.
No se inventa una revisión a partir de la versión declarada del lenguaje.

Las tablas son `background_jobs`, `background_job_items`, `event_outbox`.
La auditoría HTTP de ejecuciones existente sigue siendo autoridad de sesiones;
estos trabajos existen antes de abrir una sesión. Los items guardan referencias
a snapshots/resultados, sin duplicar su almacenamiento autoritativo. Estados,
cursor de selección y reservas del worker se usarán en SNI-58: aquí no hay motor
de ejecución ni actualización de resultados.

## Contrato del stream

Un stream conocido por ambiente. Cada entrada tiene un campo string `event`
con JSON de DomainEvent v1: eventId, jobId, eventType, schemaVersion,
correlationId, occurredAt, scope y revisions. Sin serialización Java ni `_class`.

Tipos: FormattingRulesChanged, LintRulesChanged, ParserVersionActivated y
SnippetUpdated. Las claves de scope/revisions se concretan con cada historia.
Owner/actor sirve para auditoría, nunca como permiso. No enviar código, tokens,
inputs ni resultados. El sobre está limitado a 16 KiB UTF-8: excederlo revierte
la transacción. IDs y contenido se conservan al republicar; el ID Redis corresponde
a una entrega diferente. Dos updates reales siguen siendo dos hechos distintos.

Inicialmente hay un trabajo/reacción por evento y outbox único por evento/trabajo.
No se agregan consumidores independientes hipotéticos ni un framework de eventos.

## Publicación y configuración

El publicador reclama un evento a la vez hasta el límite del lote por ciclo,
usando `FOR UPDATE SKIP LOCKED`, reloj PostgreSQL y UUID de posesión. Redis se
invoca sin locks ni transacción de BD. Registrar publicación o reintento exige
token vigente: un publicador viejo no libera la reserva del siguiente.

XADD aceptado precede a la marca de publicación. Un fallo posterior o respuesta
perdida puede duplicar entregas. El trabajo sigue PENDING: publicado no significa
procesado. La garantía es at-least-once; la deduplicación del consumidor es SNI-58.

Los fallos Redis, incluido OOM con noeviction, conservan el evento y usan espera
exponencial acotada con jitter. No se abandona por agotar intentos de publicación.
Errores BD abortan el ciclo; el scheduler reporta el fallo y los ciclos posteriores
recuperan reservas vencidas. Los logs de publicación incluyen eventId, jobId,
correlationId, intento y resultado; no payloads ni excepciones con credenciales.

| Configuración | Default |
| --- | --- |
| SNIPPETS_OUTBOX_ENABLED / snippets.outbox.enabled | false |
| SNIPPETS_OUTBOX_STREAM / snippets.outbox.stream | snippet-searcher:local:events |
| snippets.outbox.poll-delay | 2s |
| snippets.outbox.batch-size | 10; máximo 100 |
| snippets.outbox.lease | 30s |
| snippets.outbox.retry-delay | 2s |
| snippets.outbox.max-retry-delay | 5m |
| spring.data.redis.connect-timeout / timeout | 2s / 2s |

Host, puerto, autenticación y TLS usan `spring.data.redis.*` de Spring Boot y
secretos externos. Sobrescribir el nombre del stream por ambiente. Mantener lease
mayor al presupuesto de conexión, comando y escritura BD. Un vencimiento prematuro
admite duplicados, no pérdida. Hay un solo ciclo activo por instancia de publicador.
La activación y rollout de una sola instancia se coordinan con SNI-56/58.

Se revisó el productor MVC de la cátedra en
`austral-ingsis/class-redis-streams@c22b911154ec18f438eec96d16312a30878cc177`.
Delega en Spring Data `opsForStream().add`: se usa esa API con el starter del Boot
4.1.1 existente, sin importar el helper con dependencias abiertas `+` y componentes
reactivos. No se copia el auto-ACK del consumidor didáctico.

## Reconstrucción y retención

No hay purga automática ni trimming. No se usan KEYS, SCAN, MAXLEN ni FLUSHDB
en el código productivo. Conservar el outbox permite recuperar publicaciones.

Después de reconstruir Redis, un operador debe:

1. Pausar publicadores/consumidores y respetar las reservas remotas aún vigentes.
2. Invocar `OutboxRecovery.requeueBatch(limite)` (1–100) en un contexto Spring
   interno conectado a PostgreSQL. Es una capacidad interna probada, sin endpoint
   ni comando administrativo público agregado en este ticket.
3. Repetir por lotes hasta retornar 0. Sólo reencola publicados de trabajos
   incompletos, debidos y sin reserva vigente. Los pendientes permanecen intactos.
   Diferidos/reservados requieren otra pasada cuando queden elegibles.
4. Verificar/recrear Consumer Group desde historial retenido (SNI-58) y reanudar.
   El consumidor deduplica por evento/reacción.

UNKNOWN no es terminal. SNI-58 debe reconciliar intentos: republicar nunca autoriza
reiniciar una ejecución incierta en PrintScript. El outbox incompleto no vence.
Antes de activar en ambientes, acordar retención de terminales y verificar limpieza
coordinada con resultados, snapshots y todos los grupos activos. ACK no autoriza
borrar blobs. Conservar todo consume espacio; monitorear backlog y capacidad.

SNI-56 configura red interna, secretos, volumen, AOF y memoria con margen y
noeviction. No se agregan nuevas VMs, Sentinel ni Cluster. Persistencia Redis no
elimina toda ventana de pérdida; PostgreSQL reconstruye entregas pendientes.

## Verificación

AtomicJobWriteTest prueba commit/rollback del reemplazo real, fallo de outbox,
transacción obligatoria e identidad de items. OutboxDeliveryTest usa PostgreSQL y
Redis 7.4.7 reales con Testcontainers para verificar publicación, Redis pausado,
respuesta perdida, fallo después de XADD, reservas vencidas, rechazo por memoria
y reconstrucción selectiva. Ejecutar con JDK 21 y Docker:

```sh
./gradlew build
```

Juan autorizó implementar el plan en este chat el 01/11/2026. No implica activar
workers, desplegar ni promover a producción. La evidencia de ejecución se registra
al terminar las verificaciones; la existencia de pruebas no acredita que pasaron.

### Integración pendiente y entorno seguro propuesto

Verificación local del 01/11/2026: compilación de producción y tests, ktlint,
Detekt de producción/tests y assemble completados. La selección JVM sin Docker
ejecutó **258 pruebas en 58 clases, 0 fallos, 0 errores y 0 skips**, incluyendo
DomainEventTest (2) y OutboxPublisherTest (3), y siete contextos Spring con H2.
Se seleccionaron clases sin dependencias de Testcontainers ni servicios live
externos; el filtro no modifica el test task por defecto ni el CI completo.
El manifiesto local está en `build/sni57-nondocker-selection.txt`, los XML en
`build/test-results/test/` y el log en `build/sni57-nondocker-verification.log`.
Este resultado es una **verificación parcial**, no `gradlew build` completo.
Tras ajustar la prueba de Redis pausado para admitir una escritura aceptada cuya
respuesta venció, se repitieron `ktlintCheck compileTestKotlin detektTest` con
resultado exitoso. Log: `build/sni57-final-static-verification.log`. Ese ajuste
afecta sólo una integración pendiente; no cambia las 258 pruebas seleccionadas.

El 01/11 Juan indicó expresamente no montar el socket Docker del equipo en el
runner Linux. Gradle nativo Windows falla antes de compilar con
`Unable to establish loopback connection`. El runner local autorizado no recibe
socket ni credenciales: sirve para compilar, analizar y ejecutar tests sin Docker.

Quedan pendientes las **14 pruebas** nuevas de Testcontainers:

| Clase | Pruebas | Qué falta verificar realmente |
| --- | --- | --- |
| AtomicJobWriteTest | 7 | Commit, rollback real, transacción obligatoria, visibilidad antes de commit, límite de tamaño y unicidad de items |
| OutboxDeliveryTest | 7 | Publicación, Redis caído, respuesta perdida, fallo posterior a XADD, reservas, OOM y reconstrucción |

Propuesta: ejecutar en una VM efímera de GitHub Actions dedicada al job, usando
JDK 21 directamente sobre esa VM y su Docker propio. No montar el socket del
equipo local ni reutilizar un runner self-hosted que tenga acceso a las VMs del
proyecto. El CI compartido existente `kotlin-ci.yml` ya utiliza `ubuntu-24.04`,
`setup-java` y `gradlew build` directamente, sin runner Gradle en contenedor con
socket montado. La rama debe pasar por PR/CI; no se solicita despliegue.

Para diagnosticar primero las nuevas integraciones, en ese entorno aislado:

```sh
./gradlew test --tests '*AtomicJobWriteTest' --tests '*OutboxDeliveryTest' --no-daemon
./gradlew build --no-daemon
```

Revisar los XML de `build/test-results/test/` y conservar reportes/logs del job.
Las 14 pruebas deben ejecutar sin fallos ni skips; luego debe pasar el build
completo. Un filtro local sin Docker o un assemble exitoso no satisface ese cierre.
Las integraciones anteriores que necesitan Docker también quedan fuera de la
verificación local filtrada. No declarar SNI-57 completamente verificado ni cerrar
el ticket hasta obtener esa evidencia.
