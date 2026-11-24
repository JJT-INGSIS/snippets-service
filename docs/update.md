# Actualizar un snippet

`PUT /snippets/{id}` requiere el UUID del snippet y `application/json`. El propietario reemplaza todos los campos con el mismo JSON estricto de [creación](creation.md). Omitir `description` o enviar `null` la quita. El UUID y el propietario se conservan. La ruta no requiere `Idempotency-Key`.

Se comprueban el body, la identidad, el UUID, el estado del snippet y el acceso antes de validar el código. Snippets guarda el nuevo contenido y confirma junto a sus metadatos el cambio de referencia. La base serializa las escrituras concurrentes del snippet y conserva el último reemplazo completo. El blob anterior sólo se descarta después de confirmar y si no lo retiene un snapshot. Ante un resultado incierto del commit, el blob nuevo se conserva porque podría haber quedado vinculado. [Almacenamiento](storage.md)

La transacción de reemplazo también guarda el snapshot, el trabajo y el outbox de [tests automáticos](automatic-tests.md). La evaluación puntual de [lint](linting-rules.md) comienza tras el commit. Un fallo posterior de evaluación no revierte el guardado.

`200` devuelve `id`, `name`, `description`, `language` y `version`.

## Errores

| Status | `type` | Cuándo | ¿Repetir el pedido? |
| --- | --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Body, campo o `id` de la ruta ausente o inválido. Si se identifica, `field` lo nombra. | No |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. | No |
| `403` | `urn:snippets:problem:modification-denied` | La identidad no es el owner del snippet. | No |
| `404` | `urn:snippets:problem:snippet-not-found` | No existe un snippet con ese `id`. | No |
| `409` | `urn:snippets:problem:snippet-pending` | El snippet existe pero su creación no está confirmada. | Después de completar la creación |
| `409` | `urn:snippets:problem:ownership-missing` | El snippet está confirmado pero Permissions no tiene su owner. Nadie puede modificarlo. | No |
| `422` | `urn:snippets:problem:invalid-code` | Código inválido. `diagnostics` trae `rule`, `message`, `line` y `column`. | No |
| `422` | `urn:snippets:problem:unsupported-language` | Lenguaje no soportado. | No |
| `422` | `urn:snippets:problem:unsupported-version` | Versión no soportada por el lenguaje. | No |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. | Sí |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions o PrintScript no respondieron según su contrato, o tardaron más que el límite. | Sí |
| `503` | `urn:snippets:problem:metadata-unavailable` | Falló la base de Snippets. | Sí |
| `503` | `urn:snippets:problem:storage-unavailable` | Falló el storage del código. | Sí |

## Ejemplos

#### `200` Reemplazo completo

```json
{
  "name": "Renamed",
  "description": null,
  "language": "printscript",
  "version": "1.1",
  "code": "println(2);"
}
```

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Renamed",
  "description": null,
  "language": "printscript",
  "version": "1.1"
}
```

#### `200` Descripción ausente

```json
{
  "name": "Renamed",
  "language": "printscript",
  "version": "1.1",
  "code": "println(2);"
}
```

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Renamed",
  "description": null,
  "language": "printscript",
  "version": "1.1"
}
```

#### `400` Campo obligatorio ausente

```json
{
  "name": "Renamed",
  "description": null,
  "language": "printscript",
  "version": "1.1"
}
```

```json
{
  "type": "urn:snippets:problem:invalid-request",
  "title": "Bad Request",
  "status": 400,
  "detail": "The body, one of its fields, a header or the snippet id is missing or invalid.",
  "instance": "/snippets/550e8400-e29b-41d4-a716-446655440000"
}
```

#### `403` Actor que no es el owner

```json
{
  "name": "Renamed",
  "description": null,
  "language": "printscript",
  "version": "1.1",
  "code": "println(2);"
}
```

```json
{
  "type": "urn:snippets:problem:modification-denied",
  "title": "Forbidden",
  "status": 403,
  "detail": "Only the owner can modify this snippet.",
  "instance": "/snippets/550e8400-e29b-41d4-a716-446655440000"
}
```

#### `404` Snippet inexistente

```json
{
  "name": "Renamed",
  "description": null,
  "language": "printscript",
  "version": "1.1",
  "code": "println(2);"
}
```

```json
{
  "type": "urn:snippets:problem:snippet-not-found",
  "title": "Not Found",
  "status": 404,
  "detail": "There is no snippet with that id.",
  "instance": "/snippets/550e8400-e29b-41d4-a716-446655440000"
}
```

#### `422` Código inválido

```json
{
  "name": "Renamed",
  "description": null,
  "language": "printscript",
  "version": "1.0",
  "code": "let total: number = 5\nprintln(total);"
}
```

```json
{
  "type": "urn:snippets:problem:invalid-code",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "The code is not valid for the requested language and version.",
  "instance": "/snippets/550e8400-e29b-41d4-a716-446655440000",
  "diagnostics": [
    {
      "rule": "UNEXPECTED_TOKEN",
      "message": "se esperaba ';' pero se encontró 'println'",
      "line": 2,
      "column": 1
    }
  ]
}
```
