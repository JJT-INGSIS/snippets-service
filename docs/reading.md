# Consultar snippets

Permissions determina si el actor es propietario o lector compartido. La lista y el detalle sólo muestran snippets confirmados. Consultá los modos de identidad en [Autenticación](authentication.md).

## Listar

`GET /snippets` devuelve `page`, `page_size`, `count` y `snippets`. Cada elemento contiene metadatos, relación y estado de lint. Los parámetros deben ser conocidos, con un solo valor por clave, y se validan antes de resolver la identidad.

| Parámetro | Default | Valores | Descripción |
| --- | --- | --- | --- |
| `relation` | `all` | `all`, `owned`, `shared` | Propios, compartidos o ambos. |
| `name` | sin filtro | texto | Contiene el texto, sin distinguir mayúsculas. Vacío equivale a no filtrar. No se recorta. |
| `language` | sin filtro | texto | Igual exacto al lenguaje del snippet. Vacío equivale a no filtrar. |
| `lint` | sin filtro | `compliant`, `not-compliant`, `pending`, `failed` | Estado vigente, aplicado antes de paginar. |
| `sort` | `name` | `name`, `language`, `lint` | Campo de orden; lint usa orden lexicográfico del estado. |
| `direction` | `asc` | `asc`, `desc` | Sentido del orden. |
| `page` | `0` | entero `>= 0` | Número de página. La primera es `0`. |
| `page_size` | `10` | entero de `1` a `100` | Tamaño de página. Cubre las opciones de la tabla de la UI: 10, 25, 50 y 100. |

Se filtra y ordena antes de paginar, con desempate por ID ascendente. `count` es el total filtrado, incluso si la página pedida está vacía. La lista se resuelve con metadatos y consulta la revisión vigente de lint, sin leer el código. Un fallo de permisos o del proveedor de lint se informa como error.

### Errores

| Status | `type` | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Parámetro desconocido, repetido o inválido. `parameter` lo nombra. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions o el proveedor de lint no respondieron según su contrato. |
| `503` | `urn:snippets:problem:metadata-unavailable` | Falló la base de Snippets. |

### Ejemplos

#### `200` Primera página, propios y compartidos

```http
GET /snippets?page=0&page_size=2
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":2,"count":3,"snippets":[{"id":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","name":"Hello","description":null,"language":"printscript","version":"1.0","extension":"prs","ownerId":"dev-juan","relation":"owned","lint":{"status":"pending","reason":"NOT_EVALUATED","diagnostics":[],"codeHash":null,"rulesRevision":null,"processorRevision":null}},{"id":"7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50","name":"Sum","description":"Adds two numbers","language":"printscript","version":"1.1","extension":"prs","ownerId":"dev-thiago","relation":"shared","lint":{"status":"pending","reason":"NOT_EVALUATED","diagnostics":[],"codeHash":null,"rulesRevision":null,"processorRevision":null}}]}
```

#### `200` Solo compartidos, filtrados por nombre

```http
GET /snippets?relation=shared&name=su&page=0&page_size=10
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":10,"count":1,"snippets":[{"id":"7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50","name":"Sum","description":"Adds two numbers","language":"printscript","version":"1.1","extension":"prs","ownerId":"dev-thiago","relation":"shared","lint":{"status":"pending","reason":"NOT_EVALUATED","diagnostics":[],"codeHash":null,"rulesRevision":null,"processorRevision":null}}]}
```

#### `200` Página posterior a la última

```http
GET /snippets?page=5&page_size=10
X-Dev-Actor-Id: dev-juan
```

```json
{"page":5,"page_size":10,"count":3,"snippets":[]}
```

#### `400` Tamaño de página fuera de rango

```http
GET /snippets?page=0&page_size=500
X-Dev-Actor-Id: dev-juan
```

```json
{"type":"urn:snippets:problem:invalid-request","title":"Bad Request","status":400,"detail":"The body, one of its fields, a header or the snippet id is missing or invalid.","instance":"/snippets","parameter":"page_size"}
```

