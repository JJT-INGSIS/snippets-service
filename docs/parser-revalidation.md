# SNI-70 — Referencia del parser y revalidación

## Alcance y decisiones

El 02/11/2026 Juan aprobó activar explícitamente una referencia por lenguaje y
versión, registrar `ParserVersionActivated` y revalidar en segundo plano con la
base de SNI-57/58. Activar **no despliega ni cambia físicamente PrintScript**.
Un deploy, un arranque o un cambio del jar no generan este evento.

Se comprueba `GET /processor`, específicamente `capabilities.validate`. Una
validación vacía acredita también la versión soportada y su propio
`Processor-Revision` antes de confirmar. Cada elemento del lote debe acreditar
otra vez su propia respuesta; la consulta inicial no prueba quién atendió después.
El header de ejecutar, formato o lint no identifica el parser.

Juan ajustó el plan: `/validate` es análisis sin efectos de negocio, no una sesión
US9/US16. Un timeout permite reintentos acotados del mismo snapshot. Ante la
consulta de diseño, eligió **admisión temporal en Snippets, sin ampliar PrintScript**.
No se crean sesiones consultables, infraestructura adicional ni protocolos nuevos
de ejecución remota. Tampoco se modifica la biblioteca del TP.

La alternativa de limitar las validaciones activas dentro de PrintScript controla
mejor el trabajo remoto real, pero amplía el proveedor. La opción elegida limita
admisiones de Snippets: un timeout o el vencimiento de la admisión **no prueban que
el análisis remoto terminó**. Se acepta ese límite para análisis idempotentes y
se mantienen backoff, intentos finitos y protección de resultados.

## Activación y auditoría

Una solicitud tiene UUID idempotente, lenguaje, versión, revisión exacta y motivo.
Una transacción PostgreSQL serializa la solicitud y el ámbito, e inserta:

- Referencia activa e historial de activaciones con identidad operativa y motivo.
- Trabajo durable y evento/outbox con IDs de evento, trabajo y correlación.

El mismo UUID con los mismos parámetros devuelve su trabajo original, incluso si
otra activación lo reemplazó. Reutilizarlo con otros parámetros se rechaza. Un UUID
nuevo expresa una activación nueva deliberada, aunque la revisión sea la misma:
su identidad distinta impide que un lote anterior publique por coincidencia del hash.
La selección opera por lenguaje/versión; para varias versiones se ejecuta una
activación explícita por versión.

La revisión activa es una referencia de vigencia, no una instrucción de routing.
Si PrintScript cambia después de comprobarla, el lote no acepta respuestas de otro
build. Se necesita otra activación explícita o recuperar el proveedor esperado.

## Comando interno

El mismo jar de Snippets admite `parser-admin activate` y `parser-admin status`.
No hay endpoint administrativo público, token compartido nuevo ni acceso directo
del operador a tablas/Redis. El comando invoca `ActivateParser` y las capacidades
de progreso de Snippets.

Se exige Linux y UID 0, adquirido mediante acceso operativo autorizado por SSH/sudo
o permisos del daemon/contenedor. La identidad se deriva del sistema operativo:
`os:<usuario>:uid=<uid>`. No se acepta `--operator`, headers dev ni un actor de negocio
inventado. Si la cuenta/acceso root es compartido, este registro no identifica a
la persona: debe correlacionarse con la auditoría de acceso del host.

La autenticación y los argumentos se comprueban antes de iniciar Spring. El contexto
no abre servidor HTTP, no ejecuta Flyway, deshabilita publicador/worker/capacidad
local y retira el procesador de tareas programadas. No ejecuta tareas de sesiones,
limpieza o publicación como efecto secundario de la administración. El esquema
debe estar previamente migrado por el despliegue revisado de la aplicación.

Ejemplo ilustrativo, **requiere aprobación operativa antes de usarlo en un ambiente**:

```sh
docker exec --user 0 <contenedor-snippets> java -Xmx192m -jar /app/app.jar parser-admin activate \
  --request-id <uuid-nuevo> --language printscript --version 1.1 \
  --processor sha256-v1:<64-hex> --reason 'Corrección revisada del parser'

docker exec --user 0 <contenedor-snippets> java -Xmx192m -jar /app/app.jar parser-admin status \
  --job-id <uuid-del-trabajo>
```

Se usa la configuración del contenedor, incluyendo configtree. El comando inicia
una JVM adicional corta: reservar memoria dentro del límite del contenedor antes
de ejecutarlo; `-Xmx` no incluye todo el consumo nativo/metaspace. No aumenta
automáticamente los recursos ni crea un servicio permanente. Emite resultado y
tipo de outcome en el log y termina con error si no confirma la activación.

