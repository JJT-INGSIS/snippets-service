# SNI-59 — Capacidad de formato de Language

Language puede pedir el catálogo de reglas de formato de un lenguaje y formatear
código, sin saber qué servicio lo hace. Para PrintScript lo resuelve
[printscript-service](https://github.com/JJT-INGSIS/printscript-service/blob/dev/docs/formatting.md)
con el formatter real de la biblioteca `printscript-v1:1.2.0`.

Esta capacidad **no** agrega rutas públicas: todavía no hay endpoint de Snippets
que formatee ni configuraciones de formato por usuario. Un caso de uso futuro la
va a usar igual que hoy se usa `validate`.

## Uso

```kotlin
language.formattingCatalog(language = "printscript", version = "1.1")
language.format(language = "printscript", version = "1.1", code = "let a=1;", rules = mapOf("enforce-spacing-around-equals" to true))
```

Las dos devuelven el resultado junto con la revisión del procesador que contestó
(`Attributed`), igual que `validateAttributed` (ver [revisión del procesador](processor-revision.md)).

`rules` es un mapa `id → valor` que viaja tal cual: Language no interpreta las
reglas. Las valida printscript-service, que es el que sabe qué reglas existen en
cada versión y de qué tipo son.

### Resultados

| `FormattingResult` | Significado |
| --- | --- |
| `Formatted(code)` | El código formateado |
| `Unformattable(diagnostics)` | Un error léxico impide formatear; cada diagnóstico conserva regla, mensaje, línea y columna |
| `InvalidRules(violations)` | Alguna regla está mal: cada violación trae la regla y el motivo (`unknown-rule`, `unavailable-in-version`, `wrong-type`, `out-of-range`, `conflicting-rules`) |
| `CodeTooLarge` | El código supera el límite del servicio |
| `OutputTooLarge` | El resultado supera el límite del servicio |
| `UnsupportedLanguage` | Ningún formateador atiende ese lenguaje |
| `UnsupportedVersion` | El lenguaje no anuncia esa versión, o el servicio la rechazó |
| `Failed` | Falla técnica: servicio caído, timeout o respuesta fuera de contrato. No dice nada sobre el código |

`FormattingCatalogResult` tiene `Catalog(rules)`, `UnsupportedLanguage`,
`UnsupportedVersion` y `Failed`. Cada `FormattingRule` trae `id`, `type`,
`default`, `minimum`, `maximum`, `conflictsWith` y `description`, tal como los
publica printscript-service.

Una versión que el lenguaje no anuncia se rechaza **sin** llamar al servicio,
con el mismo criterio que al validar.

## Diseño

| Pieza | Qué hace |
| --- | --- |
| `Language` | Elige el formateador del lenguaje y controla la versión |
| `LanguageFormatter` (`language/formatting`) | Contrato de formateo de un lenguaje y sus resultados tipados |
| `PrintScriptFormatter` | Traduce las respuestas de printscript-service a resultados de formateo |
| `PrintScriptExchange` | Parte HTTP: manda el pedido, lee `Processor-Revision` y convierte excepciones en `PrintScriptAnswer` |
| `PrintScriptAnswer` | Lo que contestó el servicio: `Answered`, `Rejected` (con su Problem Details) o `Unavailable` |
| `PrintScriptFormattingMessages` | Los JSON del contrato HTTP |

`PrintScriptFormatter` distingue los rechazos por el `type` del Problem Details
(`invalid-formatting-rules`, `formatted-output-too-large`, `request-too-large`), y
la versión no soportada por el `422` con `supportedVersions`. Cualquier otra cosa
es `Failed`: nunca se presenta una falla técnica como código o reglas inválidas.

## Decisiones

| Decisión | Alternativa descartada | Costo aceptado | Aprobación |
| --- | --- | --- | --- |
| Estrategia `LanguageFormatter` por lenguaje con resultados tipados | Llamar a HTTP desde el caso de uso | Un contrato más en Language | Juan, 31/10/2026 (D5 de SNI-59) |
| Solo catálogo y formato; sin chequeo de reglas | Exponer también `POST /formatting/rules/check` | Cuando haya configuraciones por usuario hay que agregarlo | Juan, 01/11/2026, al revisar el código |
| Reglas como mapa sin interpretar | Tipar cada regla en Snippets | Snippets no detecta un tipo mal antes de llamar | Juan, 01/11/2026, al revisar el código |
| HTTP aislado en `PrintScriptExchange` genérico | Clase específica del formateo, sin genéricos | Lleva `<T : Any>` (lo exige Spring) y `body: Any` | Juan, 01/11/2026 («dejala así») |

`PrintScriptValidator` sigue con su propio manejo HTTP. Podría pasar a usar
`PrintScriptExchange`, pero eso queda fuera de este ticket.

## Configuración

Usa las mismas propiedades que la validación: `language.printscript.base-url`,
`connect-timeout` y `read-timeout` (ver el README). No hay reintentos.

## Verificación

`./gradlew build`:

- `PrintScriptFormatterTest`, contra un servidor HTTP local: forma del pedido, código formateado con su revisión, diagnóstico léxico, cada rechazo tipado, respuestas inesperadas y servicio caído como `Failed`, catálogo y versión no soportada.
- `LanguageFormattingTest`: elección del formateador, lenguaje sin formateador, versión no anunciada sin llamar al servicio, formateadores duplicados.

### Evidencia con printscript-service real — 01/11/2026

Con el Compose de infra levantado desde las ramas de SNI-59, `Language` con
`PrintScriptValidator` y `PrintScriptFormatter` apuntando a `http://127.0.0.1:8082`:

| Llamada | Resultado |
| --- | --- |
| `format` 1.0 de `let a:number=1;println(a);` con espacios en `=` y salto por sentencia | `Formatted("let a:number = 1;\nprintln(a);")`, revisión `Verified(sha256-v1:2001166d…5289)` = `capabilities.format` |
| `format` de un texto sin cerrar | `Unformattable` con `UNTERMINATED_STRING` en 1:9 y la misma revisión |
| `format` 1.0 con `indent-inside-if` | `InvalidRules` con `unavailable-in-version` y la misma revisión |
| `format` versión `2.0` | `UnsupportedVersion`, sin llamar al servicio (`Missing`) |
| `formattingCatalog` 1.1 | `Catalog`, con la misma revisión |

La aceptación SNI-53 (`scripts/verify_us8_10.py`) siguió en 15/15 con estas imágenes.