#### `200` Filtro por lint

```http
GET /snippets?lint=compliant
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":10,"count":0,"snippets":[]}
```

#### `502` Permissions no disponible

```http
GET /snippets
X-Dev-Actor-Id: dev-juan
```

```json
{"type":"urn:snippets:problem:dependency-failure","title":"Bad Gateway","status":502,"detail":"A service this operation depends on did not answer correctly. Repeating the same request is safe.","instance":"/snippets"}
```

## Ver el detalle

`GET /snippets/{id}` devuelve los metadatos de un snippet accesible más `code` y `capabilities`. El acceso se verifica antes de leer el contenido. Un tercero recibe `404` aunque el snippet exista. Si falta el blob y la referencia cambió por una edición concurrente, Snippets relee metadatos y contenido una vez con la nueva referencia. Si el contenido sigue ausente, devuelve `503`.

### Errores

| Status | `type` | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | `{id}` no es un UUID canónico. `field` vale `snippetId`, igual que en el `PUT`. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `404` | `urn:snippets:problem:snippet-not-found` | No existe, sigue pendiente o el actor no tiene acceso. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado, incluido un snippet confirmado sin referencia de contenido. |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions o el proveedor de lint no respondieron según su contrato. |
| `503` | `urn:snippets:problem:metadata-unavailable` | Falló la base de Snippets. |
| `503` | `urn:snippets:problem:storage-unavailable` | Falló el storage o el código no se pudo leer de forma coherente con una actualización simultánea. |

### Ejemplos

#### `200` Snippet propio

```http
GET /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40
X-Dev-Actor-Id: dev-juan
```

```json
{"id":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","name":"Hello","description":null,"language":"printscript","version":"1.0","extension":"prs","ownerId":"dev-juan","relation":"owned","lint":{"status":"pending","reason":"NOT_EVALUATED","diagnostics":[],"codeHash":null,"rulesRevision":null,"processorRevision":null},"code":"println(\"Hello\");","capabilities":{"modify":true,"share":true}}
```

#### `200` Snippet compartido

```http
GET /snippets/7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50
X-Dev-Actor-Id: dev-juan
```

```json
{"id":"7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50","name":"Sum","description":"Adds two numbers","language":"printscript","version":"1.1","extension":"prs","ownerId":"dev-thiago","relation":"shared","lint":{"status":"pending","reason":"NOT_EVALUATED","diagnostics":[],"codeHash":null,"rulesRevision":null,"processorRevision":null},"code":"let a: number = 1;\nprintln(a + 2);","capabilities":{"modify":false,"share":false}}
```

#### `404` Sin acceso

```http
GET /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40
X-Dev-Actor-Id: dev-ana
```

```json
{"type":"urn:snippets:problem:snippet-not-found","title":"Not Found","status":404,"detail":"There is no snippet with that id.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40"}
```

#### `503` Storage no disponible

```http
GET /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40
X-Dev-Actor-Id: dev-juan
```

```json
{"type":"urn:snippets:problem:storage-unavailable","title":"Service Unavailable","status":503,"detail":"The snippet code is not available right now. Repeating the same request is safe.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40"}
```

## Lenguajes disponibles

`GET /languages` es público. Publica el catálogo de lenguajes, versiones y extensiones que el backend usa para validar el guardado.

#### `200` Catálogo

```http
GET /languages
```

```json
[{"language":"printscript","extension":"prs","versions":["1.0","1.1"]}]
```

## Vigencia de lint

Lista y detalle comprueban contenido, lenguaje, versión, propietario, reglas y revisión del procesador. Los resultados obsoletos se muestran como `pending` / `SUPERSEDED` sin diagnósticos históricos. Un fallo técnico vigente se muestra como `failed`. Si no se puede acreditar la revisión actual, se devuelve `502`. [Lint](linting-rules.md)
