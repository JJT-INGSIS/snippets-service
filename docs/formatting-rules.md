# SNI-61 — Reglas de formato del owner (US11)

Cada usuario guarda sus reglas de formato por lenguaje y versión. Guardar es
síncrono: las reglas se validan contra el catálogo real del lenguaje y, si
cambiaron, la configuración, su revisión, el trabajo raíz y el evento
`FormattingRulesChanged` se confirman en un único commit de PostgreSQL
(outbox de [SNI-57](background-outbox.md)). El reprocesamiento de los snippets
afectados es US12; acá solo queda el trabajo pendiente.

Estado: **implementado y verificado en Compose (02/11/2026).** La ruta HTTP del derivado individual es SNI-66.

## API

| Ruta | Qué hace |
| --- | --- |
| `GET /formatting-rules/{language}/{version}` | Reglas del actor para ese lenguaje y versión |
| `PUT /formatting-rules/{language}/{version}` con `{"rules":{id: valor}}` | Reemplaza toda la selección del actor |

El owner es siempre el actor identificado. No hay ruta que reciba otro owner,
así que nadie puede leer ni modificar la configuración de otro usuario.

Respuesta `200` de las dos rutas:

```json
{
  "language": "printscript",
  "version": "1.1",
  "revision": "sha256-v1:…",
  "changed": true,
  "jobId": "…",
  "rules": [
    {
      "id": "indent-inside-if",
      "type": "integer",
      "default": null,
      "minimum": 0,
      "maximum": 10,
      "conflictsWith": [],
      "description": "…",
      "value": 2
    }
  ]
}
```

- `rules` es el catálogo real de la versión con el `value` vigente de cada regla.
  Una regla no seleccionada muestra su `default` del catálogo; la UI no inventa defaults.
- `changed` y `jobId` sólo tienen sentido en `PUT`: `changed:false` y `jobId:null`
  cuando la selección no cambió. En `GET` son `false` y `null`.

## Selección canónica y revisión

| Concepto | Regla |
| --- | --- |
| Selección canónica | Sólo las reglas prendidas. `false` y `null` equivalen a no mandar la regla, como define el contrato de printscript-service. La calcula el adaptador del lenguaje |
| Revisión | `sha256-v1:` del JSON canónico (claves ordenadas) |
| Config ausente | Selección vacía: todas las reglas en su default |

Por eso config ausente, selección vacía y "todo en default" tienen la misma
revisión, y un `PUT` que no cambia la selección no escribe nada.

## Casos

| Caso | Resultado | Config | Trabajo/outbox |
| --- | --- | --- | --- |
| `GET` sin config guardada | `200`, defaults del catálogo, revisión de `{}` | Sin cambios | No |
| `PUT` de selección vacía o todo en default sin config previa | `200 changed:false` | No se crea fila | No |
| `PUT` con la misma selección (reintento incluido) | `200 changed:false` | Sin cambios | No |
| `PUT` con selección nueva | `200 changed:true` con `jobId` | Nueva revisión | Sí, en el mismo commit |
| Reglas inválidas (desconocida, de otra versión, tipo, rango, conflicto) | `422 invalid-formatting-rules` con `violations` | Se conserva la anterior | No |
| Lenguaje o versión no soportados | `422` | Se conserva la anterior | No |
| PrintScript caído, o catálogo y chequeo sin la misma revisión verificada | `502 dependency-failure` | Se conserva la anterior | No |
| Falla al escribir config, trabajo u outbox | `503 metadata-unavailable` | Rollback completo | No queda evento |
| Body que no es `{"rules":{…}}` exacto, o con claves repetidas | `400` | Sin cambios | No |
| Identidad ausente o inválida | `401` | Sin cambios | No |

## Errores

