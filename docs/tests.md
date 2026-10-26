# Tests de un snippet — SNI-46

Contrato para crear y listar los tests de un snippet (US8), acordado en SNI-45.

| Operación | Estado |
| --- | --- |
| `POST /snippets/{id}/tests` | **Implementado en SNI-46.** `TestCasesHttpTest` envía los ejemplos de abajo y compara la respuesta. |
| `GET /snippets/{id}/tests` | **Implementado en SNI-46.** Ídem. |

Un test es una entrada (`inputs`) y la salida que se espera (`expectedOutputs`).
**Crearlo no lo ejecuta** ni llama a PrintScript: un test puede guardarse con una
salida que después no coincida. Ejecutarlo es US9, otro ticket.

La identidad, las capacidades y la política 403/404 están en
[ui-integration.md](ui-integration.md). Qué concede un share está en
[sharing.md](sharing.md#qué-concede-un-share).

## Quién puede qué

| | Owner | Usuario compartido | Cualquier otro |
| --- | --- | --- | --- |
| Crear un test | Sí (`201`) | No (`403 access-denied`) | No (`404`) |
| Listar los tests | Sí (`200`) | Sí (`200`) | No (`404`) |

Un snippet inexistente o **pendiente** responde `404`, igual que uno sin acceso, y
no se le pregunta nada a Permissions. Así un tercero no puede saber si el snippet existe.

## Body del POST

| Header | Obligatorio | Descripción |
| --- | --- | --- |
| `X-Dev-Actor-Id` | Sí | Identidad de desarrollo. Solo con el perfil Spring `dev`. |
| `Content-Type` | Sí | `application/json`. |

```json
{"inputs":["Ana"],"expectedOutputs":["Hola, Ana"]}
```

- El body tiene **exactamente** `inputs` y `expectedOutputs`, los dos arrays de strings.
  Un campo de más (incluidos `owner` o `actorId`), uno que falte, un `null`, un
  número, claves repetidas o texto después del JSON responden `400`. La identidad
  nunca sale del body.
- Se conservan exactos el **orden**, los **duplicados**, los **strings vacíos**, los
  espacios, los saltos de línea y el **Unicode**. Los arrays vacíos son válidos.
- Sin `Idempotency-Key`: reintentar un POST puede crear un test igual al anterior.

### Límites

| Límite | Valor | Si se excede |
| --- | --- | --- |
| Items en `inputs` | 100 | `413` con `"limit":"definition"` |
| Items en `expectedOutputs` | 100 | `413` con `"limit":"definition"` |
| Tamaño de cada string | 8 KiB **medidos en UTF-8** (`ñ` ocupa 2 bytes, `🚀` 4) | `413` con `"limit":"definition"` |
| Tests por snippet | 50 | `413` con `"limit":"testsPerSnippet"` |
| Carácter NUL (`\u0000`) o Unicode mal formado | No se aceptan: PostgreSQL no los guarda exactos | `400` |

El listado no está paginado; estos límites lo mantienen acotado. Están en
`TestCaseRules` y el de 50 lo hace cumplir la base dentro de una transacción, así
que dos pedidos simultáneos tampoco lo superan.

## Secuencia

| Paso | Qué hace | Si falla |
| --- | --- | --- |
| 1 | (POST) Lee el body estricto. | `400` |
| 2 | (POST) Aplica los límites. | `413` o `400` |
| 3 | Resuelve la identidad. | `401` |
| 4 | Valida `{id}`. | `400` con `"field":"snippetId"` |
| 5 | Comprueba que el snippet exista y esté confirmado. | `404`. Base caída: `503 metadata-unavailable`. |
| 6 | Pregunta a Permissions la relación del actor (una sola llamada). | Sin acceso: `404`. Permissions caído: `502`. |
| 7 | (POST) Exige que sea owner. | Compartido: `403 access-denied` |
| 8 | Guarda o lista en PostgreSQL. | Base caída: `503`. Límite por snippet: `413`. |

Un body inválido corta en el paso 1: no se consulta la identidad, la base ni
Permissions. Un error nunca se responde como un test creado ni como `{"items":[]}`.

## Respuestas

| Status | Body |
| --- | --- |
| `201 Created` (POST) | El test guardado: `{id, snippetId, inputs, expectedOutputs}` |
| `200 OK` (GET) | `{"items":[...]}` en orden de creación. Sin tests: `{"items":[]}`. |

`id` lo genera el servicio (UUID). `snippetId` es el UUID canónico del snippet.

## Ejemplos

En el ejemplo de creación el `id` es ilustrativo: el test comprueba que la respuesta
tenga los mismos campos, el mismo `snippetId` y exactamente los mismos arrays.

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

El duplicado y el string vacío vuelven tal cual.

#### `200` Tests de un snippet compartido

```http
GET /snippets/0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40/tests
X-Dev-Actor-Id: dev-thiago
```

```json
{"items":[{"id":"5b6c7d8e-9f01-4a2b-8c3d-4e5f60718293","snippetId":"0f8b3c1e-4a2d-4d3b-9b7a-2c6e5f1a9d40","inputs":["Juan"],"expectedOutputs":["Hola, Juan"]}]}
```

`dev-juan` le compartió el snippet a `dev-thiago`: puede ver sus tests.

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

Inexistente, pendiente o sin acceso responden igual.

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

## Errores

| Status | `type` | Significado |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Body inválido, o `{id}` no es un UUID (`"field":"snippetId"`). |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `403` | `urn:snippets:problem:access-denied` | Un usuario compartido intenta crear un test. |
| `404` | `urn:snippets:problem:snippet-not-found` | Inexistente, pendiente o sin acceso. |
| `413` | `urn:snippets:problem:payload-too-large` | Se superó un límite. `limit` dice cuál: `definition` o `testsPerSnippet`. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions no respondió bien. Repetir el GET es seguro. |
| `503` | `urn:snippets:problem:metadata-unavailable` | PostgreSQL no está disponible. Repetir el GET es seguro. |

**Diferencia con la tabla de SNI-45:** allí figuran `identity-required` (401) y
`dependency-unavailable` (503). Snippets usa los nombres que ya consume la UI en
el resto del servicio (`identity-unavailable` 401 y `dependency-failure` 502), así
el mismo fallo tiene el mismo `type` en todos los endpoints (decisión 1).

## Diseño

| Pieza | Paquete | Qué hace |
| --- | --- | --- |
| `V3__create_snippet_test_cases.sql` | migración | Tabla `snippet_test_cases`: una fila por test, `inputs` y `expected_outputs` como arrays JSONB de strings (la base rechaza cualquier otra cosa) y `creation_order` para listar en orden de creación. |
| `TestCaseStore` | `testcase` | Puerto: `create(snippetId, definition, maximumPerSnippet)` y `list(snippetId)`. |
| `JdbcTestCaseStore` | `metadata` | Implementación JDBC. Bloquea la fila del snippet (`FOR UPDATE`), cuenta y recién inserta: el límite se cumple con pedidos simultáneos. |
| `TestCaseRules` | `testcase` | Límites y caracteres válidos. No sabe de HTTP ni de la base. |
| `TestCaseRequestDecoder` | `http` | Lee el body estricto. |
| `ReadGate` / `ConfirmedSnippetGate` | `access` | "¿Puede este actor leer este snippet?": identidad → UUID → snippet confirmado → Permissions. Lo comparten el detalle (SNI-31) y los tests. |
| `CreateTestCase` / `ListTestCases` | `testcase` | Casos de uso: reglas → gate → (owner) → store. |
| `TestCasesController` / `TestCaseOutcomeTranslator` | `http` | Rutas y Problem Details. |

## Decisiones — SNI-46

Propuestas en el plan de SNI-46 y aprobadas por Juan el 26/10/2026 («vamos con tus
recomendaciones»).

| # | Decisión | Alternativa descartada | Beneficio | Costo aceptado |
| --- | --- | --- | --- | --- |
| 1 | Nombres de error de snippets + `access-denied` 403 y `payload-too-large` 413 | Los nombres de la tabla de SNI-45 | Mismo fallo, mismo `type` en todo el servicio | Difiere de SNI-45 en dos nombres; se avisa al equipo |
| 2 | Acceso compartido en `ReadGate`, usado también por el detalle | Copiar la lógica | Una sola regla de acceso | `ReadSnippet` cambió por dentro; sus tests lo protegen |
| 3 | Una consulta a `/access` de Permissions: owned crea, shared `403`, sin acceso `404` | `can-modify` para crear | Un puerto y una llamada | Permissions no vuelve a verificar al guardar |
| 4 | Tabla con arrays JSONB y orden de creación | `text[]`; una fila por item | Orden y duplicados exactos, validado por la base | Migración nueva para cambiarlo |
| 5 | Límites: 100 items, 8 KiB por string, 50 tests por snippet | Sin límites | Listado sin paginar y acotado | Un test muy grande no se puede guardar |
| 6 | POST sin `Idempotency-Key` | Exigirla como `POST /snippets` | Igual al contrato; la UI no la manda | Un reintento puede duplicar un test |
| 7 | Este documento con ejemplos ejecutados por tests | OpenAPI (springdoc) | Convención del repo, sin dependencias | No hay UI interactiva de la API |
| 8 | Paquete `testcase` y clases `TestCase…` | `SnippetTest…` | No se confunde con tests de JUnit | La ruta HTTP se sigue llamando `/tests` |

Todas son reversibles; la 4 necesita una migración nueva.
