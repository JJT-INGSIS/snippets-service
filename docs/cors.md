# CORS

La API aplica CORS cuando el navegador llega desde un origen distinto. En el ingreso mediante NGINX la UI y `/api` comparten origen. Para acceso directo desde otro origen, usá `SNIPPETS_CORS_ALLOWED_ORIGINS` con orígenes HTTP(S) exactos separados por comas, sin paths ni comodines. La lista vacía deshabilita el acceso cross-origin.

El preflight se procesa antes de [autenticar](authentication.md). La política registra rutas y métodos explícitos y admite `Content-Type`, `Idempotency-Key` y `Authorization`. Sólo el modo y perfil `dev` admite `X-Dev-Actor-Id`. No habilita cookies y el preflight se puede cachear 300 segundos. [Rutas y métodos habilitados](../src/main/kotlin/com/jjt/ingsis/snippets/http/CorsConfiguration.kt)