| Status | Type | Cuándo |
| --- | --- | --- |
| `400` | `urn:snippets:problem:invalid-request` | El body no es exactamente `{"rules":{…}}`. |
| `401` | `urn:snippets:problem:identity-unavailable` | Identidad ausente, inválida o deshabilitada. |
| `422` | `urn:snippets:problem:invalid-formatting-rules` | Alguna regla no vale para esa versión. `violations` trae `rule` y `reason` de cada una. |
| `422` | `urn:snippets:problem:unsupported-language` | Lenguaje sin formatter. |
| `422` | `urn:snippets:problem:unsupported-version` | Versión no soportada por el lenguaje. |
| `502` | `urn:snippets:problem:dependency-failure` | PrintScript no respondió según su contrato o su revisión no se pudo verificar. Repetir es seguro. |
| `503` | `urn:snippets:problem:metadata-unavailable` | PostgreSQL no está disponible. Repetir es seguro. |

Los motivos de `violations` son los de printscript-service: `unknown-rule`,
`unavailable-in-version`, `wrong-type`, `out-of-range` y `conflicting-rules`.

## Evento

Sólo cuando cambia la revisión:

| Campo | Valor |
| --- | --- |
| `eventType` | `FormattingRulesChanged` |
| `scope` | `ownerId`, `language`, `version`, `configurationId` |
| `revisions` | `formattingRules` (revisión de la config), `formatProcessor` (revisión `format` que validó) |
| Reacción del trabajo | `format-owned-snippets` (US12) |
| `initiatedBy` | Actor que guardó; sirve para auditoría, no da permisos |

El check con PrintScript ocurre **antes** de abrir la transacción: adentro de
`AtomicJobWrite` sólo hay escrituras PostgreSQL.

Hasta que existan los consumidores (SNI-58) y US12, cada cambio deja un trabajo
`PENDING` sin procesar. Es un costo aceptado.

## Reglas aplicables y derivado individual

- Las reglas que aplican a un snippet son las **de su owner** para el lenguaje y
  la versión del snippet. Un lector de un snippet compartido puede obtener la
  variante formateada con esas reglas, pero no tiene forma de modificarlas.
- `FormatSavedSnippet` (para US13/SNI-66, sin ruta HTTP en este ticket) formatea
  el snippet guardado bajo demanda y **no persiste** el resultado. Lee el
  contenido de forma estable, formatea con `Language` y antes de devolver vuelve
  a comprobar la base. Si cambió, reintenta y, si sigue cambiando, responde `Stale`.
  El original nunca se modifica, y una descarga fallida no escribe nada ni crea trabajo.

## Vigencia de derivados

Un derivado formateado es vigente sólo si coincide toda su base:

| Parte de la base | Cambia cuando |
| --- | --- |
| Referencia del contenido | Cada `POST`/`PUT` del snippet (siempre se guarda un blob nuevo) |
| Lenguaje y versión | Un `PUT` del snippet los cambia |
| Revisión de reglas | El owner guarda una selección distinta |
| Revisión `format` del procesador | Cambia el artefacto o los límites de printscript-service |

La regla es la función pura `FormattingBasis.isCurrent`, que US12 reutiliza al
publicar derivados. La invalidación es implícita por comparación: crear o editar
un snippet no escribe nada extra, no formatea automáticamente y no agrega
consumidores de `SnippetUpdated`. `POST` y `PUT` mantienen su respuesta.

## Decisiones

Aprobadas por Juan el 02/11/2026 («dale, aprobadas las 8»).

| # | Decisión | Alternativa descartada | Costo aceptado |
| --- | --- | --- | --- |
| D1 | Validar con `Language` → `POST /formatting/rules/check`, exigiendo revisión verificada | Validar en snippets contra el catálogo; no validar | Una llamada HTTP antes de guardar |
| D2 | Guardar la selección canónica (sólo reglas prendidas) | Guardar el JSON tal cual llega | El adaptador conoce que `false`/`null` = no mandada |
| D3 | Revisión `sha256-v1:` del JSON canónico | Contador incremental | Volver a una selección anterior repite su revisión (mismo contenido) |
| D4 | `GET`/`PUT /formatting-rules/{language}/{version}`, owner = actor, `PUT` reemplaza todo | Query params; `PATCH`; reglas por snippet | Mandar siempre la selección completa |
| D5 | Evento sólo si cambia la revisión, con scope y revisiones de la tabla | Emitir en todo `PUT` | — |
| D6 | Un snippet usa las reglas de su owner | Reglas del lector | El lector no ve su propio formato en snippets ajenos |
| D7 | Derivado individual bajo demanda, sin persistir, con recomprobación de base | Guardarlo en storage | Se formatea en cada pedido |
| D8 | Invalidación implícita por comparación de base | Marcar derivados inválidos en la transacción del update | La comparación se hace al publicar o leer |