Los trabajos globales siguen ocultos por `GET /jobs/{jobId}` para usuarios normales.
`status` exige la misma autorización operativa y reutiliza `JobProgress`, incluyendo
`selectionComplete`. El futuro Auth0 podrá autorizar el mismo caso de uso mediante
una capacidad administrativa real; no debe pasar tokens a los workers.

## Selección, snapshots y resultados

Se seleccionan snippets CONFIRMED de todos los owners del ámbito, mediante cursor
UUID persistido y orden estable, sin OFFSET ni listar owners desde Permissions.
Los elementos se descubren por páginas; no se promete una foto MVCC global del
instante de activación. Una creación concurrente puede entrar si está después del
cursor, y puede quedar fuera si está detrás o el lote cerró la selección. La
creación/actualización conserva su validación síncrona normal.

La misma transacción de selección fija la referencia de contenido y la retiene
antes de publicar los elementos. Un retry lee siempre esa referencia. Cambios de
código, lenguaje/versión o activación invalidan el resultado anterior: no se sustituye
el snapshot silenciosamente ni se sobrescribe una revisión más nueva. La limpieza
existente respeta esas referencias mientras el trabajo no sea terminal.

Una validación inválida es un elemento completado con `valid=false` y diagnósticos;
no una falla técnica. Se guardan revisión de contenido, activación y procesador.
La publicación verifica y bloquea ámbito/contenido dentro del commit protegido
por el lease, junto con el avance del elemento. No modifica contenido, lenguaje,
ownership, sharing, formato, lint ni tests. Un resultado obsoleto se descarta como
SUPERSEDED. Los resultados se persisten para consulta interna; no se agrega un
endpoint público de diagnósticos en este ticket.

## Admisión y recuperación

`parser_analysis_admission` ocupa como máximo una admisión de fondo y participa
del mismo presupuesto PostgreSQL que las sesiones manuales/background. Las
admisiones manuales reservan su margen y ningún otro fondo puede admitir trabajo
mientras el análisis ocupa su lugar. La admisión usa un token de posesión y vence
tras `2 × (connectTimeout + readTimeout) + 5s`. Es horizonte de admisión, no SLA ni
prueba de terminación remota. Un holder viejo no libera una admisión nueva.

El permiso se libera al terminar el intento local, incluso ante timeout. Si cae
el proceso, el vencimiento permite continuar. No se crea una reserva de sesión ni
UNKNOWN por análisis. `execution_permits` mantiene intacta su semántica sin TTL
para US9/US16. Falta de capacidad difiere el elemento sin consumir intentos.

Timeouts, storage no disponible y respuesta sin revisión esperada se reintentan
con la política de elementos de SNI-58 (por defecto cinco intentos y backoff).
Agotarlos falla ese elemento y permite avance parcial del resto. Un worker que
pierde el lease no puede guardar resultados, aunque después reciba una respuesta.
ACK y reconstrucción de entregas siguen siendo responsabilidad de SNI-57/58.

## Verificación

Pruebas específicas en `ParserRevalidationFlowTest` con PostgreSQL y Redis reales,
proveedor HTTP controlado y storage local; `ParserAdminArgumentsTest` cubre el
contrato del comando. Se comprueban idempotencia/concurrencia de activación,
rollback, páginas entre owners, inválidos, cambios concurrentes, revisión distinta,
timeout del mismo snapshot, recuperación de admisiones, retención y fencing.

`ParserAdminPackagingTest` verifica el jar de Snippets en una JVM Linux aislada,
sin socket Docker ni credenciales del host, contra PostgreSQL y una imagen real
de PrintScript. El 02/11/2026 pasó usando el proveedor revisado por digest
`ghcr.io/jjt-ingsis/printscript-service@sha256:ff2445b952bae35d43e88e400503e63eae15d430d83505a1660806d432c6dde5`.
Comprobó activación root, idempotencia, status, rechazo no-root, ausencia de
publicación/activación de capacidad como efecto del comando y lote válido/inválido
con el proveedor real. Las pruebas de transporte/ACK usan además Redis real en
`ParserRevalidationFlowTest`.

Para repetir esa aceptación opcional, generar primero el bootJar y definir
`PARSER_ADMIN_TEST_IMAGE` con una imagen local JDK 21 y `PARSER_PRINTSCRIPT_TEST_IMAGE`
con el proveedor empaquetado por digest. Ejecutar después `./gradlew test --tests
'*ParserAdminPackagingTest'`. Sin esas variables el caso se omite explícitamente;
las pruebas de PostgreSQL/Redis y argumentos se ejecutan normalmente.

Implementar este ticket no autoriza deploys, ejecutar comandos en dev, habilitar
workers ni activar lotes en producción.
