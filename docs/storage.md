# Storage de contenido — SNI-8

El 7 de octubre de 2026 la cátedra indicó avanzar con un storage propio mientras no esté disponible su interfaz oficial. Este documento describe la interfaz mínima de Snippets, su adaptador con archivos locales y las garantías que pueden usar la creación (SNI-17) y la actualización (SNI-18).

Es provisional. La adaptación al contrato oficial se seguirá en SNI-19 y debería limitarse a escribir otro adaptador de la misma interfaz. No hay integración con Azure.

Aquí «local» significa archivos del backend, no almacenamiento del navegador.

## Interfaz

`SnippetContentStorage` es una interfaz Kotlin interna. No es una API HTTP.

| Operación | Resultados |
| --- | --- |
| `store(code)` | `Stored(reference)` o `StorageUnavailable` |
| `read(reference)` | `Found(code)`, `Missing`, `InvalidContentReference` o `StorageUnavailable` |
| `delete(reference)` | `Deleted`, `InvalidContentReference` o `StorageUnavailable` |

`ContentReference` es opaca: los casos de uso la guardan y la devuelven, pero no la interpretan ni conocen rutas. Solo el storage decide su forma.

No existe una operación de reemplazo. El contenido guardado nunca se modifica: reemplazar es guardar contenido nuevo, cambiar la referencia en los metadatos y borrar el anterior.

## Garantías

| Situación | Garantía |
| --- | --- |
| Guardar | Cada `store` crea contenido nuevo con una referencia nueva. Nunca sobrescribe. |
| Leer | Devuelve exactamente el texto guardado. Si no se puede representar sin alterarlo, `store` falla y no guarda nada. |
| Escritura interrumpida | El contenido se escribe con un nombre temporal y se renombra de forma atómica. Un contenido legible siempre está completo. |
| Reintentar `store` | Crea otro contenido independiente. No daña ninguno existente. |
| Reintentar `delete` | Borrar contenido que ya no existe devuelve `Deleted`. |
| Concurrencia | Escrituras simultáneas producen contenidos separados y completos. |
| Referencia inválida | Se rechaza sin tocar el sistema de archivos. Ninguna referencia puede alcanzar algo fuera del directorio. |
| Falla del sistema de archivos | `StorageUnavailable`. Nunca se informa como éxito ni como contenido inexistente. |

No hay conflictos que informar: como nada se sobrescribe, dos operaciones nunca compiten por el mismo contenido.

## Adaptador local

`LocalSnippetContentStorage` guarda cada contenido en un archivo UTF-8 dentro de un directorio. La referencia es un UUID generado al guardar y el archivo lleva ese nombre.

- `ContentDirectory` convierte una referencia en una ruta solo si es un UUID canónico, y arma la ruta a partir de ese UUID, nunca del texto recibido.
- `AtomicTextFile` escribe en un archivo temporal, lo fuerza a disco y lo renombra.

Si el directorio completo deja de existir, por ejemplo por un volumen desmontado, las lecturas devuelven `StorageUnavailable` y no `Missing`.

## Configuración

| Propiedad | Variable de entorno | Valor por defecto |
| --- | --- | --- |
| `storage.local.directory` | `STORAGE_LOCAL_DIRECTORY` | `build/snippet-content` |

El valor por defecto sirve para `bootRun` y para los tests. Queda dentro de `build`, por lo que `clean` lo borra: no es un destino persistente.

Al arrancar, el servicio crea el directorio si falta y comprueba que puede escribir en él. Si no puede, no arranca e informa la ruta.

En la imagen:

| Dato | Valor |
| --- | --- |
| Directorio | `/var/lib/snippets/content` |
| Variable ya definida | `STORAGE_LOCAL_DIRECTORY=/var/lib/snippets/content` |
| Usuario y grupo del proceso | `10001:10001` |
| Dueño del directorio | `10001:10001` |

El `Dockerfile` declara ese directorio como volumen. Un volumen con nombre montado allí hereda el dueño en su primer uso. Un montaje de otro tipo debe entregar el directorio con permiso de escritura para el usuario `10001`.

## Vínculo con los metadatos

La migración `V2__link_snippet_content.sql` agrega la columna `content_reference` a `snippets`. Admite `NULL`: una reserva todavía no tiene contenido. No modifica la migración `V1`.

