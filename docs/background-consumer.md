# Consumo y recuperación de trabajos

`SNIPPETS_WORKER_ENABLED=true` habilita el consumidor. Usa el stream configurado para el [outbox](background-outbox.md) y el grupo `snippet-processing-v1` por defecto. Redis transporta entregas; PostgreSQL conserva progreso, reservas, leases e intentos. En Swarm, Snippets y PrintScript se reemplazan con parada previa para mantener un único coordinador y proveedor de operaciones durables por ambiente.

Tras reclamar una entrega, el consumidor procesa elementos con lease y token de posesión. Un trabajador que perdió su lease no puede publicar resultados ni liberar la reserva de otro. Los reintentos transitorios quedan recuperables. El ACK se confirma después de persistir el estado terminal o `UNKNOWN` con su obligación conservada en PostgreSQL. Los mensajes inválidos se registran como rechazados antes del ACK. El redespacho deduplica por evento y reacción. Los snapshots vinculados se retienen hasta que el trabajo termine y queden resueltas las obligaciones remotas.

El presupuesto de capacidad comparte un límite durable de 4 y reserva 1 cupo manual por defecto. Se admite un trabajo pesado de fondo a la vez. Esperar capacidad no gasta intentos de recuperación. Un timeout no prueba que PrintScript liberó el cupo. Los intentos de procesamiento durable registran el ID de operación antes del PUT al proveedor. Ante un resultado incierto se consulta ese ID y se reconcilia o establece la cancelación antes de habilitar otro intento. Un `404` del descriptor por sí solo no certifica que un pedido de inicio demorado no pueda llegar. [Ejecución](interactive-execution.md)

## Consulta de progreso

`GET /jobs/{jobId}` devuelve `id`, `status`, `processed`, `failed`, `pending` y `selectionComplete`. `processed` incluye descartes obsoletos y `pending` incluye reintentos e inciertos. Si `selectionComplete=false`, se cuentan los elementos descubiertos hasta el momento.

Los trabajos de formato y lint son visibles para el propietario de las reglas. Para el trabajo de tests se exige propiedad vigente del snippet. Los trabajos globales del parser se consultan mediante [el comando operativo](parser-revalidation.md). Un ID inexistente o fuera del alcance devuelve `404`.

## Diagnóstico

Correlacioná `eventId`, `jobId` y `correlationId` en los logs. El evento de New Relic `SnippetBackgroundSample` permite distinguir atraso del outbox, lag e antigüedad de entregas pendientes de ACK, trabajos sin progreso y presión de capacidad. La configuración de alertas y dashboards vive en el repo de [infraestructura](https://github.com/JJT-INGSIS/snippet-searcher-infra#readme).
