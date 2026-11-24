# Compartir snippets

Sólo el propietario puede otorgar acceso. Un lector compartido puede leer el mismo código, descargar su formato y consultar o ejecutar sus tests. Permissions verifica la propiedad y registra el share. [Contrato de acceso de Permissions](https://github.com/JJT-INGSIS/permissions-service/blob/dev/docs/access.md)

## Buscar destinatarios

`GET /users?name=…&page=0&page_size=10` devuelve `page`, `page_size`, `count` y `users` (cada usuario con `id` y `name`). La página empieza en cero y el tamaño admite de 1 a 100. La búsqueda excluye al actor. El directorio local busca un fragmento, sin distinguir mayúsculas. Auth0 usa un prefijo literal del nombre y devuelve una página del proveedor. Si el total llega a 1.000 o la ventana excede el límite recuperable, devuelve `422 directory-search-too-broad`.

Auth0 entrega `user_id` como ID opaco. El índice de búsqueda es eventual. Para compartir se verifica al destinatario por ID con la Management API; sólo su `404` acredita que no existe. Un error del directorio devuelve `502` sin degradar al catálogo local. [Configuración del directorio](authentication.md#directorio-de-usuarios)

## Dar acceso

`PUT /snippets/{id}/shares/{userId}` no lleva body. Usá el UUID del snippet y el `id` del directorio codificado como segmento de URL. Devuelve `201` para un share nuevo y `200` si ya existía, ambos con `snippetId` y `userId`. Repetir un pedido es seguro. Una dependencia caída se informa como error y el cliente puede verificar el resultado al reintentar.

## Errores de búsqueda

| Status | `type` | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Parámetro desconocido, repetido o inválido. `parameter` lo nombra. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `422` | `urn:snippets:problem:directory-search-too-broad` | Refine el nombre; Auth0 no permite una página completa verificable dentro de su límite. |
| `502` | `urn:snippets:problem:directory-unavailable` | Directorio no disponible o respuesta inválida; no es una lista vacía. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. |

## Errores al compartir

| Status | `type` | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | `{id}` no es un UUID canónico o `{userId}` está en blanco. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `403` | `urn:snippets:problem:share-denied` | El actor ve el snippet pero no es su owner. |
| `404` | `urn:snippets:problem:snippet-not-found` | No existe, sigue pendiente o el actor no tiene acceso. |
| `422` | `urn:snippets:problem:invalid-recipient` | El destinatario no está en el directorio o es el propio actor. |
| `500` | `urn:snippets:problem:technical-failure` | Error inesperado. |
| `502` | `urn:snippets:problem:directory-unavailable` | No se pudo validar el destinatario con el directorio. No se registró el share. |
| `502` | `urn:snippets:problem:dependency-failure` | Permissions no respondió según su contrato o tardó más que el límite. El share no está confirmado. |
| `503` | `urn:snippets:problem:metadata-unavailable` | Falló la base de Snippets. |

## Ejemplos

#### `200` Usuarios que contienen «an»

```http
GET /users?name=an&page=0&page_size=5
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":5,"count":2,"users":[{"id":"dev-ana","name":"Ana"},{"id":"dev-juanfran","name":"Juan Francisco"}]}
```

#### `200` Sin coincidencias

```http
GET /users?name=zzz&page=0&page_size=5
X-Dev-Actor-Id: dev-juan
```

```json
{"page":0,"page_size":5,"count":0,"users":[]}
```

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
