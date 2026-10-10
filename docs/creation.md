# Creación de snippets — SNI-9 y SNI-17

`POST /snippets` crea un snippet con su código, sus metadatos y su owner. Es una propuesta backend sujeta a revisión con la UI.

SNI-9 preparó la identidad, la validación, la huella del pedido y las reservas. SNI-17 completa la secuencia con el storage local de SNI-8 ([storage.md](storage.md)), registra el owner en Permissions y habilita el endpoint.

Los ejemplos de este documento son tests del contrato: `CreationHttpTest` lee este archivo, envía cada pedido y compara la respuesta completa. Si el comportamiento cambia y el ejemplo no, el build falla.

## Pedido

`POST /snippets`

| Header | Obligatorio | Descripción |
| --- | --- | --- |
| `Content-Type` | Sí | `application/json`. Se acepta con `charset`. |
| `Idempotency-Key` | Sí | UUID canónico de 36 caracteres elegido por el cliente. Identifica el alta ante reintentos. |
| `X-Dev-Actor-Id` | Sí | Identidad de desarrollo. Solo se acepta con el perfil Spring `dev`. |

| Campo | Tipo | Obligatorio | Descripción |
| --- | --- | --- | --- |
| `name` | string | Sí | No puede estar en blanco. |
| `description` | string o `null` | No | Ausente y `null` son equivalentes. Una cadena vacía es distinta. |
| `language` | string | Sí | No puede estar en blanco. |
| `version` | string | Sí | No puede estar en blanco. No hay versión por defecto. |
| `code` | string | Sí | Puede estar vacío: Language decide su validez. |

Los valores se conservan sin recortar ni normalizar. No se acepta ningún otro campo: un body con `ownerId`, `actorId` o `fingerprint` se rechaza. Tampoco se convierten tipos: un número donde va un string se rechaza. `DraftJsonDecoder` aplica estas reglas.

Archivo y editor usan este mismo pedido: la UI envía el texto del archivo en `code`. No hay otro endpoint ni otra lógica.

### Identidad y owner

El owner del snippet es siempre la identidad del pedido. No se puede elegir desde el body.

`ActorContext` conserva todos los valores de `X-Dev-Actor-Id` para rechazar headers repetidos. `DevelopmentActorIdentityResolver` solo acepta el header con el perfil Spring `dev` explícitamente activo; sin ese perfil toda creación responde `401`. No hay actor por defecto ni Auth0: una identidad ausente, en blanco o repetida se rechaza. Las identidades válidas se comparan exactamente. Este mecanismo no es autenticación y no debe habilitarse como identidad de producción.

## Respuestas

| Status | Significado | Cuerpo |
| --- | --- | --- |
| `201` | El alta quedó confirmada en este pedido. | Snippet |
| `200` | Reintento de un alta que ya estaba confirmada. | Snippet |
| Otro | No hay un alta confirmada por este pedido. | Error |

Solo `201` y `200` significan que el snippet existe. Ninguna otra respuesta presenta una creación pendiente como un snippet disponible.

### Snippet

`Content-Type: application/json`

| Campo | Tipo | Descripción |
| --- | --- | --- |
| `id` | string | UUID generado por Snippets. Es el mismo en metadatos, contenido y ownership. |
| `name` | string | El recibido. |
| `description` | string o `null` | La recibida. |
| `language` | string | El recibido. |
| `version` | string | La recibida. |

No incluye el código ni el owner.

### Error

`Content-Type: application/problem+json`, formato Problem Details (RFC 9457): `type`, `title`, `status`, `detail` e `instance`. Para decidir se usa `type`; la redacción de `detail` puede cambiar.

| Status | `type` | Significado | ¿Sirve reintentar con la misma clave? |
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

En PrintScript el código inválido es un `200` con `valid` en `false`; en Snippets es un `422`, porque el alta no se realizó.

Los errores de protocolo, como un método o un `Content-Type` no admitidos, usan el mismo formato con su status estándar y sin un `type` propio.

Las comprobaciones siguen este orden: `Content-Type`, forma del body, identidad, `Idempotency-Key`, validación del código y, recién entonces, la reserva.

## Secuencia

`CreateSnippet` es el único caso de uso. Cada paso es una clase pequeña:

| Paso | Clase | Qué hace | Dónde queda |
| --- | --- | --- | --- |
| 1 | `PrepareCreation` | Resuelve la identidad, valida clave, campos y código, y reserva un UUID en estado `PENDING`. | PostgreSQL de Snippets |
| 2 | `CreationContent` | Guarda el código y vincula su referencia a la reserva. | Storage y PostgreSQL de Snippets |
| 3 | `CreationOwner` | Registra como owner a la identidad que hizo la reserva, con el UUID reservado. | Permissions |
| 4 | `CreationConfirmation` | Pasa la reserva a `CONFIRMED`. | PostgreSQL de Snippets |

El código inválido se rechaza en el paso 1, antes de reservar: no deja ningún registro.

