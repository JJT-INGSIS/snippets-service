# Descargar código formateado

`GET /snippets/{snippetId}/formatted` entrega al propietario o a un lector autorizado la variante vigente, siempre con las reglas del propietario.

`CurrentStoredVariant` busca un derivado con la misma base de formato: referencia del original, lenguaje, versión, revisión de reglas y revisión verificada del procesador. Confirma de nuevo la base después de leer el derivado. Si el blob falta o quedó obsoleto, `FormatSavedSnippet` formatea bajo demanda. Ante un cambio concurrente durante esa operación, reintenta una vez y devuelve `409` si la base sigue cambiando. Si no se puede verificar el procesador, devuelve `502`.

`200` devuelve `snippetId`, `name`, `language`, `version`, `extension`, `code`, `source` (`stored` u `on-demand`), `rulesRevision` y `processorRevision`. La UI arma el archivo descargable con el código y la extensión. El GET no guarda la variante bajo demanda ni modifica el original.

## Errores

| Status | `type` | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | El id no es un UUID (`field: snippetId`) |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente o inválida |
| `404` | `urn:snippets:problem:snippet-not-found` | No existe, no está confirmado o el actor no tiene acceso |
| `409` | `urn:snippets:problem:formatting-stale` | El snippet cambió mientras se preparaba la variante. Repetir es seguro |
| `422` | `urn:snippets:problem:unformattable-code` | Un error léxico impide formatear. `diagnostics` trae `rule`, `message`, `line` y `column` |
| `422` | `urn:snippets:problem:invalid-formatting-rules` | Las reglas guardadas del owner ya no valen para el procesador actual. `violations` trae `rule` y `reason` |
| `422` | `urn:snippets:problem:unsupported-version` | La versión del snippet ya no tiene formatter |
| `422` | `urn:snippets:problem:formatting-limit-exceeded` | El código o el resultado superan el límite del procesador. `limit` es `code` u `output` |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions o PrintScript no respondieron, o la revisión del procesador no se pudo verificar |
| `503` | `urn:snippets:problem:storage-unavailable` | No se pudo leer el código o el derivado |
| `503` | `urn:snippets:problem:metadata-unavailable` | PostgreSQL no está disponible |
| `500` | `urn:snippets:problem:technical-failure` | El contenido guardado está roto |
