# Actualización de snippets — SNI-10 y SNI-18

`PUT /snippets/{id}` reemplaza el código y los datos de un snippet existente. Solo su owner puede hacerlo. Es una propuesta backend sujeta a revisión con la UI.

SNI-10 preparó la identidad, la comprobación de existencia, el permiso y la validación del reemplazo. SNI-18 completa la escritura con el storage local de SNI-8 ([storage.md](storage.md)) y habilita el endpoint. No se implementa `PATCH`.

Los ejemplos de este documento son tests del contrato: `UpdateHttpTest` lee este archivo, envía cada pedido y compara la respuesta completa. Si el comportamiento cambia y el ejemplo no, el build falla.

La [verificación integrada de SNI-16](update-verification.md) complementa esos
tests con la imagen de Snippets y los servicios reales en Compose.

## Pedido

`PUT /snippets/{id}`

| Parte | Obligatorio | Descripción |
| --- | --- | --- |
| `{id}` en la ruta | Sí | UUID canónico del snippet, el que devolvió la creación. |
| `Content-Type` | Sí | `application/json`. Se acepta con `charset`. |
| `X-Dev-Actor-Id` | Sí | Identidad de desarrollo. Solo se acepta con el perfil Spring `dev`. |

No lleva `Idempotency-Key`: repetir el mismo `PUT` deja el mismo resultado.

### Reemplazo completo

El body tiene los mismos campos y las mismas reglas que la creación ([creation.md](creation.md)). No es un cambio parcial: lo enviado reemplaza todo lo anterior.

| Campo | Tipo | Obligatorio | Si no se envía | Si se envía `null` |
| --- | --- | --- | --- | --- |
| `name` | string | Sí | `400` | `400` |
| `description` | string o `null` | No | Se quita la descripción. | Se quita la descripción. |
| `language` | string | Sí | `400` | `400` |
| `version` | string | Sí | `400` | `400` |
| `code` | string | Sí | `400` | `400` |

Nombre, lenguaje y versión no pueden estar en blanco. El código no puede estar vacío ni contener sólo espacios en blanco (incluidos tabs y saltos de línea): responde `400` con `field=code`, antes de consultar dependencias o modificar el snippet. Una descripción vacía (`""`) se guarda como vacía y es distinta de quitarla. Los valores se conservan sin recortar ni normalizar.

Para conservar un dato hay que enviarlo de nuevo: un campo obligatorio ausente no significa «dejar el actual». No se acepta ningún otro campo: `ownerId`, identidad o huella en el body se rechazan. El owner no se puede cambiar con este pedido.

Se pueden modificar el código, el nombre, la descripción, el lenguaje y la versión. El `id`, el owner y los datos de la creación original no cambian.

Archivo y editor usan este mismo pedido: la UI envía el texto del archivo en `code`. No hay otro endpoint ni otra lógica.

## Respuestas

