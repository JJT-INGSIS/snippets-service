# SNI-58 — consumo recuperable y progreso

Implementa el consumidor técnico de la [ADR-001](https://github.com/JJT-INGSIS/snippet-searcher-infra/blob/dev/docs/architecture/adr-001-async-processing.md)
sobre la [base productora de SNI-57](background-outbox.md). PostgreSQL conserva
trabajos, elementos, reservas y resultados; Redis distribuye entregas repetibles.
No incorpora handlers de US12, US15, US16 ni de revalidación del parser.
El consumidor permanece desactivado por defecto.

## Recorrido y límites

Cada ciclo lee una cantidad acotada de eventos nuevos y pendientes recuperables,
valida el sobre contra el evento canónico persistido y despacha por `eventType`.
Existe un handler por tipo y un trabajo/reacción por evento, como en SNI-57.
Los duplicados de Redis no crean nuevos trabajos ni nuevos elementos: la identidad
durable es evento/trabajo y `(job_id, item_key)`, no el ID de entrega de Redis.

El trabajo se reserva en PostgreSQL con un token UUID y vencimiento. Sólo el token
vigente puede seleccionar elementos, confirmar resultados o modificar el avance.
Un heartbeat independiente renueva la reserva durante llamadas remotas. Perderlo
impide escrituras posteriores; no implica que PrintScript haya detenido su tarea.
Dos JVM comparten estas condiciones y los locks de la base, sin locks en memoria
como autoridad distribuida. El guard local solamente evita solapar ciclos propios.

Cada turno admite una página de selección y un elemento para procesar; después
cede el turno. Cursor y `selectionComplete` se confirman con los elementos.
Los elementos completados no se reclaman nuevamente después de un reinicio.
Se pueden registrar elementos `FAILED` o `SUPERSEDED` y seguir con el lote.
Un lote con selección cerrada, sin pendientes y con fallos termina `PARTIAL`.

Los reintentos de trabajo y elemento usan espera exponencial, jitter y tope;
el límite predeterminado es cinco intentos. Los motivos persistidos son códigos,
sin mensajes arbitrarios que puedan contener código, inputs o credenciales.
Los vencimientos y fechas de admisión usan el reloj de PostgreSQL; `occurredAt`
describe el hecho y no programa su disponibilidad.

Sin handler registrado, se conserva el mensaje pendiente, se difiere la admisión
cinco minutos y se registra `HANDLER_UNAVAILABLE`, sin consumir intentos,
reservar capacidad ni hacer ACK. El handler no se ejecuta en un bucle de reintentos.
La inspección periódica de pendientes sigue acotada por el tamaño del ciclo.
Los handlers controlados existen sólo en pruebas; activar el worker no crea
funcionalidad de negocio que todavía no está implementada.

## Persistencia y ACK

El resultado y avance deben confirmarse antes del ACK explícito. Si se cae entre
commit y ACK, el siguiente consumidor encuentra el trabajo terminal y confirma la
entrega sin repetir efectos. Si falla PostgreSQL, no se fabrica un ACK.

Hay dos condiciones para confirmar una entrega válida:

- Trabajo terminal: `COMPLETED`, `PARTIAL`, `FAILED` o `SUPERSEDED`.
- `UNKNOWN` con obligación de reconciliación persistida: ciclos posteriores la
  recuperan desde PostgreSQL aunque la entrega ya esté confirmada en Redis.

Un sobre inválido, demasiado grande o distinto del evento persistido se aparta en
`rejected_stream_deliveries` antes de confirmar. Se guardan stream, ID de entrega,
motivo, hash SHA-256 y tamaño; no se conserva el payload. La clave de entrega hace
idempotente este apartamiento. Si falla su persistencia, el mensaje queda pendiente.

Para los futuros handlers, `JobWork.persist` ofrece una transacción protegida por
el token: comprobar las revisiones vigentes, publicar el resultado en las tablas
de negocio y completar el elemento debe ser una sola operación PostgreSQL.
Si las revisiones de código, reglas, test o procesador ya no coinciden, completar
como `SUPERSEDED` sin publicar el resultado obsoleto. Esta base conserva revisiones
y referencias, pero la comparación funcional corresponde a cada historia.
No incluir HTTP, storage, Permissions ni adaptadores `REQUIRES_NEW` en ese callback.
Los blobs/snapshots siguen perteneciendo a las abstracciones existentes.

## Capacidad compartida y ejecuciones inciertas

`execution_capacity` contiene el presupuesto durable. Toda admisión manual y de
fondo bloquea la misma fila antes de comprobar e insertar su reserva. El inicio
remoto ocurre después del commit. El presupuesto inicial es cuatro operaciones,
con reserva de al menos un lugar para ejecuciones manuales y como máximo una
operación pesada de fondo. Si las manuales ocupan todo el presupuesto de fondo,
el worker debe ceder el turno sin iniciar la llamada.

Las reservas de `execution_permits` tienen otro ciclo de vida que el lease del
trabajo: **no vencen por tiempo**. Un inicio incierto conserva el cupo, incluso si
el worker se reinicia o pierde el token. Antes de otra ejecución, el handler debe
reconciliar la reserva existente. El reconciliador por defecto devuelve `UNKNOWN`;
nunca vuelve a iniciar la operación. Un ACK de `UNKNOWN` no libera capacidad.

`beginRemote` reserva antes del envío; `bindRemote` conserva ID, procesador y
límite efectivo cuando existe una sesión identificable. El límite declarado por
la sesión debe coincidir con el presupuesto configurado; de lo contrario se
conserva la reserva y falla la asociación. Los futuros handlers deben comprobar
también las capacidades efectivas del endpoint que utilicen antes de activarse.
Sólo una respuesta que prueba el rechazo de inicio permite `capacity.rejected`.
Un timeout, un 404 en otra instancia o una respuesta perdida no prueban rechazo.
El resultado remoto confirmado y la liberación de cupo se persisten juntos.

Las ejecuciones manuales conservan sus contratos HTTP/NDJSON, límite por actor y
auditoría. La falta del cupo global usa el rechazo de capacidad existente.
Un cierre de HTTP no libera cupo. Si una sesión conocida desaparece o responde
otro build de PrintScript, permanece reservada hasta reconciliarse; un estado
terminal confirmado sí permite cerrar la auditoría aunque falte el historial.

### Recuperación operativa de UNKNOWN

1. Consultar la reserva y la auditoría/trabajo por sus IDs. Si se conoce la sesión,
   comprobar su estado en el mismo procesador; recuperar y persistir el resultado
   terminal mediante el handler o la recuperación manual existente.
2. Si no hay ID o no puede acreditarse el estado remoto, detener nuevas admisiones
   e impedir que el coordinador original pueda enviar un inicio demorado, retirando
   sus procesos y drenando/bloqueando los requests pendientes. Después comprobar que
   la ejecución original terminó. Cuando sea necesario, detener los procesos
   PrintScript que podrían alojarla y verificar su terminación con el procedimiento
   operativo de SNI-56. Detener sólo PrintScript mientras un emisor viejo todavía
   puede enviar el inicio no alcanza. Ni el vencimiento ni la ausencia de una sesión
   en un reemplazo sirven como evidencia suficiente.
3. Registrar evidencia verificable de la intervención. En un contexto Spring
   interno, `ExecutionCapacity.resolve(lease, permitId, evidence)` exige el token
   actual y prueba de terminación: marca el elemento fallido y libera el cupo
   atómicamente. Cerrar después el turno mediante `jobs.finish(lease, JobStep.Complete)`
   con el mismo token vigente: termina el lote o lo devuelve a `PENDING` si queda
   selección/trabajo. Desde un handler, devolver ese paso cumple la misma función.
   Para una ejecución manual existe `ResolveManualExecution.resolve`.
   La comprobación externa ocurre fuera de la transacción y se revalida la
   identidad de la reserva antes de modificarla.
4. Reanudar el procesamiento; el resto del lote puede continuar. Sin prueba no se
   libera el cupo. Estas son capacidades internas probadas, sin endpoint ni CLI
   administrativo público. Los handlers reales aportarán su reconciliación.

Esta intervención deliberada evita reservas sin procedimiento de recuperación;
no promete resolver automáticamente una ejecución remota imposible de observar.

### Activación y reemplazos de instancias

La activación durable es monotónica: cuando una instancia habilita el control,
las otras instancias de esta versión lo respetan aunque su flag local sea falso.
Las reservas de auditoría existentes se adoptan conservadoramente. Distintas JVM
de esta versión no pueden exceder el presupuesto compartido durante un reemplazo.

Antes de habilitar workers en un ambiente, SNI-56 debe retirar coordinadores
anteriores a V7, drenar/reconciliar ejecuciones y comprobar el límite efectivo de
PrintScript. Una versión antigua desconoce este control y no puede participar
de la garantía. Redis mantiene la topología de una instancia acordada.
Cambiar el presupuesto durable requiere pausar admisiones, drenar y actualizar
la fila/configuración coherentemente; no se resetea al arrancar una JVM.
No activar capacidades ni aplicar estos pasos a un ambiente como parte del ticket.

## Tres mecanismos de entrega y recuperación

| Mecanismo | Responsabilidad |
| --- | --- |
| `XGROUP CREATE ... 0-0 MKSTREAM` | Inicializar desde el historial retenido. Sólo se tolera `BUSYGROUP`; nunca se resetea el cursor existente. |
| `XREADGROUP ... >` | Obtener eventos nuevos para el grupo. |
| `XPENDING` / `XAUTOCLAIM` | Recuperar entregas pendientes después del tiempo mínimo de inactividad, con cursor y cantidad acotados. |
| `OutboxRecovery.requeueBatch` | Reconstruir entregas desde PostgreSQL si Redis pierde información; no sustituye el reclaim normal. |

El adaptador utiliza la conexión Lettuce ya configurada para `XAUTOCLAIM`, que no
está expuesto por `StreamOperations` de la versión utilizada. No agrega cliente,
dependencia, conexión paralela ni auto-ACK. Se requiere Redis 6.2 o posterior;
las pruebas usan Redis 7.4.7. Una reconstrucción conserva IDs de dominio, cursor
y progreso, y sigue obligando a reconciliar cualquier ejecución incierta.
No hay trimming, purga automática, `KEYS`, `FLUSHDB` ni promesa de exactly-once.

## Consulta de progreso y autorización

`GET /jobs/{jobId}` devuelve exclusivamente:

```json
{
  "id": "b0b07fba-664a-4106-88cf-9e953c64a186",
  "status": "RUNNING",
  "processed": 30,
  "failed": 2,
  "pending": 68,
  "selectionComplete": true
}
```

`processed` cuenta completados y descartados por obsolescencia; `failed` cuenta
elementos fallidos; `pending` incluye pendientes, en ejecución, reintento e
inciertos. Mientras `selectionComplete=false`, sólo se cuentan elementos ya
descubiertos y no se afirma conocer el total definitivo. El estado del trabajo
es independiente: un fallo global puede dejar elementos pendientes sin ejecutar.

| Ámbito | Visibilidad actual |
| --- | --- |
| Reglas personales de formato/lint | Actor identificado igual a `scope.ownerId`. |
| Tests de un snippet | Snippet confirmado y ownership vigente en Permissions, verificado en cada consulta. Un acceso compartido no basta. |
| Activación global del parser | Siempre denegada hasta implementar autorización administrativa real. |

Sin identidad: 401. ID inválido: 400. Trabajo inexistente o no autorizado: el mismo
404 sin revelar cantidades. Dependencia indisponible: 503. No se exponen scopes,
errores internos ni referencias de resultados. Los productores de cada historia
deben construir el ámbito a partir de la autorización real, nunca del request.
La UI hace polling; sólo GET se agrega a la política CORS existente.
La identidad sigue detrás de `ActorIdentityResolver`: Auth0 reemplazará su
adaptador HTTP. Los workers no reciben tokens ni `X-Dev-Actor-Id`.

## Configuración y diagnóstico

| Propiedad | Default |
| --- | --- |
| `SNIPPETS_WORKER_ENABLED` / `snippets.worker.enabled` | `false` |
| `snippets.worker.group` | `snippet-processing-v1` |
| `snippets.worker.poll-delay` | `1s` |
| `snippets.worker.batch-size` | `5` nuevos y hasta `5` recuperados por ciclo |
| `snippets.worker.item-batch-size` | `10` seleccionados; un elemento procesado por turno |
| `snippets.worker.lease` / `reclaim-idle` | `30s` / `30s` |
| `snippets.worker.missing-handler-delay` | `5m` |
| `snippets.worker.retries.attempts` / `initial` / `maximum` | `5` / `2s` / `5m` |
| `snippets.capacity.enabled` | Hereda `snippets.worker.enabled`, inicialmente `false` |
| `snippets.capacity.active-limit` / `manual-reserve` | `4` / `1`, iguales a la fila durable |

Stream, host, autenticación, TLS y timeouts se configuran como en SNI-57.
Un stream por ambiente mantiene aislado el grupo. El scheduler de consumo es
independiente de la recuperación de ejecuciones manuales y del heartbeat.
Los logs identifican evento, trabajo, correlación y resultado del turno. Los
contadores durables mínimos se consultan sin un nuevo sistema de métricas:

```sql
SELECT state, count(*) FROM background_jobs GROUP BY state;
SELECT reason, count(*) FROM rejected_stream_deliveries GROUP BY reason;
SELECT kind, state, count(*) FROM execution_permits GROUP BY kind, state;
```

## Decisiones y costos aceptados

Juan aprobó la implementación y estos ajustes en este chat el 01/11/2026:
consumidor apagado hasta disponer de handlers, leases con token, capacidad durable
separada, `UNKNOWN` con reconciliación, admisión común y recuperación diferenciada.
Se mantiene la arquitectura y el alcance aprobados de SNI-27/SNI-58.

Se eligieron ciclos acotados frente a un listener continuo: simplifican operación
inicial y pruebas a cambio de latencia de polling y consultas periódicas.
Se descartaron auto-ACK y deduplicación sólo en memoria porque perderían progreso
o protección entre JVM. No liberar cupos por TTL evita reinicios duplicados,
a cambio de intervención cuando no puede probarse la terminación remota.
El presupuesto fijo y un solo trabajo pesado favorecen las ejecuciones manuales
a cambio de throughput. Cambiarlo exige revalidar capacidad y rollout.
No se introducen microservicios, workflows genéricos, dashboards ni gestión pública
de trabajos; los handlers y autorización administrativa quedan en sus tickets.

## Verificación reproducible

Con JDK 21 y Docker disponible directamente para Testcontainers:

```sh
./gradlew test --tests '*JobConsumerIntegrationTest' --tests '*ExecutionCapacityIntegrationTest' --tests '*JobProgressHttpTest'
./gradlew build
```

Las integraciones usan PostgreSQL/Redis reales y handlers controlados: grupo
idempotente, ausencia de handler, clocks distintos, rollback de efectos/avance,
caída entre commit y ACK, progreso parcial, claim concurrente, fencing y heartbeat,
retención/reconciliación de UNKNOWN, reconstrucción tras pérdida de Redis,
apartamiento durable, agotamiento de reintentos, cupos entre coordinadores y
autorización/DTO HTTP. No requieren ni simulan una implementación de US12/15/16.
`WorkerSchedulingTest` comprueba el arranque desactivado sin Redis y que una tarea
de fondo bloqueada no retrase las tareas de recuperación manual sin scheduler
explícito; se prueban los schedulers reales del contexto Spring.
Los tests live existentes conservan sus requisitos de servicios externos.
No se montan sockets del host en un runner; las pruebas locales se ejecutan
directamente desde Java en Windows, según la autorización vigente de Juan.

En este equipo, el error previo de Gradle `Unable to establish loopback connection`
se evita con un directorio temporal corto para el proceso Java. Con JDK 21 en
`JAVA_HOME`, desde el repositorio y en una sesión nueva de PowerShell:

```powershell
$taskJavaTemp = 'C:\dev\ingsis\snippet-searcher\.jtmp'
New-Item -ItemType Directory -Path $taskJavaTemp -Force | Out-Null
$env:TEMP = $taskJavaTemp
$env:TMP = $taskJavaTemp
$env:JAVA_TOOL_OPTIONS += " -Djdk.net.unixdomain.tmpdir=$taskJavaTemp"
.\gradlew.bat build
```

Estas variables sólo afectan esa sesión y sus procesos hijos; no se modifican
hooks, checks ni configuración global. En Linux/CI se utiliza el comando normal.