No hay una transacción que abarque los tres sistemas. La consistencia se obtiene con el orden y con pasos que se pueden repetir: el snippet solo se confirma cuando su contenido y su owner ya existen, y una reserva `PENDING` nunca se muestra como snippet.

## Idempotencia y reintentos

La huella del pedido se calcula en el backend: SHA-256 de `[name, description, language, version, code]` serializado como array JSON de orden fijo en UTF-8, con prefijo `sha256-v1:`. No depende del orden del JSON recibido y distingue descripción nula de vacía. El cliente no puede elegirla.

| Pedido con una clave ya usada | Resultado |
| --- | --- |
| Misma identidad y misma huella, alta confirmada | `200` con el mismo snippet. No guarda ni registra nada de nuevo. |
| Misma identidad y misma huella, alta pendiente | Continúa desde el paso que faltaba y responde `201` con el mismo UUID. |
| Otra huella u otra identidad | `409 creation-conflict`. No expone ni modifica el registro existente. |

Cada pedido vuelve a validar el código antes de mirar la reserva. Por eso un reintento de un alta ya confirmada responde `502` mientras PrintScript no esté disponible; repetirlo después responde `200`.

Ante un timeout o una falla técnica no se debe generar otra clave: con otra clave se crearía un snippet distinto.

## Fallas parciales

Un timeout es un resultado incierto: la operación pudo haber ocurrido. Por eso ninguna falla deshace pasos anteriores ni borra lo que otro sistema pudo haber guardado. La respuesta es un error y el reintento con la misma clave completa lo que falte.

| Falla | Respuesta | Estado resultante | El reintento |
| --- | --- | --- | --- |
| Language no disponible | `502` | Nada reservado. | Empieza de nuevo. |
| Al reservar | `503 metadata-unavailable` | La reserva puede existir o no. | La encuentra o la crea. |
| Al guardar el código | `503 storage-unavailable` | Reserva `PENDING` sin contenido. | Guarda y sigue. |
| Al vincular el código | `503 metadata-unavailable` | El vínculo puede existir o no. El código guardado no se borra. | Si está vinculado sigue; si no, guarda otra copia. |
| Al registrar el owner | `502` | Reserva `PENDING` con contenido. El owner puede estar registrado o no. | Registra de nuevo: Permissions responde igual para el mismo UUID y owner. |
| Owner distinto en Permissions | `409 ownership-conflict` | Reserva `PENDING`. Nunca se confirma. | Recibe el mismo error. |
| Al confirmar | `503 metadata-unavailable` | La confirmación puede existir o no. | `200` si existía; si no, confirma y responde `201`. |

Un código guardado que no llegó a vincularse queda sin referenciar: no afecta a ningún snippet y no hay limpieza automática, como indica [storage.md](storage.md). No hay reintentos automáticos dentro del servicio ni un proceso que complete reservas abandonadas: una reserva `PENDING` espera el reintento del cliente.

## Concurrencia

Varios pedidos simultáneos con la misma clave y el mismo contenido obtienen el mismo UUID: la clave es única en PostgreSQL. Cada uno ejecuta los pasos que encuentra sin hacer, y todos son repetibles. El resultado es un único snippet con un único contenido vinculado; más de uno puede recibir `201`.

Si dos pedidos guardan el código a la vez, el vínculo queda apuntando a una de las copias y la otra se borra. Solo se borra una copia cuando la base confirmó que ningún snippet la referencia.

## Ejemplos

En cada ejemplo, el primer bloque es el cuerpo del pedido y el segundo es el cuerpo de la respuesta. El título indica el status. Salvo que se indique otra cosa, el pedido lleva `Idempotency-Key` con un UUID nuevo y `X-Dev-Actor-Id: dev-juan`. El `id` de las respuestas es ilustrativo.

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

El mismo pedido, con la misma `Idempotency-Key` y la misma identidad que un alta ya confirmada.

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

La `Idempotency-Key` del alta anterior, con otro código.

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

Falta el punto y coma de la primera línea.

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

