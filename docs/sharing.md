# Compartir snippets — SNI-26, SNI-29 y SNI-32

Contrato para buscar destinatarios y compartir un snippet (US7), acordado en SNI-26.

| Operación | Estado |
| --- | --- |
| `GET /users` | **Implementado en SNI-29.** Sus ejemplos son tests: `UsersHttpTest` envía cada pedido y compara la respuesta completa. |
| `PUT /snippets/{id}/shares/{userId}` | **Implementado en SNI-32.** `SharingHttpTest` verifica sus ejemplos HTTP, permisos y reintentos. |

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

Los parámetros se validan antes que la identidad, como en el listado: un pedido
con parámetros inválidos y sin identidad responde `400`.

### Directorio de desarrollo — SNI-29

Todavía no hay usuarios reales: Auth0 queda para una entrega posterior. Mientras
tanto, los destinatarios salen de un catálogo de desarrollo explícito.

| Pieza | Qué hace |
| --- | --- |
| `application-dev.properties` | Declara los usuarios (`development.users[i].id` y `.name`). Spring lo carga solo con el perfil `dev`. |
| `DevelopmentUsers` | Lee esa lista. Si un `id` o un `name` está en blanco, o un `id` se repite, el servicio no arranca. |
| `UserDirectory` | Puerto: `search(name, excluding, page)` y `contains(id)`. Es el punto de reemplazo por un directorio real. |
| `ConfiguredUserDirectory` | Implementación en memoria sobre el catálogo: filtra, excluye al actor, ordena y pagina. |
| `SearchUsers` | Caso de uso: resuelve la identidad y busca excluyendo al actor. |
| `UsersController` | `GET /users`: valida parámetros (`ParsedUserSearch`) y responde `{page, page_size, count, users}`. |

