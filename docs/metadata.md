# Contrato interno de metadatos — SNI-6

`SnippetMetadataStore` es una interfaz Kotlin dentro de Snippets. No es una API HTTP.
Su implementación JDBC guarda metadatos en la base propia de Snippets y no llama a
Permissions, PrintScript ni al storage.

## Datos

`SnippetMetadata` contiene `id: UUID`, `name: String`, `description: String?`,
`language: String`, `version: String` y `state: SnippetState`.

`SnippetDetails` agrupa los cuatro campos descriptivos usados al crear o actualizar.
Nombre, lenguaje y versión no pueden estar en blanco. La descripción puede ser
`null` o vacía. Los valores se conservan tal como se reciben: no se recortan ni se
normalizan. La validación de lenguajes, versiones y código corresponde al caso de
uso que llama a la persistencia.

La tabla `snippets` también conserva la clave UUID de creación, la identidad que
inició la operación y la huella del pedido original. No guarda código ni owner.
La identidad de creación sirve para reconocer reintentos; Permissions sigue siendo
la fuente de ownership, incluso si esos valores coinciden inicialmente.

## Preparar una creación

```kotlin
val request = CreationRequest(
    key = creationKey,
    actorId = actorId,
    fingerprint = requestFingerprint,
    details = SnippetDetails(
        name = name,
        description = description,
        language = language,
        version = version,
    ),
)
val result = metadataStore.prepareCreation(request)
```

La primera solicitud reserva un UUID nuevo generado por Snippets y un registro
`PENDING`. Devuelve `CreationResult.Created(metadata)` después del commit.

La clave es única en PostgreSQL. Otra solicitud con la misma clave:

- Misma identidad y huella: `CreationResult.Existing(metadata)`, con el mismo UUID
  y el estado actual, aunque ya esté confirmado.
- Otra identidad o huella: `CreationResult.Conflict`, sin exponer ni modificar el
  registro existente.

La reserva concurrente se resuelve mediante la restricción única y una transacción.
Esto evita duplicar metadatos; no impide que dos callers compatibles intenten una
misma operación externa. SNI-9 debe acordar ese comportamiento con SNI-8.

### Responsabilidad de la huella

SNI-9 debe calcular una huella estable de **todo el pedido original**, incluyendo
nombre, descripción, lenguaje, versión y código, a partir de una representación
inequívoca. No debe depender del orden de campos del JSON ni usar concatenaciones
ambiguas. La identidad se compara por separado y sin normalizar.

SNI-6 recibe esa huella como un string opaco no blanco: no recibe el código, no
elige todavía un algoritmo y no recalcula la huella. Reutilizar la huella para un
pedido distinto incumple este contrato. SNI-9 deberá probar su cálculo y comparación
para evitar que el cliente pueda elegir arbitrariamente la huella.

La huella original no cambia al actualizar metadatos. Un reintento del alta original
devuelve el registro vigente; no revierte modificaciones posteriores.

## Consultas y confirmación

| Operación | Resultado |
| --- | --- |
| `findCreation(key, actorId)` | `Found(metadata)`, `Missing` o `IdentityConflict`. Incluye pendientes y confirmados. |
| `findConfirmed(id)` | Metadatos confirmados o `null` si falta el UUID o sigue pendiente. |
| `findMetadata(id)` | Lectura interna por UUID: incluye PENDING/CONFIRMED; `null` solo si no existe. |
| `confirmCreation(id)` | Metadatos confirmados o `null` si no existe. Repetir la confirmación conserva el registro. |

Un pendiente no se presenta como un snippet creado. `findCreation` exige una
identidad no blanca y no devuelve metadatos si esa identidad no coincide.

SNI-10 añade `findMetadata` para distinguir inexistente y pendiente durante la
preparación de actualización. No cambia el esquema ni `findConfirmed`; no es una
consulta pública de snippets disponibles. La lectura conserva la traducción de
fallas JDBC a `MetadataPersistenceException` y no realiza escrituras.

**Precondición de `confirmCreation`:** el caso de uso debe completar y comprobar
los pasos externos requeridos antes de invocarla. Esta operación cambia únicamente
el estado en PostgreSQL. No comprueba que exista contenido ni ownership y no
constituye una transacción distribuida. Todavía no hay endpoint público de creación
que la invoque.

La vinculación con contenido la agrega SNI-8 sin modificar este contrato: la columna
`content_reference` y la interfaz separada `SnippetContentLinks` están descritas en
[storage.md](storage.md). `confirmCreation` no comprueba esa vinculación; SNI-17 debe
vincular el contenido antes de confirmar.

## Actualización

`updateMetadata(id, details)` devuelve:

- `MetadataUpdate.Updated(metadata)` si el registro está confirmado.
- `MetadataUpdate.Missing` si no existe.
- `MetadataUpdate.Pending` si sigue incompleto.

La operación actualiza nombre, descripción, lenguaje y versión. Conserva UUID,
estado, clave, identidad y huella originales. Bloquea brevemente la fila para
comprobar el estado y aplicar los cambios en una misma transacción.

El caller debe comprobar permisos y coordinar la validación y el contenido cuando
sea necesario. Esta operación no autoriza al usuario ni modifica contenido. Las
actualizaciones concurrentes se serializan y la última aplicada reemplaza los
cuatro campos: no se implementa control de revisión ni historial en SNI-6.

## Transacciones y errores

Cada escritura usa su propia transacción corta (`REQUIRES_NEW`) y devuelve su
resultado después del commit. Una transacción externa no engloba la reserva,
confirmación o actualización. SNI-9 no debe intentar abarcar llamadas HTTP o
storage dentro de una transacción de base de datos.

Las fallas JDBC y transaccionales se propagan como `MetadataPersistenceException`,
con una causa interna. Nunca equivalen a inexistencia, conflicto ni éxito. SNI-9
debe traducirlas a un error técnico HTTP sin exponer SQL o credenciales.

Si se pierde la conexión durante un commit, el resultado puede ser incierto.
SNI-9 puede consultar o repetir la reserva con su clave original; no debe asumir
que el registro no existe ni generar automáticamente una nueva clave.

## Esquema y pruebas

Flyway crea la tabla con `V1__create_snippets.sql`, agrega la referencia de contenido con
`V2__link_snippet_content.sql` y valida su historial al arrancar.
La cuenta de PostgreSQL necesita permisos para crear la tabla y el historial de
migraciones. No se crean tablas en la base de Permissions.

Las pruebas de integración usan Testcontainers con `postgres:18.6-bookworm` y
requieren Docker disponible. Verifican migración, operaciones, reintentos,
concurrencia, rollback y conservación de datos al reiniciar la aplicación sobre la
misma base. Los tests existentes de arranque y wiring de lenguaje siguen usando H2
con Flyway desactivado; no prueban las migraciones PostgreSQL.

Desde la raíz de Snippets, con JDK 21 y las credenciales locales de GitHub Packages:

```powershell
.\gradlew.bat test --tests "com.jjt.ingsis.snippets.metadata.*"
.\gradlew.bat check
.\gradlew.bat build
```

En macOS/Linux, reemplazar `.\gradlew.bat` por `sh ./gradlew`. El runner Ubuntu del
CI necesita Docker para estas pruebas, igual que Permissions.
