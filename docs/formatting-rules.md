# Reglas de formato

`GET` y `PUT /formatting-rules/{language}/{version}` operan sobre el actor autenticado. El PUT reemplaza toda la selección mediante `{"rules":{…}}`. El catálogo y el chequeo de PrintScript deben acreditar la misma revisión. Se rechazan reglas desconocidas, fuera de rango, incompatibles, de otra versión o de tipo incorrecto.

La respuesta `200` incluye `language`, `version`, `revision`, `rules` (catálogo con valores vigentes), `changed` y `jobId`. La revisión es el hash del JSON canónico con claves ordenadas. `false` y `null` equivalen a omitir la regla. Una selección sin cambios devuelve `changed:false` y `jobId:null`.

Un cambio confirma la configuración, su revisión, el trabajo y el outbox `FormattingRulesChanged` en la misma transacción. `changed:true` confirma el guardado. El reprocesamiento en segundo plano tiene progreso aparte, en `GET /jobs/{jobId}`.

El consumidor descarta un lote superado, selecciona los snippets confirmados del propietario para ese lenguaje y versión, y genera derivados sin tocar los originales. Publica sólo si `FormattingBasis` sigue vigente para el contenido, el lenguaje, la versión, las reglas y el procesador. [Descarga](formatted-download.md) · [Trabajos y recuperación](background-consumer.md)

## Errores

| Status | `type` | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | El body no es exactamente `{"rules":{…}}`. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `422` | `urn:snippets:problem:invalid-formatting-rules` | Alguna regla no vale para esa versión. `violations` trae `rule` y `reason` de cada una. |
| `422` | `urn:snippets:problem:unsupported-language` | Lenguaje sin formatter. |
| `422` | `urn:snippets:problem:unsupported-version` | Versión no soportada por el lenguaje. |
| `502` | `urn:snippets:problem:dependency-failure` | PrintScript no respondió según su contrato o su revisión no se pudo verificar. Repetir es seguro. |
| `503` | `urn:snippets:problem:metadata-unavailable` | PostgreSQL no está disponible. Repetir es seguro. |
