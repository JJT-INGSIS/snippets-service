# SNI-72 — Autenticación de la API

## Comportamiento

Snippets es un OAuth2 Resource Server. Auth0 realiza el login y administra las
credenciales; este servicio sólo verifica el access token recibido en
`Authorization: Bearer …`. No agrega usuarios, contraseñas ni sesiones propias.

Spring Security valida RS256 mediante JWKS, issuer exacto, audience y tiempos
(`exp`/`nbf`, con la tolerancia de reloj predeterminada de 60 segundos). Además se
exigen `sub` no vacío y `exp`; se rechazan identidades de client credentials y
subjects terminados en `@clients`. Un ID token destinado a la SPA no pasa la
validación de audience de la API. No se exigen nombre/email dentro del token.

La capa HTTP transforma el subject validado en `ActorContext.authenticatedActorId`.
Los casos de uso siguen usando un actor opaco y Permissions conserva ownership,
lectura y modificación. Los campos del body/query y `X-Dev-Actor-Id` no pueden
reemplazar al usuario en modo auth0. No se adopta información de actores dev por
coincidencia de email. Los IDs existentes y el contenido no se migran.

El bearer termina en el borde HTTP: no se transmite a Permissions, PrintScript,
storage, Redis ni workers. Los trabajos conservan únicamente su contexto interno.

## Configuración externa

| Variable | Propiedad | Uso |
| --- | --- | --- |
| `AUTH_MODE` | `snippets.auth.mode` | `disabled`, `dev` o `auth0` |
| `AUTH_ISSUER` | `snippets.auth.issuer` | URL HTTPS exacta, con slash final |
| `AUTH_AUDIENCE` | `snippets.auth.audience` | Identificador de la API, no de la SPA |
| `SNIPPETS_CORS_ALLOWED_ORIGINS` | `snippets.cors.allowed-origins` | Origins explícitas separadas por coma |

El backend recibe el issuer completo en `AUTH_ISSUER`, en lugar de derivarlo de
`AUTH_DOMAIN`. La UI usa el domain según su SDK. Ninguna de estas variables es un
secreto; el backend no requiere client secret para verificar JWT.

Sin configuración, la identidad está deshabilitada. El perfil `dev` conserva
explícitamente `AUTH_MODE=dev` para la transición actual; no sirve para autenticar
usuarios reales. Un modo desconocido, `dev` fuera del perfil dev o configuración
auth0 incompleta impiden arrancar. Auth0 nunca cae a modo dev ante un token malo.

Parámetros DEV preparados en SNI-71:

```text
AUTH_MODE=auth0
AUTH_ISSUER=https://jjt-snippet-searcher.us.auth0.com/
AUTH_AUDIENCE=https://jjt-snippets-dev.duckdns.org/api
```

Los tokens están configurados en Auth0 con duración de 900 segundos. El backend
valida su vencimiento; no implementa renovación. PROD necesita su propia audience
y recursos preparados, aunque el tenant sea compartido.

La propiedad `snippets.auth.jwk-set-uri` permite el servidor de claves controlado
de los tests. Normalmente se omite y se obtiene el JWKS del issuer configurado.
No se reciben claves ni URLs de claves desde la petición del usuario.

## Rutas, errores y NDJSON

En modo auth0 se exige usuario para toda solicitud nueva salvo GET `/languages`
y GET `/` (sondeo mínimo existente de Docker; no se agrega un health endpoint).
Esto incluye creación/update, sharing, reglas, tests, ejecuciones, resultados y
`GET /jobs/{id}`. Las rutas futuras quedan protegidas por defecto. No se abren
Actuator ni management, ni se implementan login/logout dentro de Snippets.

Token ausente/inválido: 401 con Bearer challenge. Usuario válido: se conservan
los errores y permisos del caso de uso. Por ejemplo, un tercero recibe 404 al
leer un snippet ajeno, mientras una sesión de ejecución iniciada por otro actor
conserva el 403 vigente. No se reemplaza esa política en este ticket.

CORS se procesa dentro de Spring Security, antes de autenticar. Preflight válido
no necesita token. Se admite `Authorization` en las rutas/métodos registrados,
incluidos lint y resultados automáticos. `X-Dev-Actor-Id` sólo se admite por CORS
con modo y perfil dev; no hay cookies de autenticación ni `allowCredentials`.