- El catálogo contiene los cinco usuarios de [ui-integration.md](ui-integration.md#usuarios-de-desarrollo).
  Sus `id` son los mismos que se envían en `X-Dev-Actor-Id` y que guarda Permissions.
- Los `id` no cambian entre reinicios mientras la configuración sea la misma. Se
  pueden reemplazar sin recompilar con variables de entorno, por ejemplo
  `DEVELOPMENT_USERS_0_ID` y `DEVELOPMENT_USERS_0_NAME`.
- Sin el perfil `dev` el catálogo queda vacío y `GET /users` responde `401`, como el
  resto de la API: el catálogo nunca autentica a nadie.
- La respuesta solo expone `id` y `name`. No hay contraseñas, tokens ni perfiles.
- `contains(id)` indica si un destinatario existe. Lo usa la operación de compartir
  (SNI-32) para que un destinatario desconocido nunca reciba un share.
- Cuando se integre Auth0 se escribe otra implementación de `UserDirectory`. El caso
  de uso, el controller y la respuesta no cambian.

### Decisiones — SNI-29

Propuestas en el plan de SNI-29 y aprobadas por Juan el 24/10/2026 («todas las
recomendadas, dale armalo»). Estado: implementadas y verificadas por los tests.

| Decisión | Alternativa descartada | Beneficio | Costo aceptado |
| --- | --- | --- | --- |
| Catálogo en `application-dev.properties` | Lista fija en Kotlin con `@Profile("dev")`; archivo JSON | Configurable sin recompilar y solo activo en `dev` | Datos de prueba visibles en el repositorio |
| Sin perfil `dev`: catálogo vacío y `401` | Endpoint solo en `dev` (404 afuera) | Igual que el resto de la API, sin cableado condicional | El bean existe aunque esté vacío |
| Catálogo inválido impide arrancar | Descartar entradas inválidas | Ningún destinatario ambiguo | Un error de configuración frena el servicio |
| Búsqueda en memoria | Tabla en la base | Sin migración ni perfiles persistidos | Solo sirve para catálogos chicos |
| `contains(id)` en el puerto | Que SNI-32 lo agregue | El directorio decide quién existe | Un método sin uso hasta SNI-32 |
| Reglas de parámetros en `QueryParameters` | Copiarlas | Mismas reglas en listado y usuarios | `ParsedListing` cambia de lugar sus helpers, sin cambiar comportamiento |
| Reusar `PageRequest` del paquete `metadata` | Moverlo o duplicarlo | Un solo concepto de página | `users` depende de un tipo de `metadata` |

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

La respuesta no trae el snippet. El adaptador de la UI devuelve el acknowledgment
`{snippetId,userId}` y los hooks invalidan las consultas afectadas por separado.
Una falla del refresco posterior no convierte un share confirmado en fallido.

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

`share-denied` e `invalid-recipient` forman parte del catálogo `SnippetProblem`.

### Por qué así

| Decisión | Motivo | Descartado |
| --- | --- | --- |
| `PUT` con el destinatario en la ruta | Repetirlo deja el mismo estado; no requiere clave de idempotencia. | `POST /snippets/{id}/shares` con body: necesitaría una clave para no duplicar. |
| Permissions verifica el owner al registrar | Un solo lugar decide el ownership y no hay ventana entre comprobar y guardar. | Que Snippets pregunte `can-modify` y después registre: son dos pedidos y otra regla. |
| Directorio de usuarios en Snippets | Snippets es el backend de la UI y la búsqueda es temporal hasta Auth0. | Guardar perfiles en Permissions: no le corresponde. |
| Validar el destinatario contra el directorio | Evita shares a identidades inexistentes que nadie podría usar. | Aceptar cualquier texto. |

## Implementación y decisiones — SNI-32

Plan aprobado por Juan Francisco el 2026-10-24 con «bueno me parece bien el plan, podes implementar».
El contrato SNI-26 ya fija el endpoint PUT, estados, orden de validaciones y permiso de solo lectura.

| Pieza | Responsabilidad |
| --- | --- |
| `SnippetSharingController` / `ShareOutcomeTranslator` | Ruta sin body, identidad del request y traducción a HTTP |
| `ShareSnippet` / `ShareOutcome` | Coordinar identidad, destinatario, metadatos confirmados y registro remoto |
| `ShareRegistrar` / `HttpShareRegistrar` | Capacidad acotada de registrar un share; cliente HTTP real de Permissions |
| `PermissionsHttpTransport` / `PermissionsResponseDecoder` | Transporte existente, timeouts, JSON y validación estricta de confirmaciones/errores |

- Se sigue la separación y composición existentes: resultados explícitos e inmutables, sin HTTP en el caso de uso,
  sin herencia de comportamiento ni nuevas dependencias/migraciones/tablas de permisos en Snippets.
- La identidad del solicitante no viene del body público. El directorio comprueba el destinatario; Permissions conserva
  la comprobación atómica de ownership y la unicidad del share. No se consulta primero can-modify para autorizar una escritura posterior.
- El share concede acceso al mismo recurso, no copia código ni congela una versión. El lector sigue viendo actualizaciones
  autorizadas del propietario a través del listado/detalle existentes.
- Un 200/201 de Permissions solo confirma si el JSON y los identificadores coinciden. Un 403 solo se traduce a rechazo
  conocido si es Problem Details válido y su relación es shared/none; errores inválidos son dependencia fallida.
- **Reintento explícito aprobado:** no hay reintentos automáticos ni borrado compensatorio ante un timeout. Beneficio:
  flujo visible y sin escrituras ocultas. Costo: el usuario debe reintentar cuando falta confirmación. La alternativa de
  reintentos automáticos acotados agregaría intentos/esperas; puede incorporarse posteriormente sin cambiar el contrato PUT.
  Un share puede haberse guardado aunque se pierda la respuesta: repetirlo devuelve 200 sin duplicar accesos.
- No se leen ni modifican archivos de storage para compartir. La aceptación con Asset Service real es SNI-36; Auth0,
  revocación, edición compartida y ejecución de tests siguen fuera de alcance.

## Pruebas reproducibles — SNI-32

Con JDK 21, credenciales de Packages y Docker disponibles:

```powershell
.\gradlew.bat build
```

- `ShareSnippetTest`: reglas y secuencia sin servidor/base, incluidos ausencia de identidad, destinatario inválido,
  metadatos ausentes/indisponibles y resultados de Permissions sin reintentos.
- `ShareRegistrationTest`: cliente HTTP y contratos de confirmación/error, codificación de destinatarios como un segmento,
  actor exacto en JSON, timeout y servidor inaccesible.
- `SharingHttpTest`: HTTP de Snippets + PostgreSQL/storage reales con respuestas controladas de Permissions solo en tests.
  Cubre ejemplos de esta página, Unicode/CRLF, lectura compartida, bloqueo de edición/resharing, tercero sin acceso,
  PENDING, fallas y reintento tras un registro cuya confirmación se demoró hasta el timeout.
- `src/test/ui/sharing.cy.ts`: prueba optativa de UI + servicios locales reales, sin simular respuestas de API.
  Requiere UI SNI-35, Vite en 5173 con `UI_DEV_ACTOR=dev-juanfran`, `API_PROXY_TARGET` al Snippets recién compilado,
  y Permissions/PrintScript/PostgreSQL disponibles. Crea un snippet identificado `SNI-32 browser ...` y un share a Ana.
  Para verificar la vista de Ana solo reemplaza la configuración pública de identidad dev en el navegador del test;
  conserva respuestas reales en todas las rutas `/api`. Comprueba editor de solo lectura y acciones deshabilitadas,
  además del rechazo HTTP real a editar/volver a compartir y del 404 para un tercero.

Para correr el spec desde el repo UI sin incorporarlo a sus pruebas de contrato:

```powershell
$sharingSpec = Join-Path $PWD 'cypress/e2e/sni-32-local.cy.ts'
if (Test-Path -LiteralPath $sharingSpec) { throw 'El spec temporal ya existe' }
Copy-Item -LiteralPath '../snippets-service/src/test/ui/sharing.cy.ts' -Destination $sharingSpec
try {
    npm.cmd exec -- cypress run --config 'baseUrl=http://127.0.0.1:5173,specPattern=cypress/e2e/sni-32-local.cy.ts' --spec 'cypress/e2e/sni-32-local.cy.ts'
    if ($LASTEXITCODE -ne 0) { throw 'Falló la verificación de sharing' }
} finally {
    Remove-Item -LiteralPath $sharingSpec
}
```

Esta verificación local no debe ejecutarse contra producción ni sustituye la aceptación integral con Asset Service oficial.

### Evidencia ejecutada — 2026-10-24

- `gradlew.bat build --no-daemon` con `PRINTSCRIPT_SERVICE_URL=http://127.0.0.1:8082` y
  `PERMISSIONS_SERVICE_URL=http://127.0.0.1:8081`: **305 tests, 0 fallos, 0 errores, 0 omitidos**;
  ktlint, detekt y empaquetado correctos. La suite incluye 18 pruebas nuevas de SNI-32.
- Cypress 13.8.1 / Electron 118: **1 recorrido aprobado, 0 fallos**, UI SNI-35 real en 5173,
  backend de esta rama compilado en 18080, Permissions de dev `949885c` en 8081, PrintScript en 8082 y PostgreSQL locales.
  No se modificaron código de UI, Permissions ni infra. El spec temporal del repo UI se eliminó al terminar.
- Registro creado para la prueba: `0deec4cf-d332-4ac9-aad3-72586cdf9aff`, nombre `SNI-32 browser 1792880145617`.
  Actor owner `dev-juanfran`, destinatario `dev-ana` y tercero `dev-bruno`.
- El navegador confirmó share 201 y cierre del modal; el mismo PUT respondió 200. Ana listó el recurso una sola vez,
  leyó `println(32);\r\n` exacto y recibió capacidades de solo lectura. Actualizar y volver a compartir respondieron 403;
  el tercero recibió 404. La vista de Ana mostró el editor de solo lectura y acciones deshabilitadas.
- Captura local ignorada por Git, en el repo UI: `cypress/screenshots/sni-32-local.cy.ts/sni32-real-sharing.png`.
  El recorrido usó storage local; la aceptación integral de SNI-36 conserva su comprobación con Asset Service real.