| Status | Significado | Cuerpo |
| --- | --- | --- |
| `200` | El snippet quedó con el código y los datos enviados. | Snippet |
| Otro | Este pedido no modificó el snippet, salvo lo indicado en [Fallas parciales](#fallas-parciales). | Error |

El cuerpo `Snippet` es el mismo de la creación: `id`, `name`, `description`, `language` y `version`. No incluye el código ni el owner.

### Error

`Content-Type: application/problem+json`, formato Problem Details (RFC 9457): `type`, `title`, `status`, `detail` e `instance`. Para decidir se usa `type`; la redacción de `detail` puede cambiar.

| Status | `type` | Significado | ¿Sirve repetir el pedido? |
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

Una falla de Permissions es un `502`, nunca un `403`: que el servicio no responda no dice nada sobre el permiso.

Las comprobaciones siguen este orden: `Content-Type`, forma del body, identidad, `id` de la ruta, existencia y estado del snippet, permiso, validación del código y, recién entonces, la escritura.

## Secuencia

`UpdateSnippet` es el único caso de uso:

| Paso | Clase | Qué hace | Dónde queda |
| --- | --- | --- | --- |
| 1 | `PrepareUpdate` | Resuelve la identidad, comprueba que el snippet exista y esté confirmado, pregunta a Permissions si esa identidad puede modificarlo y valida el reemplazo completo. | Nada: solo lee. |
| 2 | `SnippetReplacement` | Guarda el código nuevo como un contenido nuevo. | Storage |
| 3 | `SnippetReplacement` | Cambia nombre, descripción, lenguaje, versión y referencia del contenido en una sola sentencia SQL, con la fila bloqueada. | PostgreSQL de Snippets |
| 4 | `SnippetReplacement` | Borra el contenido anterior, que ya nadie referencia. | Storage |

Se valida el reemplazo completo, con el lenguaje y la versión enviados: cambiar de lenguaje o de versión valida el código nuevo contra los nuevos. No hace falta leer el código vigente, porque el pedido lo reemplaza entero.

El contenido vigente nunca se sobrescribe. Hasta el paso 3 el snippet apunta al código anterior completo; desde el paso 3 apunta al nuevo completo. No existe un momento en que muestre contenido a medias ni datos de un pedido con código de otro.

Un pedido rechazado en el paso 1 no escribe nada: el contenido y los datos anteriores quedan como estaban.

## Fallas parciales

Un timeout es un resultado incierto: la operación pudo haber ocurrido. Ninguna falla se responde como éxito.

| Falla | Respuesta | Estado resultante |
| --- | --- | --- |
| Permissions o Language no disponibles | `502` | Sin cambios. |
| Al guardar el código nuevo | `503 storage-unavailable` | Sin cambios. |
| Al cambiar datos y referencia, sin llegar a guardarse | `503 metadata-unavailable` | Sin cambios. El código nuevo queda guardado sin referenciar. |
| Al cambiar datos y referencia, guardado pero sin confirmación | `503 metadata-unavailable` | El snippet ya tiene el código y los datos nuevos, completos. El código anterior queda sin referenciar. |
| Al borrar el código anterior | `200` | Actualizado. El código anterior queda sin referenciar. |

Ante un `503 metadata-unavailable` el cliente no puede saber cuál de los dos casos ocurrió. En ambos el snippet está completo, con lo anterior o con lo nuevo. Repetir el mismo `PUT` lo deja con lo nuevo.

Un código que no llegó a referenciarse o que dejó de estarlo no afecta a ningún snippet y no tiene limpieza automática, como indica [storage.md](storage.md). Un código nuevo solo se borra cuando la base confirmó que no lo vinculó. No hay reintentos automáticos dentro del servicio.

## Concurrencia

Dos actualizaciones simultáneas del mismo snippet se aplican una después de la otra, porque el paso 3 bloquea la fila. Gana la última aplicada, con sus cinco valores juntos: el snippet nunca queda con el nombre de un pedido y el código de otro. Cada una borra el código que reemplazó, así que al terminar queda un único contenido.

Las dos reciben `200`. No hay control de revisiones: quien aplicó primero no se entera de que su cambio fue reemplazado. Detectar ese caso requeriría que el cliente envíe la versión que leyó, y no forma parte de este contrato.

El permiso se comprueba antes de escribir y no dentro de la misma transacción. Si el owner cambiara entre la comprobación y la escritura, el pedido ya autorizado se aplicaría igual. Hoy el owner no puede cambiar.

## Ejemplos

En cada ejemplo, el primer bloque es el cuerpo del pedido y el segundo es el cuerpo de la respuesta. El título indica el status. Salvo que se indique otra cosa, el pedido lo envía `dev-juan`, owner de un snippet creado con el ejemplo «Alta válida» de [creation.md](creation.md). El `id` es ilustrativo.

#### `200` Reemplazo completo

Cambia el nombre, la versión y el código.

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

El snippet tenía la descripción `Draft`. El pedido no envía `description`: se quita.

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

Falta `code`. No se interpreta como «conservar el código actual».

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

El mismo pedido del primer ejemplo, enviado por `dev-thiago`.

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

Ningún snippet tiene el `id` de la ruta.

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

Falta el punto y coma de la primera línea. El snippet conserva su código anterior.

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

## Límites

- No hay todavía un endpoint de lectura. Que el código nuevo quedó recuperable y que el anterior se conservó ante un rechazo se comprueba en los tests, leyendo el storage con la referencia vinculada.
- No hay control de revisiones ni historial: gana la última actualización aplicada y el código anterior se borra.
- Una lectura que tome la referencia justo antes de una actualización puede no encontrar ese contenido, porque ya fue reemplazado y borrado. El endpoint de lectura deberá volver a consultar la referencia en ese caso.
- El body se revisa antes que la identidad: un pedido sin identidad y con un body ilegible recibe `400`, no `401`.
- Un `code` con texto que no es Unicode válido responde `503 storage-unavailable`, igual que en la creación. Aquí no deja nada pendiente: el snippet conserva su estado anterior.
- `403` y `404` son distintos, como pide el contrato: quien no es owner puede saber que un `id` existe.

## Piezas internas

`PrepareUpdate.prepare(draft, snippetId, context)` devuelve `Prepared(UpdateCandidate)`, `Missing`, `Pending`, `Denied`, `OwnershipMissing` o `Rejected` con un motivo tipado. `Prepared` no es una edición exitosa: es un candidato ya autorizado y validado. Lee con `findMetadata`, que incluye pendientes solo para distinguirlos de inexistentes.

Solo para un snippet `CONFIRMED` consulta `GET /ownership/{uuid}/can-modify?actorId=...`; el cliente codifica el actor como valor de query. Solo un `200 application/json` con `allowed: true` autoriza continuar. `false` es denegación; un `404` Problem Details válido es ownership inexistente. Timeout, servicio caído y respuesta incompatible son fallas técnicas, nunca denegaciones.

`UpdateSnippet.update(draft, snippetId, context)` devuelve un `UpdateOutcome`:

| Resultado | Significado |
| --- | --- |
| `Updated(snippet)` | El snippet quedó con el código y los datos nuevos. |
| `Rejected(reason)` | La preparación rechazó el pedido: entrada, identidad, código o falla técnica al leer. |
| `Missing` | No existe. |
| `Pending` | Existe sin confirmar. |
| `Denied` | La identidad no es el owner. |
| `OwnershipMissing` | Permissions no conoce a su owner. |
| `Failed(failure)` | Falló el storage o la base durante la escritura. |

`SnippetReplacement` usa `replaceContent` de `SnippetContentLinks` (SNI-8), que cambia datos y referencia juntos. `updateMetadata` de SNI-6 no interviene: cambiaría los datos sin el contenido.

El caso de uso no conoce HTTP. `SnippetsController` decodifica el body y la ruta, `UpdateOutcomeTranslator` traduce cada resultado y `RejectionTranslator` traduce los rechazos de la preparación, que comparte con la creación. `SnippetProblem` reúne el catálogo de errores de los dos endpoints.

## Verificación

`sh ./gradlew check build --no-daemon` requiere Docker para PostgreSQL 18.6 con Testcontainers.

| Prueba | Qué comprueba | Con qué |
| --- | --- | --- |
| `UpdateHttpTest` | Los ejemplos de este documento sobre snippets creados por HTTP, las reglas del pedido, que un rechazo conserva código y datos, el código de un archivo sin alterar, y fallas reales de Permissions, PrintScript y storage. | HTTP real, PostgreSQL real, storage local real y servidores HTTP locales en lugar de PrintScript y Permissions. |
| `UpdateFlowTest` | Cada falla parcial, los resultados inciertos de la base, la repetición del pedido y la concurrencia. | PostgreSQL real y storage local real. |
| `UpdateOutcomeTranslatorTest` | La traducción de cada resultado a HTTP. | Sin Spring ni Docker. |
| `SnippetProblemTest` | Que las tablas de errores de este documento y de [creation.md](creation.md) coincidan con el catálogo. | Sin Spring ni Docker. |
| `UpdateLiveTest` | Creación seguida de actualización contra los servicios reales, incluido el rechazo a quien no es owner. | PostgreSQL real, storage local real, `printscript-service` y `permissions-service` reales. |

`UpdateLiveTest` se omite salvo que se indiquen los dos servicios:

```bash
PRINTSCRIPT_SERVICE_URL=http://localhost:8082 PERMISSIONS_SERVICE_URL=http://localhost:8081 ./gradlew test
```