El stream NDJSON se autentica y autoriza al abrirse o reconectarse. La escritura
asíncrona y el despacho de errores conservan la respuesta ya autorizada; no
inician otra operación de negocio. Vencer un JWT no cancela el intérprete ni un
stream admitido. Cada consulta, reconexión, input y cancelación nuevos requiere
un token vigente y los permisos actuales. No se introduce revocación inmediata.

## Decisiones y límites

Plan aprobado por el usuario en este chat el 08/11/2026 («implementa acorde al
plan»), sobre el contrato SNI-71. Se utiliza el starter oficial, con versiones
gestionadas por el plugin Gradle existente, en lugar de filtros o verificadores
criptográficos propios. Los costos aceptados son configuración/JWKS del proveedor,
JWT válidos hasta su expiración y pruebas de seguridad en el borde HTTP.

Se conserva la autorización actual frente a duplicar owner/reader en roles de
Auth0. Se mantienen modos separados frente a un fallback implícito. Se preserva
NDJSON frente a reemplazar su transporte. El directorio todavía es el catálogo
actual: SNI-74 incorpora usuarios reales para sharing. Login, caché y renovación
del transporte de UI corresponden a SNI-73.

## Verificación reproducible

`./gradlew build` ejecuta compilación, ktlint, detekt, pruebas y empaquetado. Docker
debe estar disponible para PostgreSQL/Redis efímeros. Las pruebas HTTP de SNI-72
usan RSA/JWKS controlados y PostgreSQL real, sin endpoints ficticios en la API:
creación/idempotencia por actor, dueño/lector/tercero, headers forjados, matriz de
rutas/métodos, JWT inválidos, CORS y streaming/reconexión/input/cancelación reales.

Para la prueba optativa con el tenant DEV:

```powershell
$env:AUTH0_LIVE_ACCEPTANCE = 'true'
./gradlew.bat test --tests '*Auth0LiveAcceptanceTest'
Remove-Item Env:AUTH0_LIVE_ACCEPTANCE
```

Con puerto 5173 libre, abrir la URL temporal `build/sni72-login-url.txt` durante la
prueba e ingresar en Universal Login. El diagnóstico usa Authorization Code +
PKCE con la SPA DEV existente, callback sólo en loopback y espera máxima de diez
minutos. Code verifier y token quedan en memoria; no se imprimen ni guardan.
Al terminar se cierra el callback y se elimina el archivo de URL. No usa la
aplicación M2M administrativa. Comprueba aceptación del JWT real y rechazo del
header dev sin bearer; no acredita todavía la integración completa de UI/sharing.

Con el adaptador de SNI-74, este diagnóstico sigue sin configurar una credencial
Management: un JWT real válido permite llegar al error específico 502
`directory-unavailable`, mientras el header dev sin bearer recibe 401. El 502
confirma esa frontera de autenticación, no el funcionamiento del directorio. La
aceptación real de SNI-74 necesita además su M2M dedicada y usuarios de prueba.
La verificación histórica del 08/11 que figura debajo corresponde al código de
SNI-72 anterior al adaptador; no se reescribe como una prueba de la versión nueva.

La activación remota necesita coordinar SNI-73/74 y la configuración de release
de infra. Integrar este código mantiene DEV en modo dev y PROD deshabilitado.
Este ticket no autoriza despliegues, migraciones de ownership ni cambios de tenant.

Verificación real realizada el 08/11/2026: el usuario completó Universal Login
y consentimiento de la SPA DEV; `Auth0LiveAcceptanceTest` pasó (1 prueba, 0 fallos).
La API local respondió 200 con access token real y 401 con sólo header dev.
Callback y URL temporal se cerraron/eliminaron al terminar. No se usó M2M ni se
persistió el bearer. Esto no acredita todavía UI desplegada ni directorio real.

Build final local del mismo día: aprobado, incluidos ktlint, detekt, JaCoCo y
empaquetado. Resultado JUnit: 670 casos, 651 ejecutados, 0 fallos y 19 optativos
omitidos por sus condiciones de entorno. Uno es la prueba Auth0 ya ejecutada
por separado; los otros 18 requieren proveedores/imágenes de aceptación externas
(PrintScript/Permissions y parser-admin). No se acredita su ejecución en este corte.
