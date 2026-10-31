# Ejecución interactiva — SNI-49

Ejecutar un snippet y escribirle los inputs mientras corre (US10). Usa el
[contrato de ejecución de SNI-45](https://github.com/JJT-INGSIS/snippet-searcher-infra/blob/dev/docs/architecture/execution-contract.md)
y las sesiones que agregó [SNI-48](test-execution.md) para ejecutar tests.

| Operación | Estado |
| --- | --- |
| `POST /snippets/{id}/executions` | **Implementado en SNI-49.** Arranca una ejecución interactiva. |
| `POST /executions/{id}/inputs` | **Implementado en SNI-49.** Manda el input que pidió el programa. |
| `GET /executions/{id}`, `GET /executions/{id}/events`, `POST /executions/{id}/cancel` | De SNI-48. Sirven igual para los dos modos. |

La identidad y la política 403/404 están en [ui-integration.md](ui-integration.md).
Ejecutar **nunca modifica** el snippet ni su contenido.

## Quién puede qué

| Operación | Quién | Si no |
| --- | --- | --- |
| Arrancar | Quien puede leer el snippet: owner o usuario compartido | Sin acceso, inexistente o pendiente: `404`, sin llamar al intérprete |
| Input, eventos, descriptor, cancelar | Solo el actor que arrancó, mientras siga teniendo acceso | Otro actor: `403`. Perdió el acceso: `404` |

El `executionId` **no es una credencial**: cada pedido vuelve a comprobar la
identidad, que sea el mismo actor y el acceso vigente al snippet.

Cada actor tiene **una sola ejecución activa**, sumando tests e interactivas: la
reserva de SNI-48 (índice único en PostgreSQL) vale para los dos modos.

## Arrancar

```http
POST /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/executions
X-Dev-Actor-Id: dev-juan
Content-Type: application/json

{}
```

El body es exactamente `{}`. Cualquier campo (código, actor, owner) o texto
después del JSON responde `400`. El código siempre sale del snippet guardado,
leído con la lectura estable de SNI-48: hasta 3 intentos y `409 snapshot-conflict`
si el código cambia en el medio.

Respuesta `201 Created` con `Location: /executions/{executionId}`. Los valores
son ilustrativos:

```json
{"executionId":"5f0c2a8e-1b7d-4c9a-9e3f-2d6b8a1c4e70","snippetId":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","mode":"INTERACTIVE","state":"RUNNING","lastSeq":0,"limits":{"activeGlobal":4,"totalSeconds":120,"inputWaitSeconds":30,"reconnectSeconds":15,"codeBytes":1048576,"inputBytes":8192,"outputBytes":1048576,"bufferEvents":256,"bufferBytes":262144,"terminalRetentionSeconds":60},"snapshot":{"language":"printscript","version":"1.1","codeHash":"sha256-v1:d36f4e10154d0ba0e271496ff499add84339de6e586ad4b4088f343c1d796b58"}}
```

Es el mismo descriptor que el de un test, **sin `testId` ni `testHash`**: no se
mandan en `null` porque la UI rechaza el descriptor si vienen.

## Eventos

Se leen con `GET /executions/{id}/events?after=0` (NDJSON, igual que SNI-48).
Un programa que pide un nombre y saluda produce, por ejemplo:

```json
{"executionId":"5f0c2a8e-1b7d-4c9a-9e3f-2d6b8a1c4e70","seq":1,"type":"started","data":{"mode":"INTERACTIVE"}}
{"executionId":"5f0c2a8e-1b7d-4c9a-9e3f-2d6b8a1c4e70","seq":2,"type":"input-required","data":{"requestId":"b7e1c9a2-3f4d-4e5a-8b6c-7d8e9f0a1b2c","prompt":"Nombre?"}}
{"executionId":"5f0c2a8e-1b7d-4c9a-9e3f-2d6b8a1c4e70","seq":3,"type":"input-accepted","data":{"requestId":"b7e1c9a2-3f4d-4e5a-8b6c-7d8e9f0a1b2c"}}
{"executionId":"5f0c2a8e-1b7d-4c9a-9e3f-2d6b8a1c4e70","seq":4,"type":"output","data":{"text":"Hola, Ana"}}
{"executionId":"5f0c2a8e-1b7d-4c9a-9e3f-2d6b8a1c4e70","seq":5,"type":"terminal","data":{"outcome":"COMPLETED","reason":"program-completed","diagnostics":[]}}
```

Puede haber `heartbeat` en el medio. Snippets valida cada evento antes de
reenviarlo (`RunProgress`):

- `started` tiene que decir INTERACTIVE.
- Hay **un solo** `input-required` pendiente a la vez.
- `input-accepted` tiene que corresponder al pedido pendiente.
- El terminal **no trae `comparison`**: no hay test contra el cual comparar.

Un stream que rompe estas reglas se corta como falla del intérprete.

## Mandar un input

```http
POST /executions/5f0c2a8e-1b7d-4c9a-9e3f-2d6b8a1c4e70/inputs
X-Dev-Actor-Id: dev-juan
Content-Type: application/json

{"requestId":"b7e1c9a2-3f4d-4e5a-8b6c-7d8e9f0a1b2c","value":"Ana"}
```

```json
{"requestId":"b7e1c9a2-3f4d-4e5a-8b6c-7d8e9f0a1b2c","accepted":true}
```

| Regla | Si no se cumple |
| --- | --- |
| El body tiene exactamente `requestId` (UUID) y `value` (string). El string vacío es un input válido. | `400` |
| La sesión es INTERACTIVE. Un test nunca recibe inputs. | `409 input-conflict`, sin llamar al intérprete |
| `value` ocupa como máximo `limits.inputBytes` (8 KiB) **medidos en UTF-8**. | `413 payload-too-large`, sin llamar al intérprete |
| `requestId` es el pedido pendiente. | `409 input-conflict` (lo decide PrintScript) |

**Repetir** el mismo `requestId` con el mismo valor responde `200` otra vez, sin
consumir dos inputs: PrintScript guarda los ids 60 s y la repetición es
idempotente. Por eso Snippets no corta antes un input de una sesión que ya
terminó: un reintento después de un corte de red tiene que seguir funcionando.
Otro valor para un `requestId` ya aceptado es `409`.

## Errores

Las rutas de ejecución usan los nombres del contrato de SNI-45, los mismos que
SNI-48 (decisión 4). Por eso difieren de `identity-unavailable`/`dependency-failure`
del resto del servicio.

| Status | `type` (`urn:snippets:problem:…`) | Cuándo |
| --- | --- | --- |
| `400` | `invalid-request` | Body inválido o id que no es UUID |
| `401` | `identity-required` | Identidad ausente o inválida |
| `403` | `execution-actor-mismatch` | Otro actor opera una sesión ajena |
| `404` | `snippet-not-found` / `execution-not-found` | Sin acceso, inexistente o sesión vencida |
| `409` | `execution-already-active` | El actor ya tiene una ejecución activa, de cualquier modo |
| `409` | `input-conflict` | Input fuera de turno, otro valor para un pedido aceptado o sesión de test |
| `409` | `snapshot-conflict` | El código cambió durante los 3 intentos de lectura |
| `413` | `payload-too-large` | Input de más de 8 KiB |
| `422` | `unsupported-capability` / `unsupported-version` | El lenguaje o la versión no se pueden ejecutar |
| `503` | `dependency-unavailable` | PrintScript, Permissions o la base no respondieron |
| `504` | `dependency-timeout` | No se confirmó el inicio en el intérprete |

## Qué se guarda

La auditoría de SNI-48 (`snippet_execution_audit`) guarda también las ejecuciones
interactivas. La migración V5 permite `test_id` vacío y agrega `mode`, con un CHECK
que exige que TEST tenga test e INTERACTIVE no.

| Dato | TEST | INTERACTIVE |
| --- | --- | --- |
| Actor, snippet, código, lenguaje, versión, `codeHash`, fechas | Sí | Sí |
| Test (id, definición, hash) | Sí | No existe |
| Asociación con la sesión de PrintScript | Sí | Sí |
| Revisión del procesador que la inició (SNI-76) | Sí | Sí |
| Resultado final (outcome, motivo, diagnósticos) | Sí | Sí |
| Outputs | Sí, para comparar | **No** |
| Inputs | Los del test | **No**: lo que tipea el usuario no se guarda |

La salida interactiva se reenvía en vivo y se cuenta para el límite de 1 MiB,
pero no se retiene (decisión 3). V5 también reescribe los snapshots que había
guardado V4 a la forma nueva; `InteractiveRunAuditTest` lo comprueba migrando
una base desde V4.

## Recuperación y límites

`RecoverRuns` recupera los dos modos con el mismo código. Si el navegador se
cierra, guarda el resultado final cuando PrintScript termina la sesión.

- Los límites de tiempo los aplica PrintScript y llegan como terminal: 30 s
  esperando cada input, 120 s en total y 15 s sin navegador.
- Si Snippets se reinicia, la reconexión se reconstruye desde el historial de
  PrintScript (256 eventos / 256 KiB). Si ese historial ya se perdió, responde
  `409 event-history-unavailable` y la auditoría queda con resultado desconocido,
  igual que en SNI-48.

## Diseño

| Pieza | Paquete | Qué hace |
| --- | --- | --- |
| `ExecutionSnapshot` / `TestSnapshot` | `execution` | Foto del código. El test es un campo opcional; `mode()` y `request()` salen de él. |
| `ReadExecutionSnapshot` | `execution` | Lectura con test (SNI-48) y sin test (SNI-49), con el mismo `ReadGate` y la misma lectura estable. |
| `RunLauncher` | `execution` | Elige intérprete, verifica versión, reserva el cupo, arranca y asocia. Lo usan los dos modos. |
| `StartTestRun` / `StartInteractiveRun` | `execution` | Casos de uso: identidad → snapshot → `RunLauncher`. |
| `RunProgress` | `execution` | Valida cada evento. Lleva el input pendiente y no retiene salida interactiva. |
| `RunChannel.input` | `execution` | Rechaza tests y valores grandes; el resto lo reenvía a PrintScript. |
| `RecoverRuns` | `execution` | Recuperación periódica de los dos modos. |
| `V5__allow_interactive_execution_audit.sql` | migración | `test_id` opcional, columna `mode` con CHECK y reescritura del JSON de V4. |
| `ExecutionRequestDecoder` | `http` | Bodies estrictos `{}` y `{requestId, value}`. |
| `InteractiveExecutionController` | `http` | Las dos rutas nuevas. |

## Decisiones — SNI-49

Las propuso el plan de SNI-49 y las aprobó Juan.

| # | Decisión | Alternativa descartada | Beneficio | Costo aceptado | Aprobación |
| --- | --- | --- | --- | --- | --- |
| 1 | Esperar el merge de SNI-48 (snippets #47) y construir sobre esa base | Implementar en paralelo | No duplicar las reglas de US9, como pide el ticket | Esperar ese merge | 29/10/2026, «dale, espero a q mergee el 47» |
| 2 | Generalizar las piezas de SNI-48 en lugar de copiarlas: snapshot con test opcional, progreso con inputs y arranque compartido en `RunLauncher` | Una copia interactiva de cada clase | Una sola regla para cupo, auditoría y fallas | Cambia clases de Thiago; se le avisó | 29/10/2026, mismo mensaje |
| 3 | La auditoría interactiva guarda snapshot y resultado final, sin inputs ni outputs | Guardar todo, como en TEST | No retiene lo que tipea el usuario | No se puede reconstruir qué pasó adentro de la sesión | 29/10/2026, mismo mensaje |
| 4 | Errores con los nombres del contrato de ejecución | Los nombres de SNI-46 | Las rutas que comparten sesión con SNI-48 responden igual | Difieren del resto del servicio | 29/10/2026, mismo mensaje |
| 5 | La recuperación periódica cubre los dos modos | Una recuperación aparte | Misma garantía para los dos | Ninguno extra | 29/10/2026, mismo mensaje |
| 6 | El test opcional es un único campo `test: TestSnapshot?` | Tres campos opcionales sueltos | El compilador impide un snapshot a medias | V5 reescribe el JSON de las filas de V4 | 30/10/2026, opción B |
| 7 | Renombrar `TestRunProgress` → `RunProgress` y `RecoverTestRuns` → `RecoverRuns`, en un commit propio | Dejar los nombres | El nombre dice lo que hace | Toca nombres de clases de Thiago | 30/10/2026, opción A |

Todas son reversibles. La 6 necesita una migración nueva para volver atrás.

Detalles que siguen el contrato o convenciones del repo, sin consulta aparte:

- Arrancar exige permiso de **lectura** porque así lo fija el contrato de SNI-45.
- Snippets decide solo lo que sabe por sí mismo (test → `409`, tamaño → `413`).
  Si el input está en turno lo decide PrintScript, que emitió el pedido.
- Las rutas nuevas van en un controller propio. La respuesta `201` de una
  ejecución arrancada (`executionCreated`) es la misma para los dos modos.

## Verificación

```sh
./gradlew check
```

En el prototipo verificado del 30/10/2026: 410 casos, 0 fallos, 14 omitidos por
falta de proveedores live. Los tests de SNI-49:

| Test | Qué cubre |
| --- | --- |
| `RunProgressTest` | Recorrido interactivo y rechazo de eventos fuera de contrato |
| `RunChannelInputTest` | Reglas de input sin red ni base |
| `ReadExecutionSnapshotTest` | Lectura sin test y rechazo sin acceso antes de leer código |
| `InteractiveRunAuditTest` | Guardado sin test, cupo único por actor, CHECK de la base, `RunLauncher` y migración desde V4 |
| `ExecutionAuditPersistenceTest` | Recuperación periódica de una ejecución interactiva |
| `InteractiveExecutionHttpTest` | Descriptor, stream, contrato de input, bodies estrictos y acceso por HTTP real |
| `InteractiveExecutionLiveTest` | Programa con `readInput` contra PrintScript real, incluido el reintento idempotente |

Para correr los live tests, levantar PrintScript como indica
[execution-language.md](execution-language.md#verificación) y ejecutar:

```sh
PRINTSCRIPT_EXECUTION_URL=http://127.0.0.1:18080 ./gradlew test --tests '*LiveTest'
```

### Evidencia — 30/10/2026

| Prueba | Resultado |
| --- | --- |
| PrintScript de `dev` (`2fb2928`) levantado con `java -jar` | `InteractiveExecutionLiveTest` y `TestExecutionLiveTest` pasaron |
| Compose local con perfiles `ui` e `ingress` (http://localhost:8088): Snippets de esta rama, PrintScript `dev` (`2fb2928`) y UI `dev` (`3ce356d`) | Snippet PrintScript 1.1 con `readInput`: la UI mostró el pedido `Nombre?`, se envió `Ana`, salió `Hola, Ana` y terminó `COMPLETED` (`program-completed`) |
| Auditoría de esa ejecución (`7ef02438-ff70-4a3a-9d1a-d62f26519a30`) en la base de Compose | `INTERACTIVE`, sin test, `COMPLETED`, **0 outputs guardados**, reserva liberada |
| Live tests contra el PrintScript del contenedor (`127.0.0.1:8082`) | `InteractiveExecutionLiveTest` 1/1, `TestExecutionLiveTest` 2/2 y `PrintScriptExecutionLiveTest` 2/2, sin fallos |

No se desplegó ni se tocaron las VMs. La aceptación integrada completa (UI,
Permissions y Asset) sigue en SNI-53.
