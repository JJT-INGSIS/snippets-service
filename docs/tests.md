# Casos de prueba de un snippet

`GET /snippets/{id}/tests` lista los tests de un snippet confirmado para el propietario o un lector compartido. Devuelve `200` con `{"items":[…]}` en orden de creación, sin paginación.

`POST /snippets/{id}/tests` permite al propietario guardar un test y devuelve `201` con `id`, `snippetId`, `inputs` y `expectedOutputs`. El JSON exige exactamente `inputs` y `expectedOutputs`, dos arrays de strings. Conserva los valores, su orden y sus duplicados. Se admiten arrays vacíos. Repetir un POST puede crear otro test.

Cada array admite 100 elementos y cada string hasta 8 KiB en UTF-8. Se rechazan NUL y Unicode mal formado. PostgreSQL hace cumplir un máximo de 50 tests por snippet, incluso ante guardados simultáneos.

Un usuario compartido recibe `403` al intentar crear. Un snippet pendiente, inexistente o inaccesible devuelve `404`. Para ejecutar tests guardados, consultá [Ejecución](interactive-execution.md).

## Errores

| Status | `type` | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Body inválido, o `{id}` no es un UUID (`"field":"snippetId"`). |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `403` | `urn:snippets:problem:access-denied` | Un usuario compartido intenta crear un test. |
| `404` | `urn:snippets:problem:snippet-not-found` | Inexistente, pendiente o sin acceso. |
| `413` | `urn:snippets:problem:payload-too-large` | Se superó un límite. `limit` dice cuál: `definition` o `testsPerSnippet`. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions no respondió bien. Repetir el GET es seguro. |
| `503` | `urn:snippets:problem:metadata-unavailable` | PostgreSQL no está disponible. Repetir el GET es seguro. |

## Ejemplos

#### `201` Crear un test

```http
POST /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests
X-Dev-Actor-Id: dev-juan
Content-Type: application/json

{"inputs":["Ana","Ana",""],"expectedOutputs":["Hola, Ana","Hola, Ana","Hola, "]}
```

```json
{"id":"9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d","snippetId":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","inputs":["Ana","Ana",""],"expectedOutputs":["Hola, Ana","Hola, Ana","Hola, "]}
```

#### `200` Tests de un snippet compartido

```http
GET /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests
X-Dev-Actor-Id: dev-thiago
```

```json
{"items":[{"id":"5b6c7d8e-9f01-4a2b-8c3d-4e5f60718293","snippetId":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","inputs":["Juan"],"expectedOutputs":["Hola, Juan"]}]}
```

#### `200` Snippet sin tests

```http
GET /snippets/7d2e9a4f-1b3c-4e5d-8f6a-0b1c2d3e4f50/tests
X-Dev-Actor-Id: dev-juan
```

```json
{"items":[]}
```

#### `403` Un usuario compartido intenta crear

```http
POST /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests
X-Dev-Actor-Id: dev-thiago
Content-Type: application/json

{"inputs":[],"expectedOutputs":["Hola"]}
```

```json
{"type":"urn:snippets:problem:access-denied","title":"Forbidden","status":403,"detail":"Only the owner of this snippet can do this.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests"}
```

#### `404` Sin acceso

```http
GET /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests
X-Dev-Actor-Id: dev-ana
```

```json
{"type":"urn:snippets:problem:snippet-not-found","title":"Not Found","status":404,"detail":"There is no snippet with that id.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests"}
```

#### `400` Body inválido

```http
POST /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests
X-Dev-Actor-Id: dev-juan
Content-Type: application/json

{"inputs":[1],"expectedOutputs":[]}
```

```json
{"type":"urn:snippets:problem:invalid-request","title":"Bad Request","status":400,"detail":"The body, one of its fields, a header or the snippet id is missing or invalid.","instance":"/snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests"}
```
