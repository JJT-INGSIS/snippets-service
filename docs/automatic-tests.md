# SNI-69 — Tests automáticos después de editar

`SnippetUpdated` consume el snapshot confirmado por SNI-68: código, hash y
conjunto exacto de tests del update. No consulta el blob más reciente para
reconstruir ejecuciones anteriores. El guardado confirmado no depende de que
los tests aprueben ni de que PrintScript esté disponible.

## Ejecución y recuperación

Usa trabajos, elementos, leases, fencing y permisos durables de SNI-58. Cada
intento persiste la auditoría completa antes de iniciar el intérprete y asocia
el ID remoto antes de consumir eventos. Una reentrega no crea otra ejecución
si existe una obligación remota. Un inicio incierto sin ID queda UNKNOWN:
no se repite el POST ni se libera capacidad por un timeout local.

Con ID conocido, consulta su descriptor y reconstruye el resultado desde los
eventos retenidos. Historial incompleto no equivale a test desaprobado. Una
sesión que ya no existe deja resultado desconocido explícito; nunca inventa
outputs. Usa comparación exacta de listas (orden, duplicados y strings vacíos).
Errores de programa y fallas técnicas son distintos de comparación FAILED.

El presupuesto compartido conserva capacidad manual y admite un trabajo
pesado de fondo a la vez. Esperar admisión no consume reintentos técnicos.
Los snapshots sólo se liberan después de un terminal durable sin obligaciones
remotas pendientes. No se agregan escrituras por evento: se conserva la decisión
aprobada de persistir inicio y resultado final para reducir latencia.

## Consulta pública

`GET /snippets/{snippetId}/automatic-tests` usa la autorización vigente de
lectura: owner y lectores compartidos. Un tercero recibe 404, una identidad
inválida 401 y una dependencia caída su error explícito. Respuesta sin caché.

La respuesta contiene `revision` (trabajo del update), `progress`, `validity`
(CURRENT, SUPERSEDED o UNVERIFIABLE) y `tests` con ID, estado de elemento,
outcome, comparison, outputs y reason. Sin update previo: revision/progress
null y lista vacía. Sin tests en el snapshot: trabajo completado con lista vacía.

Sólo se muestran resultados de la revisión vigente de código, conjunto de tests
y procesador efectivo. Un cambio o una revisión no verificable ocultan las filas
anteriores. La proyección DB es corta, no mantiene transacciones abiertas durante
HTTP y se vuelve a leer después de verificar el procesador. Los snapshots y el
historial anterior siguen disponibles únicamente para auditoría interna.
Las rutas manuales no exponen ni controlan IDs de auditoría automática.

## UI y alcance

El panel del detalle consulta una clave compartida por actor/snippet. Sólo hace
polling espaciado mientras el trabajo está pendiente, sin polling en segundo
plano y sin reintentos automáticos tras error. Guardar código o crear un test
invalida el resultado; cerrar el detalle desmonta la consulta. Una actualización
fallida oculta también los datos en caché. No ofrece rerun ni historial público.

Decisiones conservadas: aprobación de Thiago del 02/11/2026 para consulta por
owner/lectores vigentes; snapshots y recuperación con el contrato existente ya
aprobados en el chat. Alternativa descartada: compartir las sesiones manuales,
que siguen privadas. Costo aceptado: verificar el procesador exige una consulta
HTTP; si no se puede verificar, no se certifica un resultado anterior como vigente.

## Verificación reproducible

- JDK 21: `./gradlew build`.
- `AutomaticConsumerIntegrationTest`: PostgreSQL/Redis reales y ejecutor controlado;
  reentrega, snapshots rápidos, capacidad, recuperación, incertidumbre y errores.
- `CurrentAutomaticTestsTest`: autorización, obsolescencia y cambio durante lectura.
- UI: `npm test`, `npm run lint`, `npm run build`.
- UI contra todos los proveedores reales: iniciar UI en localhost y ejecutar
  `npx cypress run --config specPattern=cypress/e2e/backend/automatic-tests.cy.ts`.
  Sólo se configura la identidad dev; no se interceptan respuestas de servicios.

Verificación local 02/11/2026: recorrido de navegador exitoso contra PrintScript,
Permissions, PostgreSQL, Redis, Asset y Azurite reales. Dos tests del mismo update
producen PASSED/FAILED con output real `["2"]`; owner y lector ven ambos, tercero
recibe 404 y agregar otro test invalida la proyección anterior. No se desplegó
en Azure ni se modificó CD. La prueba usa datos nuevos con prefijo SNI69.
