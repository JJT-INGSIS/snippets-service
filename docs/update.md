# Preparación de actualización — SNI-10

Contrato propuesto para `PUT /snippets/{uuid}`. No hay controller funcional:
la edición pública y su respuesta exitosa se habilitarán en SNI-18, después
del contrato real de storage de SNI-8. No se implementa PATCH.

## Reemplazo completo

`Content-Type: application/json`. Nombre, lenguaje, versión y código son strings
obligatorios; los tres primeros no pueden estar en blanco. El código puede
estar vacío: Language decide su validez. Se conservan los valores exactamente.
Descripción es opcional: ausente o `null` elimina la descripción; `""` es distinta.
No se aceptan campos desconocidos, `ownerId`, identidad ni fingerprint.

```json
{
  "name": "Renamed",
  "description": null,
  "language": "printscript",
  "version": "1.1",
  "code": "println(2);"
}
```

El UUID de la ruta debe ser canónico. Se reutilizan `SnippetDraft`,
`DraftJsonDecoder` y la resolución de identidad de SNI-9. `X-Dev-Actor-Id`
solo se admite con perfil `dev`, sin actor predeterminado: identidad opaca,
no blanca y comparada exactamente. No es autenticación de producción.

## Preparación interna

`PrepareUpdate` valida identidad y entrada, lee `findMetadata` y distingue
inexistente de PENDING. Solo para CONFIRMED consulta
`GET /ownership/{uuid}/can-modify?actorId=...`; el cliente codifica el actor
como valor de query, incluso `+`, espacios y caracteres reservados.
Solo un `200 application/json` con booleano `allowed: true` autoriza continuar.
`false` es denegación; `404` Problem Details válido es ownership inexistente.
Timeout, servicio caído y respuesta incompatible son fallas técnicas, nunca
denegaciones. No hay reintentos automáticos ni decisiones basadas en mensajes.

Con permiso, Language valida lenguaje, versión y código del reemplazo completo.
No hace falta leer el contenido vigente. Todos los diagnósticos se conservan.
El resultado `Prepared(UpdateCandidate)` es un candidato interno, no una edición
exitosa. Los otros resultados distinguen request inválido, identidad rechazada,
inexistente, pendiente, denegado, ownership inexistente, código inválido,
capacidad no soportada y falla técnica.

Ningún camino llama a `updateMetadata`, `confirmCreation` o registro de ownership.
No hay escritura ni interfaz ficticia de storage. `findMetadata` incluye pendientes
solo para decidir internamente; `findConfirmed` mantiene su comportamiento.
Los errores JDBC se traducen desde `MetadataPersistenceException` a falla técnica.

## HTTP futuro y coordinación pendiente

Propuesta de errores `application/problem+json`, con identificadores `type`
estables, `title`, `status`, `detail` e `instance`. No están expuestos todavía.

| Situación | Estado futuro | Categoría propuesta |
| --- | --- | --- |
| Request o UUID inválido | 400 | invalid-request |
| Identidad no disponible o inválida | 401 | identity-required |
| Metadatos inexistentes | 404 | snippet-not-found |
| Metadatos pendientes | 409 | snippet-pending |
| Permiso denegado | 403 | modification-denied |
| Metadatos confirmados sin ownership | 409 | ownership-missing |
| Código inválido | 422 | invalid-code |
| Lenguaje o versión no soportados | 422 | unsupported-capability |
| Permissions o Language fallan | 502 | dependency-failure |
| Persistencia falla | 503 | metadata-unavailable |

SNI-18 debe definir coordinación, recuperación de fallas parciales y garantías
reales de escritura entre storage y metadatos antes de devolver `200`.
La comprobación actual de permisos no es un bloqueo transaccional ni garantiza
autorización para una escritura futura. El contenido no existe en esta preparación
y no se afirma atomicidad entre servicios.

## Verificación

`check` y `build` cubren permisos contra un servidor HTTP controlado, encoding,
respuestas incompatibles y timeouts; candidatos completos y diagnósticos;
lecturas PostgreSQL reales y preservación de metadatos; wiring y ausencia
de endpoints públicos. No son una prueba de edición completa contra storage.

Verificación local del 6 de octubre de 2026: `sh ./gradlew check build --no-daemon`
finalizó correctamente con JDK 21 y Docker disponible. La suite completa registró
99 tests: 96 exitosos, 3 omitidos (PrintScript real opcional), sin fallas ni errores.
No se ejecutaron commits ni push durante esta implementación.