El body intenta elegir el owner.

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
  "detail": "The body, one of its fields or the Idempotency-Key header is missing or invalid.",
  "instance": "/snippets"
}
```

#### `401` Pedido sin identidad

El pedido no lleva `X-Dev-Actor-Id`.

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

## Límites

- No hay todavía un endpoint de lectura. Que el código quedó recuperable se comprueba en los tests, leyendo el storage con la referencia vinculada.
- Una reserva `PENDING` que el cliente no reintenta queda así. No se muestra como snippet y no hay un proceso que la complete ni que la borre.
- El body se revisa antes que la identidad: un pedido sin identidad y con un body ilegible recibe `400`, no `401`.
- Un `code` con texto que no es Unicode válido, como un surrogate suelto escrito con un escape JSON, no se puede guardar en UTF-8 sin alterarlo. Hoy responde `503 storage-unavailable` y deja una reserva que ningún reintento completa. Rechazarlo como `400` al decodificar el body queda como mejora aparte.
- Mientras un servicio está detenido en Docker su nombre no se resuelve, y la JVM recuerda esa falla durante 10 segundos. Un reintento dentro de ese lapso puede volver a recibir `502` aunque el servicio ya esté disponible.

## Piezas internas

`PrepareCreation.prepare(draft, keyHeader, context)` devuelve `Prepared(metadata, replayed, actorId)`, `Conflict` o `Rejected` con un motivo tipado. `Prepared` no es un alta exitosa: es una reserva, y `actorId` es la identidad que la hizo y que se registra como owner. Consume `prepareCreation` según [el contrato de SNI-6](metadata.md).

`FindPreparedCreation.find(keyHeader, context)` traduce `findCreation` a `Found`, `Missing`, `IdentityConflict` o rechazo. No comprueba la huella: para comparar un nuevo pedido se usa `prepareCreation`.

`CreateSnippet.create(draft, keyHeader, context)` devuelve un `CreationOutcome`:

| Resultado | Significado |
| --- | --- |
| `Created(snippet)` | El alta se confirmó en esta llamada. |
| `AlreadyCreated(snippet)` | La clave corresponde a un alta ya confirmada. |
| `Rejected(reason)` | La preparación rechazó el pedido. No se continuó. |
| `Conflict` | La clave pertenece a otro pedido o a otra identidad. |
| `Incomplete(failure)` | Hay una reserva `PENDING` y un paso no se pudo completar. |

El caso de uso no conoce HTTP. `SnippetsController` decodifica el body y los headers, `CreationOutcomeTranslator` traduce cada resultado y `SnippetProblem` reúne el catálogo de errores. `CreationResponse.fromConfirmed` rechaza los pendientes, por lo que la capa HTTP no puede armar una respuesta exitosa con una reserva.

`MetadataPersistenceException` se traduce a falla técnica dentro de cada paso, sin SQL ni credenciales. No se mantiene una transacción JDBC abierta durante las llamadas a storage o a Permissions.

## Permissions y configuración

`OwnershipRegistrar` y `OwnershipReader` son interfaces pequeñas separadas. Sus adaptadores consumen `PUT /ownership/{uuid}` con `ownerId` y `GET` de la misma ruta. Las respuestas exitosas requieren `application/json`, el UUID correcto y un owner válido; el registro exige además el mismo owner solicitado. `201` es nuevo, `200` compatible, `409` conflicto y `404` de consulta inexistencia. Los Problem Details requieren su tipo estable, status e instance; nunca se decide por mensajes humanos.

`400`, `5xx`, timeout y respuestas vacías o incompatibles son fallas técnicas, no código inválido ni inexistencia. Un timeout de registro o un éxito incompatible es incierto: la operación puede haber ocurrido.

| Variable | Valor por defecto | Uso |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Ninguno | Debe incluir `dev` para aceptar `X-Dev-Actor-Id`. |
| `PERMISSIONS_BASE_URL` | `http://localhost:8081` | Destino de Permissions. En Docker, `http://permissions-service:8080`. |
| `PERMISSIONS_CONNECT_TIMEOUT` | `2s` | Límite de conexión. |
| `PERMISSIONS_READ_TIMEOUT` | `5s` | Límite de respuesta. |
| `PRINTSCRIPT_BASE_URL` | `http://localhost:8082` | Destino de PrintScript. `LANGUAGE_PRINTSCRIPT_BASE_URL` tiene prioridad. |
| `STORAGE_LOCAL_DIRECTORY` | `build/snippet-content` | Directorio del código. Ver [storage.md](storage.md). |

## Verificación

`sh ./gradlew check build --no-daemon` requiere Docker para PostgreSQL 18.6 con Testcontainers.

| Prueba | Qué comprueba | Con qué |
| --- | --- | --- |
| `CreationHttpTest` | Los ejemplos de este documento, las reglas del pedido, el owner tomado del contexto, el código de un archivo sin alterar, y fallas reales de PrintScript, Permissions y storage con su reintento. | HTTP real, PostgreSQL real, storage local real y servidores HTTP locales en lugar de PrintScript y Permissions. |
| `CreationFlowTest` | Cada falla parcial, los resultados inciertos de la base, los reintentos y la concurrencia. | PostgreSQL real y storage local real. |
| `CreationOutcomeTranslatorTest` | La traducción de cada resultado a HTTP y que la tabla de errores de este documento coincida con el catálogo. | Sin Spring ni Docker. |
| `CreationLiveTest` | El alta completa contra los servicios reales. | PostgreSQL real, storage local real, `printscript-service` y `permissions-service` reales. |

`CreationLiveTest` se omite salvo que se indiquen los dos servicios:

```bash
PRINTSCRIPT_SERVICE_URL=http://localhost:8082 PERMISSIONS_SERVICE_URL=http://localhost:8081 ./gradlew test
```
