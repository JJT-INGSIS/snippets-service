# Ejecución de snippets y tests

El propietario o un lector compartido puede iniciar una ejecución con el código guardado. Cada actor tiene una sola ejecución activa entre ambos modos. Sólo quien la inició puede operar la sesión, mientras conserve acceso al snippet.

| Ruta | Resultado |
| --- | --- |
| `POST /snippets/{id}/executions` con `{}` | `201` con descriptor de sesión `INTERACTIVE`. |
| `POST /snippets/{id}/tests/{testId}/executions` con `{}` | `201` con descriptor de sesión de test. |
| `GET /executions/{id}` | Descriptor con estado, cursores, límites y snapshot. |
| `GET /executions/{id}/events?after=0` | Eventos NDJSON en orden por `seq`. Para reconectar, enviar la última secuencia recibida en `after`. |
| `POST /executions/{id}/inputs` con `{"requestId":"…","value":"…"}` | Envía un input interactivo para el pedido pendiente. |
| `POST /executions/{id}/cancel` con `{}` | `202` con el estado de cancelación. El terminal confirma el resultado. |

El inicio valida acceso, test y snapshot antes de llamar a PrintScript. El descriptor devuelve `executionId`, `snippetId`, `mode`, `state`, `lastSeq`, `limits` y `snapshot`. Los tests incluyen además `testId` y el hash del test en el snapshot. La API no expone el ID remoto.

Los eventos interactivos incluyen `started`, `input-required`, `input-accepted`, `output`, `heartbeat` y `terminal`. El input admite un string vacío y hasta `limits.inputBytes` en UTF-8. Repetir el mismo `requestId` con el mismo valor durante la ventana de retención no consume otro input. Un valor distinto, un pedido fuera de turno o un input en modo test devuelve `409`.

Un cursor futuro devuelve `400`, y un historial perdido o un segundo consumidor activo devuelve `409`. Otro actor recibe `403` al operar una sesión ajena. Una sesión vencida o pérdida de acceso devuelve `404`.

Snippets registra la reserva y el snapshot en PostgreSQL antes de iniciar. Sólo un término comprobado o rechazo conocido libera la reserva. Se persiste el resultado antes de emitir el terminal público; si no puede guardarse, no se anuncia éxito confirmado. En modo test, sólo `COMPLETED` produce comparación `PASSED` o `FAILED` mediante igualdad exacta de las listas.
