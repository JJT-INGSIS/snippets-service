# SNI-76 — Revisión del procesador en Language

La versión de lenguaje (`1.0` / `1.1`) dice **qué reglas** se piden. La revisión
del procesador dice **qué build exacto** de PrintScript contestó. Dos builds que
soportan "1.1" pueden dar resultados distintos, así que Snippets no deduce nada de
la versión: lee la revisión de cada respuesta.

El contrato del lado de PrintScript (cálculo, header, `GET /processor`, cómo
verificar una imagen) está en
[printscript-service: identidad del procesador](https://github.com/JJT-INGSIS/printscript-service/blob/main/docs/processor-identity.md).
Este documento cubre qué hace Snippets con ese dato.

## Qué lee Snippets

Cada respuesta de PrintScript trae el header `Processor-Revision` con el formato
`sha256-v1:<64 hex>`. `Language` lo clasifica sin inventar nada:

| Header | `ProcessorIdentity` |
| --- | --- |
| Con el formato correcto | `Verified(revision)` |
| No viene (PrintScript corriendo fuera de un jar, o un servicio viejo) | `Missing` |
| Viene con otro formato, por ejemplo `1.1` | `Malformed(valor)` |

Un `ProcessorRevision` solo se puede construir con el formato correcto, así que una
versión de lenguaje nunca puede pasar por revisión.

Para comparar con una revisión esperada, `checkAgainst` devuelve:

| Resultado | Cuándo |
| --- | --- |
| `ACCREDITED` | Verificada e igual a la esperada |
| `MISMATCH` | Verificada pero de otro build |
| `UNVERIFIABLE` | Falta o tiene formato inválido |

## Dónde queda

| Operación | Qué guarda |
| --- | --- |
| Validar | `language.validateAttributed(...)` devuelve el resultado junto con su `ProcessorIdentity`, también en `422` (versión no soportada). `language.validate(...)` sigue igual para los casos de uso que no lo necesitan. |
| Ejecutar (inicio y consulta) | `ExecutionDescriptor.processor` con la revisión verificada, o `null` |
| Eventos | `LanguageEventStream.processor`, la revisión de quien abrió el stream |
| Auditoría | `snippet_execution_audit.remote` guarda el descriptor del inicio, con su revisión. Consulta: `SELECT remote->'processor' FROM snippet_execution_audit` |

No hizo falta una migración: `remote` ya es JSONB y el campo nuevo se agrega solo.
Las filas anteriores a SNI-76 no tienen revisión.

## Cambio de procesador durante una sesión

Una ejecución (test o interactiva) vive en un proceso de PrintScript. Si durante la
sesión, al consultar o reabrir los eventos, contesta **otra** revisión que la del
inicio, Snippets no confía en esa respuesta:

- La consulta del estado responde `DEPENDENCY_UNAVAILABLE`.
- Reabrir los eventos cierra el stream y responde `DEPENDENCY_UNAVAILABLE`
  (motivo interno `processor-changed`).
- La auditoría no recibe un resultado: queda sin resultado final, como cualquier
  sesión que no se pudo confirmar.

Así no se mezcla la salida de un build con el resultado de otro.

## Qué no hace todavía

Por ahora Snippets **registra y clasifica**, pero no rechaza: crear, actualizar y
ejecutar funcionan igual aunque la revisión falte. Exigir una revisión concreta
queda para los tickets que la necesitan:

| Ticket | Qué exigiría |
| --- | --- |
| SNI-70 | Activar una versión de parser con una revisión explícita |
| SNI-59 / SNI-60 | Formato y lint con la misma identidad |
| SNI-69 | Revalidación y conformidad contra la revisión activa |

## Diseño

| Pieza | Qué hace |
| --- | --- |
| `language/ProcessorIdentity.kt` | Header, formato, `ProcessorRevision`, `ProcessorIdentity`, `ProcessorCheck` y `Attributed` |
| `PrintScriptValidator` | Lee el header en la respuesta de `/validate`, también en errores `4xx` |
| `ExecutionHttpTransport.processorOf` | Lee el header de cualquier respuesta de ejecución |
| `ExecutionProtocol` / `OpenedExecutionEvents` | Ponen la revisión en el descriptor y en el stream |
| `RunSessions.describe` / `RunChannel` | Rechazan una sesión que contesta otra revisión |

## Decisiones

Aprobadas por Juan el 30/10/2026 («dale vamos con esas 5 recomendaciones»). Las
decisiones 1, 2, 3 y 5 son del lado de PrintScript y están en su documento.

| # | Decisión | Alternativa descartada | Costo aceptado |
| --- | --- | --- | --- |
| 4 | Language registra y clasifica la revisión (Verified / Missing / Malformed); la auditoría la guarda; un cambio de revisión en la sesión no se acepta. Exigir queda para SNI-70/59/60/69 | Rechazar ya cualquier respuesta sin revisión | Hoy una respuesta sin revisión se acepta igual; queda registrada como `Missing` |

## Verificación

`./gradlew check`:

- `ProcessorIdentityTest`: formato, comparación y que `1.1` no es una revisión.
- `PrintScriptValidatorTest`: revisión en `200` y `422`; si falta o está mal, no se inventa.
- `ExecutionClientTest`: inicio, consulta y eventos con su revisión; sin header queda `null`.
- `InteractiveRunAuditTest`: la auditoría guarda la revisión del inicio.
- `ExecutionAuditPersistenceTest`: si contesta otra revisión, la sesión se rechaza y no se guarda resultado.
