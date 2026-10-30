# Integración con la UI de la cátedra — SNI-26

Contrato acordado entre el backend y la UI provista por la cátedra para US1–7.
Define una sola representación del snippet, cómo se traducen los tipos de la UI,
qué operaciones se conectan y cuáles quedan deshabilitadas.

**Estado:** creación (`POST /snippets`), actualización (`PUT /snippets/{id}`),
listado (`GET /snippets`, SNI-30), detalle (`GET /snippets/{id}`, SNI-31), catálogo
(`GET /languages`, SNI-54) y búsqueda de destinatarios (`GET /users`, SNI-29) están
implementados. Sharing (`PUT /snippets/{id}/shares/{userId}`, SNI-32) también está
implementado; sus ejemplos se verifican con `SharingHttpTest`.

| Documento | Contenido |
| --- | --- |
| [creation.md](creation.md) | `POST /snippets` (implementado) |
| [update.md](update.md) | `PUT /snippets/{id}` (implementado) |
| [reading.md](reading.md) | `GET /snippets`, `GET /snippets/{id}` y `GET /languages` (implementados) |
| [sharing.md](sharing.md) | `GET /users` y `PUT /snippets/{id}/shares/{userId}` (implementados) |
| [access.md de Permissions](https://github.com/JJT-INGSIS/permissions-service/blob/dev/docs/access.md) | API interna de acceso y shares que usa Snippets |

## Fuente revisada

UI `austral-ingsis/printscript-ui`, commit
`8ac3338873602e5263365670cd3b88d48202365e`: `SnippetOperations`, sus DTOs
(`snippet.ts`, `users.ts`, `pagination.ts`, `FileType.ts`) y la implementación
`FakeSnippetOperations`. La UI no trae una API HTTP: trae una interfaz con una
implementación falsa. Estas rutas son decisiones del equipo.

## Representación del snippet

Todas las respuestas usan los mismos nombres de campo.

| Campo | Tipo | En lista | En detalle | Origen |
| --- | --- | --- | --- | --- |
| `id` | string (UUID) | Sí | Sí | Snippets. Es el mismo en metadatos, contenido y Permissions. |
| `name` | string | Sí | Sí | Metadatos. |
| `description` | string o `null` | Sí | Sí | Metadatos. Es opcional. |
| `language` | string | Sí | Sí | Metadatos. |
| `version` | string | Sí | Sí | Metadatos. Obligatoria; no hay versión por defecto. |
| `extension` | string | Sí | Sí | Calculada a partir de `language` con el catálogo de `GET /languages`. No se guarda. |
| `ownerId` | string | Sí | Sí | Permissions. Identidad del owner. |
| `relation` | `owned` o `shared` | Sí | Sí | Permissions. Relación del actor que consulta con el snippet. |
| `lint` | objeto | Sí | Sí | Ver [Lint](#lint). |
| `code` | string | No | Sí | Storage de contenido. |
| `capabilities` | objeto | No | Sí | Calculado a partir de `relation`. Ver [Capacidades](#capacidades). |

La lista nunca trae `code`: listar no lee archivos del storage. El código se
obtiene en el detalle, de a un snippet por vez.

Los pedidos de creación y actualización aceptan únicamente `name`, `description`,
`language`, `version` y `code`. Cualquier otro campo, incluidos `extension`,
`ownerId` o `relation`, se rechaza con `400`.

## Traducción de los tipos de la UI

El adaptador HTTP que reemplaza a `FakeSnippetOperations` traduce entre los
tipos de la UI y este contrato. El backend no adopta los nombres de la UI cuando
contradicen un contrato ya implementado.

| UI | Backend | Decisión |
| --- | --- | --- |
| `content` | `code` | El adaptador renombra el campo en los dos sentidos. POST y PUT ya existen con `code` y sus tests lo verifican. |
| No existe `description` | `description` opcional | La UI agrega un campo opcional al crear y lo muestra en el detalle. |
| No existe `version` | `version` obligatoria | La UI agrega un selector con las versiones de `GET /languages`. El adaptador no inventa una versión por defecto. |
| `extension` en `CreateSnippet` | No se acepta | El adaptador no la envía. La respuesta la trae calculada para descargar el archivo. |
| `author` | `ownerId` | Se muestra el `ownerId`. Un nombre legible depende de la integración futura de usuarios. |
| `compliance` | `lint.status` | Ver [Lint](#lint). El tipo `ComplianceEnum` de la UI necesita el valor `not-implemented`. |
| `UpdateSnippet { content }` | Reemplazo completo | El adaptador envía `name`, `description`, `language` y `version` del detalle ya cargado, más el código editado. |
| `PaginatedSnippets` | `{page, page_size, count, snippets}` | Mismos nombres que `Pagination` de la UI. Los elementos no traen `code`. |
| `PaginatedUsers` | `{page, page_size, count, users}` | Mismos nombres que la UI. |
| `FileType[]` | `GET /languages` | Cada lenguaje trae además sus versiones. |
| El mock original devolvía `Snippet` desde `shareSnippet(...)` | Devuelve `{snippetId,userId}` | El adaptador confirma el share y los hooks refrescan las consultas por separado; no fabrican un Snippet ni condicionan el éxito a un GET posterior. |

Los parámetros y campos de paginación usan `page_size` como la UI
(`paginationParams`). El resto del contrato usa camelCase, como POST y PUT.

## Lint

El linting no está implementado. Toda respuesta lo informa sin simular un resultado:

```json
{"status":"not-implemented"}
```

| `status` | Significado | Disponible |
| --- | --- | --- |
| `not-implemented` | No existe evaluación de lint en el sistema. | Ahora |
| `pending` | Hay una evaluación pedida que todavía no terminó. | Futuro |
| `compliant` | La última evaluación vigente no encontró infracciones. | Futuro |
| `not-compliant` | La última evaluación vigente encontró infracciones. | Futuro |
| `failed` | La evaluación no pudo completarse por una falla técnica. No significa incumplimiento. | Futuro |

- No se devuelve `pending`, `compliant` ni `failed` mientras no exista el lint.
- La validez del parser no es lint. Todo snippet guardado ya pasó la validación de
  Language: ese dato no se informa como cumplimiento.
- Filtrar u ordenar por lint responde `400` hasta que exista la evaluación. No se
  ignora el parámetro, para no mostrar un resultado que parece filtrado sin estarlo.
- Las infracciones del detalle se agregan cuando exista el lint (US14–15).

## Capacidades

El detalle informa qué acciones puede hacer el actor que consulta.

```json
{"modify":true,"share":true}
```

| `relation` | `modify` | `share` | Lectura |
| --- | --- | --- | --- |
| `owned` | `true` | `true` | Sí |
| `shared` | `false` | `false` | Sí |

La UI usa estas capacidades para ocultar o deshabilitar acciones. **El backend
vuelve a verificar cada permiso**: ocultar un botón no autoriza ni impide nada.
Compartir concede lectura del snippet y de sus tests ([tests.md](tests.md)). No concede
edición ni volver a compartir.

## Identidad en desarrollo

Todas las operaciones sobre snippets y usuarios, incluidas las de lectura,
identifican al actor con `X-Dev-Actor-Id`, igual que POST y PUT. La única
excepción es `GET /languages`, que no depende del actor.

- Solo funciona con el perfil Spring `dev`, que activa el Compose local de infra.
  Sin ese perfil, esas operaciones responden `401 identity-unavailable`.
- No es autenticación: cualquiera que pueda llamar al servicio elige la identidad.
  No se habilita en producción. Auth0 no forma parte de esta tanda.
- El header sigue aceptando cualquier valor no blanco, como hasta ahora.

### Usuarios de desarrollo

Snippets tiene un directorio fijo de usuarios, activo solo con el perfil `dev`.
Sirve para elegir el actor en la UI y para buscar destinatarios de un share.

| `id` | `name` |
| --- | --- |
| `dev-juan` | Juan |
| `dev-juanfran` | Juan Francisco |
| `dev-thiago` | Thiago |
| `dev-ana` | Ana |
| `dev-bruno` | Bruno |

- Los `id` son estables: no cambian entre reinicios ni entre bases.
- La UI elige el actor desde su configuración de desarrollo, con uno de estos `id`,
  y lo envía en `X-Dev-Actor-Id`.
- Solo se puede compartir con un usuario del directorio.
- Con Auth0, el directorio y el `id` se reemplazan por los usuarios reales. El
  contrato de destinatarios (`{id, name}`) no cambia.
- Implementado en SNI-29: el catálogo vive en `application-dev.properties` y se
  puede reemplazar con variables de entorno. Ver
  [sharing.md](sharing.md#directorio-de-desarrollo--sni-29).

## Errores

Todas las operaciones responden errores como Problem Details
(`application/problem+json`) con `type` `urn:snippets:problem:<categoría>`.
`type` es el identificador estable; `title` y `detail` pueden cambiar.

### 403 o 404

| Situación | Respuesta |
| --- | --- |
| El snippet no existe | `404 snippet-not-found` |
| El snippet sigue pendiente de creación | `404 snippet-not-found` |
| El actor no tiene acceso al snippet | `404 snippet-not-found` |
| El actor lo puede leer, pero la acción es del owner | `403` |

Responder `404` cuando no hay acceso evita confirmar que un UUID existe. El `403`
queda para quien ya ve el snippet: un usuario compartido que intenta editar
(`modification-denied`) o compartir (`share-denied`).

**Pendiente de alinear:** el `PUT` implementado responde `403 modification-denied`
a cualquier actor que no es owner, incluido uno sin acceso, porque hoy Permissions
solo informa `can-modify`. Cuando exista la consulta de acceso, un actor sin acceso
recibirá `404`. Hasta entonces se conserva el comportamiento documentado en
[update.md](update.md).

### Indisponibilidad

| Falla | Respuesta | Nunca |
| --- | --- | --- |
| Permissions no responde, tarda o responde fuera de contrato | `502 dependency-failure` | Lista vacía o acceso denegado |
| Falla la base de Snippets | `503 metadata-unavailable` | Lista vacía o `404` |
| Falla el storage al leer el código | `503 storage-unavailable` | Detalle sin código |
| Error inesperado | `500 technical-failure` | Éxito |

Reintentar una lectura o un share es seguro: no modifica nada o es idempotente.

## Operaciones de la UI

| Operación de `SnippetOperations` | US | Backend | Estado |
| --- | --- | --- | --- |
| `createSnippet` | US1, US3 | `POST /snippets` | Implementado |
| `updateSnippetById` | US2, US4 | `PUT /snippets/{id}` | Implementado |
| `getFileTypes` | US1, US3 | `GET /languages` | Implementado (SNI-54), público y sin versión por defecto |
| `listSnippetDescriptors` | US5 | `GET /snippets` | Implementado (SNI-30), sin lint |
| `getSnippetById` | US6 | `GET /snippets/{id}` | Implementado (SNI-31), sin lint ni tests |
| `getUserFriends` | US7 | `GET /users` | Implementado (SNI-29) |
| `shareSnippet` | US7 | `PUT /snippets/{id}/shares/{userId}` | Implementado (SNI-32), lectura sin edición ni resharing |

El panel Output (`SnippetExecution`, US10) no pasa por `SnippetOperations`: usa
las rutas de ejecución de [SNI-49](interactive-execution.md) y de
[SNI-48](test-execution.md).

### Funciones que quedan deshabilitadas

No tienen backend en esta tanda. La UI deshabilita el botón o el adaptador
rechaza la operación con un error explícito. **Nunca se resuelven con datos
falsos** de `FakeSnippetStore`.

| Operación o elemento de la UI | Función | Motivo |
| --- | --- | --- |
| `deleteSnippet`, botón Delete | Borrar snippet | Fuera de US1–7 |
| `formatSnippet`, botón Format | Formatear | US11–13, tanda posterior |
| `getFormatRules`, `modifyFormatRule` | Reglas de formato | US11–12, tanda posterior |
| `getLintingRules`, `modifyLintingRule` | Reglas de lint | US14–15, tanda posterior |
| `getTestCases`, `postTestCase`, `removeTestCase`, `testSnippet`, botón Test | Tests | El backend de crear y listar ([SNI-46](tests.md)) y el de ejecutar ([SNI-48](test-execution.md)) ya existen; falta conectar la UI. Borrar queda para después |

## Conexión de la UI con el backend

Hallazgos en el código de la UI que hay que resolver al reemplazar el Fake:

| Hallazgo | Qué hacer |
| --- | --- |
| `BACKEND_URL` vale por defecto `http://localhost:8080/api`; Snippets no tiene prefijo `/api` | Configurar `BACKEND_URL=http://localhost:8080` o usar un proxy que quite el prefijo. |
| La UI corre en `http://localhost:5173` y envía `X-Dev-Actor-Id`, `Idempotency-Key` y `PUT` | El navegador hace un pedido previo de CORS. Usar el proxy del servidor de desarrollo de Vite, o habilitar CORS para ese origen solo en `dev`. |
| `ShareSnippetModal` pide usuarios con `page=1` | La página empieza en `0`: con `1` se saltea la primera página. Enviar `0`. |
| `CreateSnippet` incluye `extension` | No enviarla: el backend rechaza campos desconocidos con `400`. |
| `UpdateSnippet` solo trae `content` | Completar el reemplazo con los datos del detalle cargado. |
| El buscador dice «Search FileType» pero filtra por nombre | Se conecta al filtro `name`. |

`POST /snippets` exige `Idempotency-Key`. El adaptador genera un UUID nuevo por
cada alta que el usuario confirma y reutiliza el mismo en los reintentos de esa alta.

## Limitaciones de esta tanda

- **US5 y US6 no están completas.** Faltan el filtro y el orden por lint, las
  infracciones y los tests del detalle. Por decisión del equipo, lint y tests se
  completan en una tanda posterior.
- No hay nombres legibles de owner: se muestra `ownerId`.
- No se puede dejar de compartir; US7 no lo pide.
- El listado obtiene de Permissions todos los IDs accesibles del actor en cada
  pedido. Alcanza para el volumen del TP; con mucho volumen haría falta paginar
  también en Permissions.
- No hay control de revisiones: dos ediciones simultáneas desde la UI terminan
  con la última aplicada ([update.md](update.md)).
- El storage es el local provisional ([storage.md](storage.md)). El contrato HTTP
  no cambia cuando se adopte el storage oficial (SNI-19): solo el detalle lee código.

## Coordinación

Juan coordina el contrato de backend. Juan Francisco valida el encaje con la UI y
la operación pública de sharing; Thiago valida el contrato de Permissions y la
transición del storage. Cada parte puede implementarse en paralelo contra este
contrato, sin esperar a las demás.
