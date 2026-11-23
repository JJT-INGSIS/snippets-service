# SNI-111 — revisión del recorrido de tests automáticos

Base de Snippets: `02851b8`. Se contrastó la UI en `e550c92` y el proveedor
PrintScript en `70533f7`. Revisión de código del 23 de noviembre de 2026.
No equivale a verificar el despliegue activo de DEV/PROD.

## Recorrido y responsabilidades revisadas

| Pieza | Lógica y garantía revisada | Evaluación |
| --- | --- | --- |
| `SnippetsController.update` y `DraftJsonDecoder` | Entrada y traducción HTTP del reemplazo; no ejecutan tests dentro del controller. | Conservar frontera HTTP. |
| `PrepareUpdate` | Identidad, existencia, propiedad y validación antes de escribir. | No duplicar permisos dentro del handler automático. |
| `UpdateSnippet.update` | Coordina reemplazo y evaluación individual de lint. | Un resultado de test no participa en esta decisión. |
| `SnippetReplacement.apply` | Almacena contenido nuevo antes de cambiar la referencia. | No sobrescribir el contenido que un trabajo anterior todavía usa. |
| `JdbcSnippetUpdateCommit.commit` | Cambio de referencia, conjunto de tests, snapshot, trabajo y evento en una transacción local. | La lectura del conjunto aquí es necesaria; no consultar los tests actuales durante una ejecución histórica. |
| `JdbcJobRecords.insert` | Persiste trabajo e intención de publicación, con tamaño de evento acotado. | Frontera compartida con SNI-97; sin cambios. |
| `UpdateSnapshot` / `JdbcUpdateSnapshots.find` | Identidad del update, referencia, huella y definiciones exactas. | Separar la referencia de contenido del identificador del trabajo. |
| `JdbcUpdateSnapshots.release` | Libera retención sólo con raíz terminal y sin permisos remotos pendientes. | No retirar por aparente duplicación con el estado de la raíz. |
| `ContentRetention` / `RetainedContentCleanup` | Evitan borrar referencias activas o retenidas y cercan borrados concurrentes. | Frontera de storage/guardado; sin cambios. |
| `OutboxPublisher` / `RedisEventStream` / `RedisStreamInbox` | Publicación, entrega/reentrega y ACK. | Dependencias revisadas para seguir el recorrido; no modificar el transporte de Juanfran. |
| `JobConsumer.cycle/receive/execute` | Valida el evento contra el trabajo, adquiere lease y elige ejecución o reconciliación. | Una reentrega no debe repetir un inicio remoto incierto. |
| `JobWork` / `JdbcJobProgress` | Persistencia cercada por lease y progreso por elemento. | Recibir un mensaje no equivale a completar el trabajo. |
| `AutomaticTestHandler.select` | Selección paginada desde el snapshot e identificador estable por trabajo/test. | Conserva conjunto y orden; no selecciona desde los tests actuales. |
| `AutomaticTestHandler.step` | Lee la referencia, verifica huella, reserva capacidad y registra intento antes de iniciar. | Releer contenido por test tiene costo de I/O, pero cachearlo requiere límites y ciclo de vida propios; no agregar caché en esta corrección. |
| `AutomaticTestHandler.reconcile` | Recupera el intento asociado al permiso, sin otro POST de inicio. | Conservar incertidumbre si falta asociación remota. |
| `AutomaticRuns` / `JdbcAutomaticRuns` | Inicio, asociación y resultado durable; consulta el intento más reciente por elemento. | No mezclar la auditoría automática con sesiones manuales. |
| `AutomaticRemoteRuns.start/bind` | Clasifica rechazo/falla/inicio incierto; cancela una sesión conocida si falla asociarla. | No liberar por timeout ni repetir el inicio a ciegas. |
| `AutomaticRemoteRuns.recover/read` | Descriptor y replay del historial; distingue terminal confirmado de ejecución activa. | Defecto confirmado de identificador de error; corregido en este cambio. |
| `AutomaticRemoteRuns.consume` / `RunProgress.advance` | Valida secuencia y compara listas sólo al completar. | Reutilizar comparación evita divergencias entre tests manuales y automáticos. |
| `ExecutionCapacity` / `JdbcExecutionCapacity` | Presupuesto compartido y liberación bajo lease. | No atribuir fin de ejecución a la edad de la reserva. |
| `PrintScriptExecutor` / `ExecutionHttpTransport` / `ExecutionEventStream` | Adaptan HTTP/NDJSON e identificadores estables del proveedor. | La categoría recibida debe coincidir con la que reconoce el handler. |
| PrintScript: `EventJournal`, `ExecutionSession`, `ExecutionRegistry` | Historial acotado, estados terminales y retención. | Historial perdido y programa detenido son hechos distintos. |
| `JdbcAutomaticResults.current` | Proyección bajo bloqueo compartido, código vigente y conjunto actual de tests. | La comprobación del conjunto evita publicar resultados anteriores después de crear otro test. |
| `CurrentAutomaticTests.find/read/project` | Acceso, revisión del procesador y ocultamiento de resultados obsoletos/no verificables. | Conservar relectura después de HTTP. Hay oportunidades de evitar llamadas cuando no hay HTTP; no forman parte de esta corrección. |
| `AutomaticTestsController.find` | Traduce resultado y errores, respuesta sin caché. | Una falla de dependencia no se traduce a lista vacía. |
| UI: `getAutomaticTests` / `fromAutomaticTests` | Verifican status, representación, revisión y estados coherentes. | No aceptar comparación PASSED/FAILED para un resultado no completado. |
| UI: `useAutomaticTests` / `AutomaticTestsPanel` | Clave por actor/snippet, polling mientras pendiente, oculta caché ante error y muestra vigencia. | Polling de UNKNOWN sigue teniendo costo mientras el panel esté activo; no cambiar su política aquí. |
| UI: mutaciones de actualización/creación de tests | Invalidan la consulta de resultados después de confirmarse. | No presentar la caché anterior como una nueva ejecución automática. |

