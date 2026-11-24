# Autenticación

Auth0 administra el login y las credenciales. En `AUTH_MODE=auth0`, Snippets valida el access token de `Authorization: Bearer …` mediante JWKS, issuer, audience y vencimiento. El `sub` identifica al actor; se requieren tokens de usuario. Permissions sigue decidiendo el acceso a cada snippet. El bearer termina en el borde HTTP y queda fuera de los llamados internos, los trabajos y los logs.

`GET /` y `GET /languages` son públicos. El resto requiere autenticación en modo Auth0. El modo `disabled` es el predeterminado sin configuración. `dev` sólo admite `X-Dev-Actor-Id` con el perfil Spring `dev`. En Auth0 un token inválido recibe `401` con Bearer challenge.

| Variable | Uso |
| --- | --- |
| `AUTH_MODE` | `disabled`, `dev` o `auth0`. |
| `AUTH_ISSUER` | Issuer HTTPS exacto, con slash final. |
| `AUTH_AUDIENCE` | Identificador de la API del ambiente. |
| `SNIPPETS_CORS_ALLOWED_ORIGINS` | Orígenes explícitos separados por comas. |

Cada apertura o reconexión NDJSON, input y cancelación comprueba la identidad y el acceso. Vencer el token no interrumpe un stream ya admitido. [Política CORS](cors.md)

## Directorio de usuarios

En modo Auth0, `GET /users` y la validación del destinatario de un share usan una aplicación M2M dedicada con el único scope `read:users` en la Management API del tenant. Configurá `AUTH0_DIRECTORY_CLIENT_ID` y el secreto `snippets.directory.auth0.client-secret` mediante configtree. Para un proceso local existe `AUTH0_DIRECTORY_CLIENT_SECRET`. La credencial queda en el backend y se configura por ambiente.

El adaptador mantiene en memoria el token M2M hasta antes de expirar. Si la credencial falta o el directorio falla, búsqueda y sharing devuelven `502`. Las demás operaciones siguen usando el JWT de usuario. [Contrato de sharing](sharing.md)
