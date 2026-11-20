# SNI-63 — Configuración y evaluación puntual de lint

Estado: implementación en revisión; defaults integrados con el contrato SNI-77.
Thiago aprobó defaults reales sin configuración, selección explícita vacía que
desactiva reglas y no crear otro lote al guardar nuevamente la misma configuración
(02/11/2026). SNI-77 incorpora la selección real de la biblioteca en el catálogo del proveedor;
no se copian defaults ni se interpreta `{}` como configuración implícita.

## Configuración explícita

GET/PUT `/linting-rules/{language}/{version}` siguen el ámbito de configuración
ya usado por formato. El owner es el actor identificado, nunca un campo del body.
PUT requiere exactamente `{"rules":{id: valor}}`, sin claves duplicadas ni campos
extra. El proveedor valida catálogo, valores, CHOICE y nulabilidad. Snippets no
mantiene listas de reglas ni reinterpretaciones de sus opciones.

200 devuelve language, version, revision, changed, jobId y rules. Cada regla
incluye id, type, acceptsNull, choices, conflictsWith y value de la selección.
La revisión es SHA-256 del mapa JSON de claves ordenadas; repetir esa selección
conserva revisión y no crea otro trabajo. Una selección explícita `{}` se guarda:
a diferencia de formato, no equivale a ausencia de configuración/defaults.

Configuración, revisión, trabajo y outbox LintRulesChanged se confirman en una
transacción. Catálogo y chequeo deben acreditar la misma revisión efectiva del
procesador. Rechazos y fallos conservan la selección anterior. Un owner no lee
ni modifica las reglas de otro; un lector configura exclusivamente sus propias
reglas, sin cambiar las del owner del snippet compartido.

GET sin configuración devuelve la selección default atribuida del catálogo.
Si el proveedor no incluye defaults o no acredita su revisión, devuelve error
de dependencia. Un proveedor anterior no activa una lista local de reglas.

| Status | Type | Condición |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | Body inválido o duplicado |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad inválida/ausente |
| `422` | `urn:snippets:problem:invalid-linting-rules` | violations reales del proveedor |
| `422` | `urn:snippets:problem:unsupported-language` | Capacidad no soportada |
| `422` | `urn:snippets:problem:unsupported-version` | Versión no soportada |
| `502` | `urn:snippets:problem:dependency-failure` | Proveedor/revisión/defaults no disponibles |
| `503` | `urn:snippets:problem:metadata-unavailable` | Fallo de persistencia de configuración |

## Evaluación individual después del commit

Creación nueva y update confirmado llaman explícitamente a SavedSnippetLint.
Un reintento idempotente de creación ya confirmada no evalúa de nuevo. Guardados
rechazados o fallidos no llaman a lint. No se agregan eventos para lint individual.

La evaluación usa la configuración del owner y la respuesta atribuida de
Language. COMPLIANT/NON_COMPLIANT sólo provienen de un análisis completo; errores
de programa, límites, dependencia o procesador no verificable producen
NOT_EVALUATED_ERROR, nunca cumplimiento o infracción ficticios.

Se conserva hash de código, referencia, revisión de reglas, procesador efectivo,
diagnósticos, estado y motivo. El registro conserva intentos obsoletos internos;
la proyección sólo cambia si referencia, lenguaje, versión y reglas aún coinciden.
Se comprueba también el contenido de la referencia antes de evaluar: un update
intermedio no puede asociar el código anterior con su blob nuevo.

Sin configuración se evalúa con defaults verificados del proveedor y se guarda
su revisión. Si no están disponibles, se registra DEFAULTS_UNAVAILABLE. Una
selección explícita vacía continúa desactivando reglas. False/null admitidos por
el validador se normalizan a regla desactivada antes de calcular la revisión.

Si PostgreSQL falla después de confirmar el contenido, no se puede garantizar
persistir simultáneamente el diagnóstico del fallo: se registra advertencia y
se conserva el éxito del guardado, como decidió Thiago. Una consulta posterior
debe verificar revisiones y no mostrar el diagnóstico anterior como vigente
(SNI-64). No hay retry async individual ni se mantiene transacción durante HTTP.

## Pruebas

JDK 21/Docker: `./gradlew build`. Las pruebas de persistencia usan PostgreSQL real
para atomicidad/no-op/aislamiento y para resultados obsoletos. Pruebas del evaluador
usan un proveedor controlado y no se presentan como integración real del linter.
Las pruebas de flujo comprueban el orden después del commit y el retry idempotente.
El proveedor y Language de SNI-77 deben integrarse antes de consumir defaults
en un ambiente. Listado/lote/UI continúan en SNI-64/67.

## Evidencia con proveedores reales — 02/11/2026

Se compiló PrintScript dev `32953ef508763732dee3a3c7323c166c6adb18ac`
y se verificó la API de esta rama con PostgreSQL, Permissions, Asset y Azurite
reales, en una base local aislada (`lint63`). Actor/fixture nuevos:
`dev-sni63-1c6b59b7-007f-4287-b8f4-e60287d3505a`, snippet
`81fd0055-ab43-430d-a883-8e9772f32ebc`.

1. GET sin configuración respondió 502: limitación SNI-77 explícita, **no es
   aceptación de defaults**.
2. PUT de `mandatory-variable-or-literal-in-println: true` produjo un trabajo;
   repetirlo respondió changed:false. Un CHOICE `identifier_format: invented`
   fue rechazado con 422 conservando revisión.
3. Crear código sintácticamente válido `println(1+2);` respondió 201 y conservó
   contenido; una consulta SQL al assessment actual confirmó NON_COMPLIANT real.
4. PUT de `{}` y update a `println(3+4);` respondieron 200; assessment COMPLIANT
   real y detalle con el código exacto actualizado.

Esta evidencia acredita configuración explícita/evaluación puntual, no defaults,
lote US15, filtros/listado ni UI de lint. No se modificó CD ni se desplegó Azure.


Actualización 04/11/2026: la migración de lint es V14, después de V12/V13 ya integradas en dev. V11 nunca se integró en esa rama; el cambio evita intentar aplicar una migración menor después de las existentes.


04/11/2026: guardar explícitamente la selección default actual conserva su revisión y no crea lote, pero persiste esa elección para que una futura modificación del default no cambie la configuración explícita del usuario. INSERT y UPDATE distinguen el alta inicial de cambios concurrentes sin basarse en una lectura previa no bloqueante.

### Verificación posterior a SNI-77 — 2026-11-04

Build completo verde sobre `6a8dc67`, con PostgreSQL real en los tests.
Además se ejecutaron los JARs de Snippets y PrintScript con una base nueva y
Permissions real: sin configuración se mostraron los defaults publicados
(`identifier_format=camel case`, `mandatory-variable-or-literal-in-println=true`).
Guardar esos mismos defaults persistió la elección explícita sin lote; cambiar
a `{}` emitió un lote; guardar después una regla booleana desactivada mantuvo
la revisión sin otro lote. La configuración de otra identidad no cambió.
Crear código válido con identificador `Bad_name` confirmó el contenido y guardó
`NON_COMPLIANT`, diagnóstico real `identifier_format`, hash de defaults y revisión
efectiva del proveedor en PostgreSQL. La infracción no revirtió la creación.
Esta prueba no acredita el consumidor de lotes SNI-64 ni un despliegue en Azure.
