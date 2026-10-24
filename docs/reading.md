# Consultas de snippets — SNI-26

Contrato propuesto para listar snippets (US5), ver su detalle (US6) y consultar
los lenguajes disponibles (US1 y US3). **No está implementado.** Los ejemplos
todavía no se ejecutan como tests; cuando exista cada endpoint, deben pasar a
verificarse como los de [creation.md](creation.md).

La representación del snippet, el lint, las capacidades, la identidad y la
política de errores comunes están en [ui-integration.md](ui-integration.md).

## Acceso

Un actor ve un snippet cuando es su owner o cuando el owner se lo compartió.
Permissions es la única fuente de esa relación ([access.md de Permissions](https://github.com/JJT-INGSIS/permissions-service/blob/dev/docs/access.md)).
Snippets no decide quién es owner ni guarda shares.

| Relación | Significado |
| --- | --- |
| `owned` | El actor es el owner. |
| `shared` | El owner se lo compartió al actor. |

Solo se muestran snippets confirmados. Una creación `PENDING` no aparece en la
lista ni en el detalle, aunque Permissions ya tenga su owner.

## Listar

`GET /snippets`

| Header | Obligatorio | Descripción |
| --- | --- | --- |
| `X-Dev-Actor-Id` | Sí | Identidad de desarrollo. Solo con el perfil Spring `dev`. |

| Parámetro | Default | Valores | Descripción |
| --- | --- | --- | --- |
| `relation` | `all` | `all`, `owned`, `shared` | Propios, compartidos o ambos. |
| `name` | sin filtro | texto | Contiene el texto, sin distinguir mayúsculas. Vacío equivale a no filtrar. No se recorta. |
| `language` | sin filtro | texto | Igual exacto al lenguaje del snippet. Vacío equivale a no filtrar. |
| `sort` | `name` | `name`, `language` | Campo de orden. |
| `direction` | `asc` | `asc`, `desc` | Sentido del orden. |
| `page` | `0` | entero `>= 0` | Número de página. La primera es `0`. |
| `page_size` | `10` | entero de `1` a `100` | Tamaño de página. Cubre las opciones de la tabla de la UI: 10, 25, 50 y 100. |

- Cada parámetro admite un solo valor. Un valor repetido o fuera de rango responde `400`.
- Un parámetro desconocido responde `400`. En particular, filtrar u ordenar por
  lint (`lint`, `compliance`, `sort=lint`) responde `400` mientras no exista el
  lint: no se ignora para no aparentar un filtro aplicado.

### Orden y página

1. Permissions entrega los IDs que el actor puede ver con la relación pedida.
2. Snippets aplica sobre esos IDs los filtros de nombre y lenguaje.
3. Ordena **todo** el resultado filtrado, no solo la página.
4. Recién después corta la página.

El orden es estable: ante valores iguales del campo pedido, desempata por `id`
ascendente. `name` y `language` se comparan sin distinguir mayúsculas. Repetir
el mismo pedido sin cambios en los datos devuelve los mismos snippets en el
mismo orden.

`count` es la cantidad total de snippets que cumplen los filtros dentro de lo que
el actor puede ver, sumando todas las páginas. Un ID que Permissions conoce pero
que en Snippets no existe o sigue pendiente no se muestra ni se cuenta.

Una página posterior a la última responde `200` con `snippets` vacío y el mismo
`count`. Una lista vacía significa que no hay resultados: nunca reemplaza a un error.

Listar no lee el código del storage. Cada elemento trae solo metadatos.

### Respuesta

`200 OK`, `application/json`:

| Campo | Tipo | Descripción |
| --- | --- | --- |
| `page` | número | La página pedida. |
| `page_size` | número | El tamaño pedido. |
| `count` | número | Total de resultados en todas las páginas. |
| `snippets` | lista | Snippets de esta página, sin `code` ni `capabilities`. |

### Ejemplos

#### `200` Primera página, propios y compartidos

```http
GET /snippets?page=0&page_size=2
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":2,"count":3,"snippets":[{"id":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","name":"Hello","description":null,"language":"printscript","version":"1.0","extension":"prs","ownerId":"dev-juan","relation":"owned","lint":{"status":"not-implemented"}},{"id":"7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50","name":"Sum","description":"Adds two numbers","language":"printscript","version":"1.1","extension":"prs","ownerId":"dev-thiago","relation":"shared","lint":{"status":"not-implemented"}}]}
```

#### `200` Solo compartidos, filtrados por nombre

```http
GET /snippets?relation=shared&name=su&page=0&page_size=10
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":10,"count":1,"snippets":[{"id":"7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50","name":"Sum","description":"Adds two numbers","language":"printscript","version":"1.1","extension":"prs","ownerId":"dev-thiago","relation":"shared","lint":{"status":"not-implemented"}}]}
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

#### `400` Filtro por lint

```http
GET /snippets?lint=compliant
X-Dev-Actor-Id: dev-juan
```

```json
{"type":"urn:snippets:problem:invalid-request","title":"Bad Request","status":400,"detail":"The body, one of its fields, a header or the snippet id is missing or invalid.","instance":"/snippets","parameter":"lint"}
```

#### `502` Permissions no disponible

```http
GET /snippets
X-Dev-Actor-Id: dev-juan
```

```json
{"type":"urn:snippets:problem:dependency-failure","title":"Bad Gateway","status":502,"detail":"A service this operation depends on did not answer correctly. Repeating the same request is safe.","instance":"/snippets"}
```

### Errores

| Status | `type` | Significado |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Parámetro desconocido, repetido o inválido. `parameter` lo nombra. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions no respondió según su contrato o tardó más que el límite. |
| `503` | `urn:snippets:problem:metadata-unavailable` | Falló la base de Snippets. |

## Ver el detalle

`GET /snippets/{id}`

| Parte | Obligatorio | Descripción |
| --- | --- | --- |
| `{id}` en la ruta | Sí | UUID canónico del snippet. |
| `X-Dev-Actor-Id` | Sí | Identidad de desarrollo. Solo con el perfil Spring `dev`. |

### Secuencia

| Paso | Qué hace | Si falla |
| --- | --- | --- |
| 1 | Resuelve la identidad. | `401` |
| 2 | Valida que `{id}` sea un UUID canónico. | `400` |
| 3 | Busca los metadatos. | Inexistente o pendiente: `404`. Base caída: `503 metadata-unavailable`. |
| 4 | Pregunta a Permissions la relación del actor con el snippet. | Sin acceso: `404`. Permissions caído: `502`. |
| 5 | Lee el código desde el storage con la referencia guardada. | Contenido ausente: ver [Lectura durante una actualización](#lectura-durante-una-actualización). Storage caído: `503 storage-unavailable`. |
| 6 | Responde el snippet con `code` y `capabilities`. | — |

El código se lee solo después de comprobar el acceso. Un actor sin acceso nunca
provoca una lectura del storage.

### Lectura durante una actualización

La actualización guarda el código nuevo, cambia datos y referencia en una sola
sentencia SQL y recién después borra el código anterior ([update.md](update.md)).
Un detalle que leyó los metadatos y la referencia vieja justo antes de esa
sentencia puede encontrar el archivo viejo ya borrado.

| Situación al leer el código | Qué hace el detalle |
| --- | --- |
| El contenido existe | Responde metadatos y código de la misma lectura. |
| Falta y, al releer los metadatos, la referencia cambió | Hubo una actualización en el medio: repite la lectura **una vez** con los metadatos y la referencia nuevos. |
| Falta y la referencia no cambió, o falta otra vez después de repetir | `503 storage-unavailable`. Repetir el pedido es seguro. |

Metadatos y código siempre salen de la misma referencia: nunca se responden
datos nuevos con código viejo ni al revés. No hace falta historial de versiones:
alcanza con releer la referencia, que cambia en una sola sentencia junto con los
datos. El reintento es uno solo para que una edición continua no deje el pedido
en un ciclo.

### Respuesta

`200 OK`, `application/json`: los campos de la lista más `code` y `capabilities`.

### Ejemplos

#### `200` Snippet propio

```http
GET /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40
X-Dev-Actor-Id: dev-juan
```

```json
{"id":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","name":"Hello","description":null,"language":"printscript","version":"1.0","extension":"prs","ownerId":"dev-juan","relation":"owned","lint":{"status":"not-implemented"},"code":"println(\"Hello\");","capabilities":{"modify":true,"share":true}}
```

#### `200` Snippet compartido

```http
GET /snippets/7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50
X-Dev-Actor-Id: dev-juan
```

```json
{"id":"7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50","name":"Sum","description":"Adds two numbers","language":"printscript","version":"1.1","extension":"prs","ownerId":"dev-thiago","relation":"shared","lint":{"status":"not-implemented"},"code":"let a: number = 1;\nprintln(a + 2);","capabilities":{"modify":false,"share":false}}
```

#### `404` Sin acceso

Inexistente, pendiente o sin acceso responden igual.

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
{"type":"urn:snippets:problem:storage-unavailable","title":"Service Unavailable","status":503,"detail":"The snippet code is not available. Repeating the same request is safe.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40"}
```

### Errores

| Status | `type` | Significado |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | `{id}` no es un UUID canónico. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `404` | `urn:snippets:problem:snippet-not-found` | No existe, sigue pendiente o el actor no tiene acceso. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado, incluido un snippet confirmado sin referencia de contenido. |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions no respondió según su contrato o tardó más que el límite. |
| `503` | `urn:snippets:problem:metadata-unavailable` | Falló la base de Snippets. |
| `503` | `urn:snippets:problem:storage-unavailable` | Falló el storage o el código no se pudo leer de forma coherente con una actualización simultánea. |

Los textos de `detail` son los del catálogo `SnippetProblem` y pueden cambiar.
Hoy `metadata-unavailable` y `storage-unavailable` dicen que los datos «could not be
saved». Al implementar las lecturas, sus textos deben pasar a ser neutros, como el
del ejemplo, porque el mismo `type` sirve para guardar y para leer.

## Lenguajes disponibles

`GET /languages`

Reemplaza a `getFileTypes()` de la UI. Sirve para elegir lenguaje y versión al
crear y para reconocer la extensión de un archivo subido (US3). No depende del
actor: no requiere `X-Dev-Actor-Id`.

El catálogo sale de los validadores de `Language`, la misma fuente que decide si
un lenguaje y una versión se aceptan al guardar. Así la UI no ofrece una versión
que el backend rechaza.

#### `200` Catálogo

```http
GET /languages
```

```json
[{"language":"printscript","extension":"prs","versions":["1.0","1.1"]}]
```

| Campo | Tipo | Descripción |
| --- | --- | --- |
| `language` | string | El valor que se envía como `language`. |
| `extension` | string | Extensión de archivo, sin punto. |
| `versions` | lista de strings | Valores aceptados como `version`. |

Si falla, responde `500 technical-failure`. No hay versión por defecto: la UI la
elige de `versions`.

## Fuera de alcance

Descarga del código formateado (US13), infracciones de lint, tests del snippet y
ejecución. Ver las funciones deshabilitadas en [ui-integration.md](ui-integration.md).
