# Crear un snippet

`POST /snippets` recibe `application/json` y requiere `Idempotency-Key` con un UUID canónico. El actor autenticado queda como propietario. Para subir un archivo, la UI envía su texto en el mismo campo `code`.

| Campo | Valor |
| --- | --- |
| `name`, `language` y `version` | Strings obligatorios que no pueden estar en blanco. La versión es explícita. |
| `code` | String obligatorio con contenido. |
| `description` | String opcional. Ausente y `null` quitan la descripción. Una cadena vacía se conserva. |

El JSON es estricto. Los strings se conservan tal como llegan. Se rechazan campos extra, claves repetidas y tipos incorrectos. La API verifica el tipo de contenido, el body, la identidad y la clave antes de validar el código y reservar el alta.

Tras validar con PrintScript, Snippets reserva el alta, guarda el código, registra la propiedad en Permissions y confirma los metadatos. Una reserva pendiente permanece oculta. La misma clave con el mismo actor y pedido permite continuar una creación pendiente o recuperar una confirmada. Si el pedido o actor difiere, devuelve `409`. El lint del alta nueva ocurre tras confirmar el guardado.

`201` confirma un alta nueva y `200` devuelve una ya confirmada. Ambas respuestas incluyen `id`, `name`, `description`, `language` y `version`.

## Errores

Los errores usan `application/problem+json`. Para identificar una condición, usá `type`.

| Status | `type` | Cuándo | ¿Reintentar con la misma clave? |
| --- | --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Body, campo o `Idempotency-Key` ausente o inválido. Si se identifica, `field` lo nombra. | No |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. | No |
| `409` | `urn:snippets:problem:creation-conflict` | La clave ya se usó con otro pedido o con otra identidad. | No |
| `409` | `urn:snippets:problem:ownership-conflict` | Permissions ya tiene otro owner para ese UUID. El alta no se confirma. | No |
| `422` | `urn:snippets:problem:invalid-code` | Código inválido. `diagnostics` trae `rule`, `message`, `line` y `column`. | No |
| `422` | `urn:snippets:problem:unsupported-language` | Lenguaje no soportado. | No |
| `422` | `urn:snippets:problem:unsupported-version` | Versión no soportada por el lenguaje. | No |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. | Sí |
| `502` | `urn:snippets:problem:dependency-failure` | PrintScript o Permissions no respondieron según su contrato, o tardaron más que el límite. | Sí |
| `503` | `urn:snippets:problem:metadata-unavailable` | Falló la base de Snippets. | Sí |
| `503` | `urn:snippets:problem:storage-unavailable` | Falló el storage del código. | Sí |

## Ejemplos

Los pedidos con `X-Dev-Actor-Id` ilustran el modo local de [autenticación](authentication.md).

#### `201` Alta válida

```json
{
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0",
  "code": "println(1);"
}
```

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0"
}
```

#### `200` Reintento del mismo pedido

```json
{
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0",
  "code": "println(1);"
}
```

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0"
}
```

#### `409` Misma clave con otro pedido

```json
{
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0",
  "code": "println(2);"
}
```

```json
{
  "type": "urn:snippets:problem:creation-conflict",
  "title": "Conflict",
  "status": 409,
  "detail": "The Idempotency-Key was already used by another request.",
  "instance": "/snippets"
}
```

#### `422` Código inválido

```json
{
  "name": "Example",
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
  "instance": "/snippets",
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

#### `400` Campo no admitido

```json
{
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0",
  "code": "println(1);",
  "ownerId": "dev-thiago"
}
```

```json
{
  "type": "urn:snippets:problem:invalid-request",
  "title": "Bad Request",
  "status": 400,
  "detail": "The body, one of its fields, a header or the snippet id is missing or invalid.",
  "instance": "/snippets"
}
```

#### `401` Pedido sin identidad

```json
{
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0",
  "code": "println(1);"
}
```

```json
{
  "type": "urn:snippets:problem:identity-unavailable",
  "title": "Unauthorized",
  "status": 401,
  "detail": "The request does not carry a usable identity.",
  "instance": "/snippets"
}
```
