# Resultados de tests automáticos

Cada actualización confirmada genera un snapshot con la referencia de código, su hash y el conjunto exacto de tests. `SnippetUpdated` procesa ese snapshot en segundo plano. La confirmación del guardado es independiente del resultado de los tests.

Se guarda la auditoría del intento antes de iniciar el intérprete. Una reentrega conserva cualquier obligación ya iniciada. Con ID remoto conocido se consulta el descriptor y se recuperan los eventos retenidos. Un historial incompleto deja el resultado desconocido. Sólo una ejecución completada permite comparar las listas de salida, con orden, duplicados y valores exactos. [Recuperación de trabajos](background-consumer.md)

`GET /snippets/{snippetId}/automatic-tests` está disponible para el propietario y los lectores compartidos actuales. Devuelve `revision`, `progress`, `validity` y `tests`. La validez es `CURRENT`, `SUPERSEDED` o `UNVERIFIABLE`. Se vuelve a verificar el código, el conjunto de tests y la revisión del procesador antes de presentar resultados. Los resultados históricos quedan fuera de la respuesta. Si todavía no hubo actualización, la revisión y el progreso son nulos y `tests` está vacío.

Las sesiones manuales son privadas del actor que las inicia. [Casos de prueba](tests.md) · [Ejecución](interactive-execution.md)