## Defecto confirmado: categoría de historial no reconocida

PrintScript `EventJournal.connect()` rechaza un cursor cuyo historial ya se perdió
con `event-history-unavailable`. El transporte conserva esa categoría, pero
`AutomaticRemoteRuns.read()` reconocía `history-unavailable`. Por ello el handler
devolvía `STREAM_REJECTED` incluso después de confirmar un descriptor terminal.
El trabajo podía continuar UNKNOWN reteniendo capacidad sin poder recuperar outputs.

Se reconoce la categoría mediante `RunFailure.HISTORY_UNAVAILABLE.category` y se
conserva la categoría previa ya admitida. Sólo con descriptor terminal se registra
`RESULT_UNKNOWN_HISTORY_EXPIRED`, se termina el elemento como falla técnica y se
libera su permiso. Sin terminal confirmado se mantiene UNKNOWN. No se produce
comparación FAILED ni se vuelve a iniciar el intérprete.

Alternativas: tratar todos los rechazos como terminales libera más rápido pero
puede admitir trabajo mientras el programa sigue activo; mantener todos como
UNKNOWN conserva capacidad de forma indefinida aun con prueba de terminación.
La corrección conserva la distinción existente y alinea el identificador, sin
cambiar protocolo, esquema, transporte Redis ni presupuesto de capacidad.

## Pruebas y límites de la evidencia

La regresión usa PostgreSQL/Redis reales y un ejecutor controlado: primero pierde
el historial con sesión activa y conserva capacidad; después confirma el terminal
y debe terminar PARTIAL, sin comparación, sin nuevo inicio y sin permiso retenido.
Antes de corregir la categoría, el trabajo seguía UNKNOWN aun con terminal confirmado.

También corresponden al recorrido las pruebas existentes:

- `UpdateSnapshotsIntegrationTest`: updates rápidos, rollback de outbox, retención,
  conjunto vacío y cercado de borrados.
- `AutomaticConsumerIntegrationTest`: reentrega, capacidad, recuperación,
  incertidumbre, comparación exacta y errores de programa.
- `CurrentAutomaticTestsTest`: propietario/lector, acceso denegado y cambio de
  código/tests/procesador durante una lectura.
- UI `automaticTests.test.ts` y Cypress `backend/automatic-tests.cy.ts`: contrato y
  aceptación de navegador; leer una prueba no acredita haberla ejecutado.

Verificación local: 23 pruebas aprobadas (9 de consumidor automático con
PostgreSQL/Redis, 7 de snapshots y 7 de consulta de vigencia). Se usó un solo
worker de Gradle con heap acotado; los contenedores de prueba se detuvieron al
final. `ktlintCheck` y `detekt` también fueron aprobados. El build completo y
su estado remoto se registran en la PR.

La revisión cruzada de otro integrante y una nueva aceptación de navegador con
todos los proveedores reales siguen siendo evidencias separadas del merge.

## Marco para defender las decisiones

El análisis aplica trade-offs, cohesión y acoplamiento al caso concreto; no asume
que más clases o menos llamadas sean siempre mejores. Referencia conceptual:
*Fundamentals of Software Architecture*, segunda edición, capítulos 2 y 3 según
el [índice editorial](https://www.oreilly.com/library/view/fundamentals-of-software/9781098175504/).
Ese índice no atribuye a los autores nuestros contratos ni límites particulares.
