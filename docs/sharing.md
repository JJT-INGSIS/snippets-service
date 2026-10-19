# Compartir snippets — SNI-26

Contrato propuesto para buscar destinatarios y compartir un snippet (US7).
**No está implementado.** Los ejemplos todavía no se ejecutan como tests.

La identidad, el directorio de usuarios de desarrollo, las capacidades y la
política 403/404 están en [ui-integration.md](ui-integration.md).

## Qué concede un share

| Puede | El owner | Un usuario compartido |
| --- | --- | --- |
| Ver el snippet en la lista y su detalle | Sí | Sí |
| Ver y ejecutar sus tests (US8–9, futuro) | Sí | Sí |
| Modificarlo | Sí | No |
| Compartirlo | Sí | No |

El contenido y los tests no se copian: el usuario compartido lee el mismo snippet.
No existe dejar de compartir; US7 no lo pide.

Permissions verifica el ownership y guarda el share en una sola operación
([access.md de Permissions](https://github.com/JJT-INGSIS/permissions-service/blob/dev/docs/access.md)).
Comprobar en Snippets que el snippet existe no reemplaza esa verificación.

## Buscar destinatarios

`GET /users`

Reemplaza a `getUserFriends(name, page, pageSize)` de la UI.

| Header | Obligatorio | Descripción |
| --- | --- | --- |
| `X-Dev-Actor-Id` | Sí | Identidad de desarrollo. Solo con el perfil Spring `dev`. |

| Parámetro | Default | Valores | Descripción |
| --- | --- | --- | --- |
| `name` | sin filtro | texto | El nombre contiene el texto, sin distinguir mayúsculas. Vacío equivale a no filtrar. |
| `page` | `0` | entero `>= 0` | La primera página es `0`. |
| `page_size` | `10` | entero de `1` a `100` | Tamaño de página. |

- La fuente es el directorio de usuarios de desarrollo. Con Auth0 cambia la
  fuente, no esta respuesta.
- No incluye al actor que pregunta: nadie se comparte un snippet a sí mismo.
- Ordena por `name` y desempata por `id`, sin distinguir mayúsculas.
- `count` es el total de usuarios que cumplen el filtro, sumando todas las páginas.
- Los parámetros siguen las mismas reglas que el listado de snippets: un valor
  repetido, fuera de rango o desconocido responde `400`.

### Ejemplos

#### `200` Usuarios que contienen «an»

```http
GET /users?name=an&page=0&page_size=5
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":5,"count":2,"users":[{"id":"dev-ana","name":"Ana"},{"id":"dev-juanfran","name":"Juan Francisco"}]}
```

`dev-juan` también contiene «an», pero no aparece porque es quien pregunta.

#### `200` Sin coincidencias

```http
GET /users?name=zzz&page=0&page_size=5
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":5,"count":0,"users":[]}
```

### Errores

| Status | `type` | Significado |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Parámetro desconocido, repetido o inválido. `parameter` lo nombra. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. |

## Compartir

`PUT /snippets/{id}/shares/{userId}`

Reemplaza a `shareSnippet(snippetId, userId)` de la UI. No lleva body.

| Parte | Obligatorio | Descripción |
| --- | --- | --- |
| `{id}` en la ruta | Sí | UUID canónico del snippet. |
| `{userId}` en la ruta | Sí | `id` del destinatario, tal como lo devuelve `GET /users`. Se codifica como segmento de URL. |
| `X-Dev-Actor-Id` | Sí | Identidad de desarrollo. Solo con el perfil Spring `dev`. |

Es `PUT` porque el resultado no depende de cuántas veces se repita: el mismo
destinatario queda con un solo share. Por eso no necesita `Idempotency-Key`.

### Secuencia

| Paso | Qué hace | Si falla |
| --- | --- | --- |
| 1 | Resuelve la identidad. | `401` |
| 2 | Valida `{id}` y `{userId}`. | `400` |
| 3 | Comprueba que el destinatario esté en el directorio y no sea el actor. | `422 invalid-recipient` |
| 4 | Comprueba que el snippet exista y esté confirmado. | Inexistente o pendiente: `404`. Base caída: `503 metadata-unavailable`. |
| 5 | Pide a Permissions que registre el share en nombre del actor. Permissions verifica que sea owner. | Sin acceso: `404`. Usuario compartido: `403 share-denied`. Permissions caído: `502`. |
| 6 | Responde el share confirmado. | — |

No se modifica ningún acceso salvo en el paso 5, y solo si Permissions confirma.
Si Permissions no responde, el share no se informa como hecho. Repetir el mismo
pedido es seguro y resuelve el resultado sin duplicar accesos.

### Respuesta

| Status | Significado |
| --- | --- |
| `201 Created` | El share se registró en este pedido. |
| `200 OK` | El destinatario ya tenía el share. No cambia nada. |

```json
{"snippetId":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","userId":"dev-thiago"}
```

La respuesta no trae el snippet. El adaptador de la UI devuelve el snippet que ya
tiene cargado o vuelve a pedir el detalle.

### Ejemplos

#### `201` Compartir por primera vez

```http
PUT /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/shares/dev-thiago
X-Dev-Actor-Id: dev-juan
```

```json
{"snippetId":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","userId":"dev-thiago"}
```

#### `200` Repetir el mismo share

```http
PUT /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/shares/dev-thiago
X-Dev-Actor-Id: dev-juan
```

```json
{"snippetId":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","userId":"dev-thiago"}
```

#### `403` Un usuario compartido intenta compartir

```http
PUT /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/shares/dev-ana
X-Dev-Actor-Id: dev-thiago
```

```json
{"type":"urn:snippets:problem:share-denied","title":"Forbidden","status":403,"detail":"Only the owner can share this snippet.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/shares/dev-ana"}
```

#### `404` Sin acceso

```http
PUT /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/shares/dev-bruno
X-Dev-Actor-Id: dev-ana
```

```json
{"type":"urn:snippets:problem:snippet-not-found","title":"Not Found","status":404,"detail":"There is no snippet with that id.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/shares/dev-bruno"}
```

#### `422` Compartirse a uno mismo

```http
PUT /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/shares/dev-juan
X-Dev-Actor-Id: dev-juan
```

```json
{"type":"urn:snippets:problem:invalid-recipient","title":"Unprocessable Content","status":422,"detail":"The recipient is not a known user other than the requester.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/shares/dev-juan"}
```

### Errores

| Status | `type` | Significado | ¿Sirve reintentar? |
| --- | --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | `{id}` no es un UUID canónico o `{userId}` está en blanco. | No |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. | No |
| `403` | `urn:snippets:problem:share-denied` | **Nuevo.** El actor ve el snippet pero no es su owner. | No |
| `404` | `urn:snippets:problem:snippet-not-found` | No existe, sigue pendiente o el actor no tiene acceso. | No |
| `422` | `urn:snippets:problem:invalid-recipient` | **Nuevo.** El destinatario no está en el directorio o es el propio actor. | No |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. | Sí |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions no respondió según su contrato o tardó más que el límite. El share no está confirmado. | Sí |
| `503` | `urn:snippets:problem:metadata-unavailable` | Falló la base de Snippets. | Sí |

`share-denied` e `invalid-recipient` se agregan al catálogo `SnippetProblem`
cuando se implemente el endpoint.

### Por qué así

| Decisión | Motivo | Descartado |
| --- | --- | --- |
| `PUT` con el destinatario en la ruta | Repetirlo deja el mismo estado; no requiere clave de idempotencia. | `POST /snippets/{id}/shares` con body: necesitaría una clave para no duplicar. |
| Permissions verifica el owner al registrar | Un solo lugar decide el ownership y no hay ventana entre comprobar y guardar. | Que Snippets pregunte `can-modify` y después registre: son dos pedidos y otra regla. |
| Directorio de usuarios en Snippets | Snippets es el backend de la UI y la búsqueda es temporal hasta Auth0. | Guardar perfiles en Permissions: no le corresponde. |
| Validar el destinatario contra el directorio | Evita shares a identidades inexistentes que nadie podría usar. | Aceptar cualquier texto. |
