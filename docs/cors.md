# CORS — SNI-41

## Policy and ownership

Spring is the single CORS authority for the public Snippets API. NGINX must forward
`Origin`, preflight request headers and the response headers without adding another
`Access-Control-Allow-*` policy or intercepting OPTIONS. Internal Permissions,
PrintScript and Asset clients do not need browser CORS configuration.

The UI uses relative `/api` requests: Vite locally, and NGINX when deployed, forward
them to Snippets. The browser sees the same origin, so this flow needs no cross-origin
allowlist. NGINX removes the public `/api` prefix before forwarding to the backend.
With an empty list, no cross-origin permissions are emitted. Actual requests still
reach normal application validation; a proxy can preserve Origin while rewriting
Host without breaking same-origin browser writes. The browser prevents cross-origin
JavaScript from reading responses without the grant. CORS is not a server firewall
or a substitute for CSRF protection or authentication.

**HTTPS termination requires coordination with SNI-38/SNI-44.** Browser same-origin
and backend same-origin are not automatically identical: NGINX receives HTTPS but
may contact Spring over HTTP. It must preserve the public Host, and any processing
of forwarded scheme/host must trust only the intended proxy with direct backend
access restricted. An HTTPS Origin can otherwise look cross-origin to Spring,
especially on POST/PUT. When an explicit nonempty policy is enabled, include the
exact public UI origin or ensure correct trusted proxy reconstruction. The empty
default grants no cross-origin access without rejecting proxy writes solely for
this mismatch. This ticket does not enable global forwarded-header
trust. Final verification must exercise writes through the actual HTTPS proxy,
not only GET or a direct backend connection.

## Configuration

`SNIPPETS_CORS_ALLOWED_ORIGINS` is a comma-separated list of exact HTTP(S) origins.
It defaults to empty: cross-origin access is denied, while same-origin and internal
requests continue to work. It is public configuration, not a secret.

For an explicitly authorized direct local browser connection:

```powershell
$env:SNIPPETS_CORS_ALLOWED_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173"
```

Set the variable on the backend process/container before starting it. Restart the
backend after changing it. No Azure or GitHub change is needed for local verification.
Deployed UI and API should share one HTTPS origin; do not copy local origins into
production unless there is an explicit reason to authorize that access.

Origins have scheme, host and optional port, without a path or trailing slash.
Wildcards, `null`, credentials, query strings and fragments fail application startup.
`localhost` and `127.0.0.1` are different origins; so are different ports or schemes.

| Backend route | Allowed cross-origin methods |
| --- | --- |
| `/snippets` | GET, POST |
| `/snippets/*` | GET, PUT |
| `/snippets/*/shares/*` | PUT |
| `/snippets/*/tests` | GET, POST |
| `/snippets/*/tests/*/executions`, `/snippets/*/executions` | POST |
| `/executions/*`, `/executions/*/events` | GET |
| `/executions/*/inputs`, `/executions/*/cancel` | POST |
| `/users`, `/languages` | GET |

OPTIONS preflight is handled by Spring before application operations and does not
require identity. Its requested method must match this table. CORS does not create
endpoints or grant ownership. Requests without Origin still use normal authorization.

Allowed headers are `Content-Type`, `Idempotency-Key`, and `X-Dev-Actor-Id` only with
the existing `dev` profile. Production still rejects development identity, including
same-origin or non-browser attempts. `Authorization` must be reviewed when real
authentication is introduced; it is not currently implemented by this service.

Cross-origin cookies are disabled (`allowCredentials=false`); sending a bearer
header in the future does not by itself require enabling this option. No additional
response headers are exposed because the UI reads JSON bodies, not Location.
Spring manages Vary headers. Preflight is cached for 300 seconds; after a policy
change, an already-open browser may retain its previous preflight result temporarily.

## Verification and diagnosis

For a backend running on port 8080 with the dev profile and the example allowlist:

```powershell
curl.exe -i -X OPTIONS http://127.0.0.1:8080/snippets -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: content-type,idempotency-key,x-dev-actor-id"
```

Check the allowed origin, method and headers, not only the successful status.
The actual response must also carry the allowed-origin header. Unlisted origins,
methods and headers do not obtain CORS permission. With a nonempty policy, unlisted
origins on actual requests are rejected by Spring. Missing identity on a protected request
still yields 401, readable by an allowed browser origin.

curl/Postman cannot prove browser enforcement. From a browser page served on the
allowed origin, a direct fetch to the backend should succeed:

```javascript
fetch('http://127.0.0.1:8080/users', {
  headers: {'X-Dev-Actor-Id': 'dev-thiago'},
}).then(async response => ({status: response.status, body: await response.json()}));
```

Use the actual backend port. Repeat from a different, unlisted local origin: the
browser must reject access. Test without the actor header as well: the browser
should read the 401 response for the allowed origin. Vite's normal `/api` flow is a
separate same-origin regression, not proof of cross-origin behavior.

The optional browser spec is `src/test/ui/cors.cy.ts`. It expects the real backend
on `127.0.0.1:18080`, allowing **only** `http://127.0.0.1:5173`, and the UI's existing
Vite server on port 5173 with development identity enabled and its proxy targeting
that backend. `localhost:5173` is intentionally not allowed for the negative case.
From `snippet-searcher-ui`, with its existing dependencies installed:

```powershell
$spec = "cypress/e2e/backend/sni-41-cors-local.cy.ts"
if (Test-Path -LiteralPath $spec) { throw "Temporary spec already exists" }
Copy-Item ../snippets-service/src/test/ui/cors.cy.ts $spec
try {
    npx cypress run --spec $spec --config "baseUrl=http://127.0.0.1:5173,specPattern=cypress/e2e/backend/**/*.cy.ts,chromeWebSecurity=true"
    if ($LASTEXITCODE -ne 0) { throw "Browser CORS verification failed" }
} finally {
    Remove-Item -LiteralPath $spec
}
```

No API responses are stubbed. The first case verifies the real UI list plus a direct
cross-origin GET with a custom header (preflight) and a readable 401 without identity.
The second checks that the browser rejects the same direct request from localhost.

Then restart the backend with `SNIPPETS_CORS_ALLOWED_ORIGINS` empty and repeat the
same copy/run/remove procedure for `src/test/ui/cors-proxy.cy.ts`. It creates one
named local snippet and verifies POST, PUT and detail through the real Vite proxy.
This explicitly checks the default policy with a rewritten Host and preserved
Origin. The local review backend port remains 18080; never run this write test
against production.

Browser DevTools Network distinguishes an OPTIONS rejection from an actual operation
failure. Do not open the policy to fix 401/403 or dependency failures. HTTPS pages
calling an HTTP backend can also fail due to mixed content, independently of CORS.

Run `./gradlew build` for properties, real HTTP integration, dev/prod identity and
existing regression tests. Final checks through NGINX, DNS and HTTPS remain SNI-44,
including preservation of headers on errors and absence of duplicate policy headers.
If Spring Security is added, integrate CORS before authentication in its filter chain;
preflight does not carry the eventual authentication token.

## Design decision

Approved in the SNI-41 conversation on 2026-10-24: exact external allowlist, empty by
default, no cross-origin cookies, no speculative headers and 5-minute preflight cache.
A central Spring CorsFilter keeps policy at the HTTP boundary and covers application
error responses without per-controller annotations or a custom CORS implementation.
An empty allowlist installs no grants instead of installing a rejecting route policy;
this preserves the existing proxy flow and browser enforcement without trusting
arbitrary forwarded headers or changing the frontend configuration.
Per-route methods follow existing endpoints. New routes must update this table and
the policy. The cost of an explicit list is configuration maintenance when consumers
change. Revisit credentials/exposed headers when an actual consumer requires them.

## SNI-52 — execution transport

The additions above follow exactly the eight public operations in
[SNI-45](https://github.com/JJT-INGSIS/snippet-searcher-infra/blob/dev/docs/architecture/execution-contract.md).
POST `/executions` is internal to PrintScript and has no public CORS grant.
The replay cursor is the `after` query parameter; requestId is JSON in the input
body. No Last-Event-ID, speculative Authorization or new exposed headers are needed.
Methods/headers outside the table retain no grants. A successful OPTIONS does not
implement execution endpoints or relax actor/session authorization.

`CorsHttpTest` checks every public method and rejects other methods, paths, origins
and speculative headers; `ProductionCorsTest` also rejects dev headers on execution
routes. Spring alone emits the policy, including errors, with credentials disabled.
Tests against missing endpoints check readable errors, not fictitious execution.

NGINX and browser transport verification are documented in
[SNI-52](https://github.com/JJT-INGSIS/snippet-searcher-infra/blob/dev/docs/sni-52-streaming.md).
Local gated upstreams prove incremental HTTP/HTTPS delivery, not real interpreter
behavior. Integration with the real interpreter and deployed TLS is recorded later.

Reference: course presentation `06-https`, pages 28–40, and
[Spring MVC CORS](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html).
