# Reglas y resultados de lint

`GET` y `PUT /linting-rules/{language}/{version}` operan sobre el actor autenticado. El PUT reemplaza la selección completa con `{"rules":{…}}`. Catálogo y validación deben acreditar la misma revisión de PrintScript. La respuesta `200` incluye `language`, `version`, `revision`, `changed`, `jobId` y `rules` con las opciones y los valores seleccionados.

Sin configuración se usan los defaults verificados del proveedor. Guardar `{}` explícitamente desactiva las reglas. La revisión es el hash del JSON canónico con claves ordenadas. Repetir la selección no genera otro trabajo. Para un cambio, reglas, revisión, trabajo y outbox `LintRulesChanged` se guardan juntos. El consumidor evalúa los snippets del propietario para ese lenguaje y versión.

Crear o editar también evalúa el snippet después del commit del guardado. El resultado conserva la base de contenido, las revisiones y los diagnósticos. Una infracción exige un análisis completo. Un error técnico no revierte el guardado ni se presenta como incumplimiento. Listado y detalle sólo muestran resultados acreditados para la base vigente. [Consultas](reading.md#vigencia-de-lint)

## Errores

| Status | `type` | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Body inválido o duplicado |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad inválida/ausente |
| `422` | `urn:snippets:problem:invalid-linting-rules` | violations reales del proveedor |
| `422` | `urn:snippets:problem:unsupported-language` | Capacidad no soportada |
| `422` | `urn:snippets:problem:unsupported-version` | Versión no soportada |
| `502` | `urn:snippets:problem:dependency-failure` | Proveedor/revisión/defaults no disponibles |
| `503` | `urn:snippets:problem:metadata-unavailable` | Fallo de persistencia de configuración |