`SnippetContentLinks` es una interfaz separada de `SnippetMetadataStore`:

| Operación | Uso | Resultado |
| --- | --- | --- |
| `findContent(id)` | Consultar | La referencia, o `null` si el snippet no existe o no tiene contenido. |
| `linkContent(id, reference)` | Creación | `Linked(replaced)`, `AlreadyConfirmed` o `SnippetMissing`. Solo actúa sobre un snippet `PENDING`. |
| `replaceContent(id, details, reference)` | Actualización | `Replaced(metadata, replaced)`, `StillPending` o `SnippetMissing`. Solo actúa sobre un snippet `CONFIRMED`. |

`replaceContent` cambia nombre, descripción, lenguaje, versión y referencia en una sola sentencia, con la fila bloqueada. Dos actualizaciones simultáneas se serializan y la última aplicada reemplaza los cinco valores juntos.

`replaced` es la referencia anterior, o `null` si no había. Indica qué contenido puede borrar el caso de uso.

Las fallas de base se propagan como `MetadataPersistenceException`, igual que en [metadata.md](metadata.md). PostgreSQL guarda solo la referencia, nunca otra copia del código. Ownership sigue en Permissions.

La base no exige que un snippet confirmado tenga contenido. `CreateSnippet` vincula el contenido antes de llamar a `confirmCreation`.

## Uso en creación — SNI-17

`CreationContent` aplica estos pasos dentro de `CreateSnippet`. El flujo completo, sus respuestas y sus reintentos están en [creation.md](creation.md).

1. `PrepareCreation` devuelve una reserva `PENDING`.
2. Si `findContent` ya devuelve una referencia, es un reintento: no se guarda de nuevo.
3. `store(code)`.
4. `linkContent(id, reference)`.
5. Registrar ownership y `confirmCreation`.

Si `linkContent` informa que reemplazó una referencia, o que el snippet ya estaba confirmado, la copia que quedó sin usar se borra. Si `linkContent` falla, no se borra nada: el vínculo pudo haberse guardado.

| Falla | Estado resultante |
| --- | --- |
| En `store` | Nada cambió. Se puede reintentar. |
| En `linkContent` | Queda un contenido sin referenciar. La reserva sigue sin contenido. |
| Después de vincular | La reserva `PENDING` conserva su contenido. El reintento continúa desde el paso 5. |

## Uso previsto en actualización — SNI-18

1. `PrepareUpdate` devuelve un candidato ya validado.
2. `store(code)` del candidato.
3. `replaceContent(id, details, reference)`.
4. `delete` de la referencia devuelta en `replaced`.

| Falla | Estado resultante |
| --- | --- |
| En `store` | El snippet vigente no cambió. |
| En `replaceContent` | El snippet vigente no cambió. Queda un contenido sin referenciar. |
| En `delete` | El snippet ya muestra los datos y el contenido nuevos. Queda sin referenciar el contenido anterior. |

En ningún punto el contenido vigente se sobrescribe ni se presenta contenido incompleto: el snippet apunta al contenido anterior completo o al nuevo completo.

## Límites

- Los archivos y PostgreSQL no forman una transacción única. Las tablas anteriores describen el estado ante cada falla parcial; los casos de uso deben coordinarlo.
- Puede quedar contenido sin referenciar. No afecta a ningún snippet y no hay limpieza automática.
- No hay historial: al completarse una actualización, el contenido anterior se borra.
- El adaptador supone un único directorio compartido por las instancias que lo usen.
- La escritura atómica protege ante la caída del proceso. La durabilidad ante un corte de energía depende del sistema de archivos del volumen.

## Verificación

`./gradlew build` ejecuta las pruebas del adaptador sobre directorios temporales y las del vínculo sobre PostgreSQL 18.6 con Testcontainers, por lo que requiere Docker.

Cubren texto preservado exactamente, contenido inexistente, referencias inválidas, fallas del sistema de archivos, escrituras interrumpidas, escrituras concurrentes, reemplazos concurrentes, reintentos y la migración sobre datos existentes.

La persistencia entre contenedores se comprueba en el Compose de `snippet-searcher-infra`, que monta un volumen en el directorio de la imagen. El recorrido completo por HTTP está en `CreationHttpTest`, descrito en [creation.md](creation.md).