## Verificación

`./gradlew build` en verde (JDK 21 y Docker para Testcontainers):

| Clase | Qué prueba |
| --- | --- |
| `FormattingSelectionTest` | Revisión independiente del orden y del tipo numérico; vacía = ausente |
| `FormattingRulesUseCasesTest` | Matriz de casos: ausente, nueva, sin cambios, vacía, volver a defaults, inválidas, proveedor caído o no verificable, versión no soportada, identidades separadas, sin identidad, base caída |
| `FormattingRulesPersistenceTest` | PostgreSQL real: config + trabajo + outbox en un commit, sin trabajo si no cambia, rollback completo si falla el outbox |
| `FormattingRulesHttpTest` | API completa con PrintScript simulado: `200`, `400`, `401`, `422`, `502` y aislamiento entre owners |
| `LanguageFormattingTest`, `PrintScriptFormatterTest` | `check` en Language y en el adaptador; selección canónica |
| `FormattingBasisTest` | Vigencia: cualquier cambio de contenido, lenguaje, versión, reglas o procesador la invalida |
| `FormatSavedSnippetTest` | Reglas del owner también para un lector, reintento ante una edición, `Stale`, procesador no verificable y ninguna escritura |
| `SnippetProblemTest` | La tabla de errores de este doc coincide con los problemas que responde el servicio |

### Evidencia con Compose — 02/11/2026

Imágenes construidas desde las ramas locales con
`docker compose --profile ui --profile ingress up -d --build printscript-service snippets-service`.
Flyway aplicó la migración de esta tabla (entonces V8). Publicador y worker apagados
(default), sin Redis.

La migración se renombró después a `V9__create_formatting_configurations.sql`,
porque SNI-68 (#55) entró antes a `dev` con su propia V8. Es aditiva y no había
llegado a ningún ambiente compartido, así que el número se puede cambiar sin
afectar datos.

| Comprobación | Resultado |
| --- | --- |
| `GET /formatting-rules/printscript/1.1` sin config | `200`, 12 reglas reales, booleanas `false`, numéricas `null`, `revision` = `sha256-v1:44136fa3…ff8a` (selección vacía) |
| `PUT` con `if-brace-below-line`, `indent-inside-if: 2`, `mandatory-line-breaks-inside-if` | `200 changed:true`, `jobId` |
| PostgreSQL después del `PUT` | 1 fila que une config, trabajo `PENDING` `format-owned-snippets` y outbox `FormattingRulesChanged` sin publicar |
| Mismo `PUT` repetido | `200 changed:false`, `jobId:null`, misma revisión, sin trabajo nuevo |
| `PUT` con `indent-inside-if: 11`, `"true"` y `tabs` | `422 invalid-formatting-rules` con `out-of-range`, `wrong-type` y `unknown-rule`; la config anterior sigue igual |
| `GET /jobs/{jobId}` | `200 PENDING` para el owner, `404` para otro actor |
| `GET` de otro actor | Todo en default: no ve reglas ajenas |
| `revisions.formatProcessor` del evento | `sha256-v1:2001166d…5289`, igual a `capabilities.format` de `GET /processor` |
| Versión `9.9` | `422 unsupported-version` |
| `FormatSavedSnippet` con PostgreSQL y PrintScript reales (prueba solo en scratchpad) | Con las reglas guardadas del owner, `if(true){println(1);}` → `if(true)⏎{⏎  println(1);⏎}` y base con revisión de reglas `64454f53…2127` y del procesador `2001166d…5289`; un snippet de otro owner sin reglas sale sin cambios con la revisión vacía |
| Aceptación SNI-53 (`scripts/verify_us8_10.py`) | 15/15: crear, editar, ejecutar y compartir no cambiaron |
