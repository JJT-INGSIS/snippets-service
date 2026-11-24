# Integración con la UI

En los ambientes desplegados, el navegador usa `/api` y NGINX lo retira antes de entregar la solicitud a Snippets. La UI envía el access token en `Authorization`. El chequeo de CORS es relevante cuando la UI y la API tienen distinto origen. [Autenticación](authentication.md)

- El listado trae metadatos, relación y lint. El detalle agrega código y capacidades por lenguaje y versión. `GET /languages` provee las opciones de creación y extensiones. [Lectura](reading.md)
- La subida de archivo y la edición en el navegador mandan el texto en `code`. La descarga formateada recibe código y metadatos y arma el archivo en la UI. [Creación](creation.md) · [Descarga](formatted-download.md)
- El destinatario de un share se identifica por el ID opaco del directorio, codificado en el segmento de ruta. [Sharing](sharing.md)
- La ejecución usa eventos NDJSON y reanuda con la última secuencia recibida. Los trabajos asíncronos exponen progreso aparte del guardado. [Ejecución](interactive-execution.md) · [Resultados automáticos](automatic-tests.md)

Los errores usan Problem Details. El front debe resolver según status y `type`, y presentar la indisponibilidad de dependencias como error.
