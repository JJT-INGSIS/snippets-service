# Asset Service — SNI-19

Estado: adaptador con envoltura versionada implementado y verificado localmente.
No se activó Asset en ambientes existentes. El corte sigue requiriendo inventario,
elección de los datos a conservar y autorización por ambiente.

## Base y alcance

SNI-19 parte de `dev`, con creación y actualización completas sobre almacenamiento
local. Se reutilizan `SnippetContentStorage`, sus resultados y los flujos existentes;
no se agrega un SDK de Azure ni otra interfaz de almacenamiento.

La implementación actual está descrita en [storage.md](storage.md). El cambio de
proveedor no autoriza desplegar, borrar datos ni reabrir SNI-8/SNI-17/SNI-18.
La incorporación permanente de runtime, red y volúmenes corresponde a SNI-33.

## Evidencia del contrato oficial

Código revisado el 19 de octubre de 2026, commit
`96c08767c47f8969c7285b5322f834f97978e7a3`:

- [Rutas](https://github.com/austral-ingsis/asset-service/blob/96c08767c47f8969c7285b5322f834f97978e7a3/src/main/kotlin/org/snippet/asset/service/AssetRoutes.kt).
- [Declaración HTTP](https://github.com/austral-ingsis/asset-service/blob/96c08767c47f8969c7285b5322f834f97978e7a3/src/main/kotlin/org/snippet/asset/service/AssetRoutesSpec.kt).
- [Backend](https://github.com/austral-ingsis/asset-service/blob/96c08767c47f8969c7285b5322f834f97978e7a3/src/main/kotlin/org/snippet/asset/service/store/AzureObjectStore.kt).
- [Tests HTTP](https://github.com/austral-ingsis/asset-service/blob/96c08767c47f8969c7285b5322f834f97978e7a3/src/test/kotlin/org/snippet/asset/service/ServerTests.kt).

La ruta es `/v1/asset/{container}/{key}`. PUT recibe contenido crudo y devuelve
201 al crear o 200 al sobrescribir; GET devuelve el contenido. El backend usa
sobrescritura habilitada, sin escritura condicional verificada. La implementación
de DELETE devuelve 204 o 404, aunque su anotación documenta 200. La anotación de
GET menciona `text/event-stream`; las pruebas reales comprobaron los bytes
recibidos solicitando `application/octet-stream`, sin deducirlo solo de OpenAPI.

La imagen revisada rechaza PUT con cuerpo vacío (HTTP 400), incluso con un cliente
JDK independiente. Se decidió representar todos los códigos mediante una envoltura
versionada, sin modificar el servicio de la cátedra. «Contenido crudo» describe
el transporte de bytes de Asset, no una exigencia de que esos bytes sean código
directamente legible. Ver la decisión y el formato propios más abajo.

El servicio no comprueba ownership. La autorización sigue perteneciendo a
Permissions y los casos de uso de Snippets. No debe publicarse Asset directamente
como una API de la UI.

Estas observaciones del código no prueban qué commit contiene una imagen publicada:
las pruebas deberán registrar su digest y el comportamiento efectivamente observado.

## Garantías que se deben conservar

- Cada guardado produce contenido independiente; nunca sobrescribir el contenido
  vigente usando el ID del snippet como key mutable.
- Actualización: guardar contenido nuevo, cambiar referencia y metadatos en BD,
  limpiar el anterior cuando la BD confirme qué referencia quedó sin usar.
- Una falla de BD puede dejar un commit incierto. No borrar el nuevo contenido
  suponiendo que no quedó referenciado.
- GET 404 representa ausencia; red, timeout y 5xx representan falla técnica.
- DELETE 204/404 se traduce a borrado idempotente. Un timeout no confirma el borrado.
- No reintentar automáticamente escrituras ni prometer una transacción distribuida.
- No cambiar referencias existentes ni activar el remoto sin inventario, copia
  verificada o un ambiente nuevo acordado, y procedimiento de recuperación.

## Decisiones aprobadas

Thiago aprobó las tres propuestas del plan con «aplicalo», el 19 de octubre de
2026, después de que el plan explicitara que estaban pendientes. Estado:
implementadas y verificadas; no equivale a autorización de corte.

| Elección | Motivo y ventaja | Alternativa descartada y costo aceptado |
| --- | --- | --- |
| `local` por defecto, proveedor explícito | Mantiene el arranque y datos actuales | Solo Asset simplifica variantes, pero exige corte previo. Mantenemos dos adaptadores. |
| Referencias UUID sin prefijo de proveedor | Permite copiar objetos conservando vínculos en BD | Prefijos permitirían transición gradual, agregando routing. Aceptamos corte global y no mezclar backends. |
| 1 MiB UTF-8 configurable | Acota memoria de entrada/salida del adaptador | 10 MiB o sin límite admiten más contenido. Rechazos grandes son `StorageUnavailable`, no un error público de tamaño nuevo. |
| Envoltura JSON `snippet-content-v1` para todos los objetos Asset | Permite persistir y recuperar exactamente el vacío sin esperar una corrección externa | Se descartó mantener código crudo y esperar a la cátedra, porque el usuario indicó que no habrá corrección. Aceptamos formato propio y migración con transformación. |

La cuarta decisión fue aprobada explícitamente por Thiago con «sí» después de
consultarle ventajas, desventajas y la propuesta de formato. Reemplaza la elección
inicial de enviar directamente los bytes del código. Mantiene UUID, interfaz,
resultados, flujos y esquema; no autoriza migrar ni activar ambientes.

Cambiar el proveedor es reversible por configuración solo mientras ambos backends
contengan todos los objetos referenciados. Cambiar el formato de referencia
afectaría datos persistidos. El límite puede ajustarse sin recompilar; revisar su
impacto en memoria y en contenidos anteriores antes de reducirlo.

Se mantienen convenciones existentes: RestClient/JDK, límites de conexión de 2s
y lectura de 5s, configuración Spring y fallas de I/O convertidas a valores.
No se agregan dependencias HTTP ni se modifica la coordinación de casos de uso.

## Configuración y comportamiento

| Propiedad | Variable | Default |
| --- | --- | --- |
| `storage.provider` | `STORAGE_PROVIDER` | `local` |
| `storage.asset.base-url` | `STORAGE_ASSET_BASE_URL` | Requerida al elegir `asset` |
| `storage.asset.container` | `STORAGE_ASSET_CONTAINER` | `snippets` |
| `storage.asset.connect-timeout` | `STORAGE_ASSET_CONNECT_TIMEOUT` | `2s` |
| `storage.asset.read-timeout` | `STORAGE_ASSET_READ_TIMEOUT` | `5s` |
| `storage.asset.max-content-bytes` | `STORAGE_ASSET_MAX_CONTENT_BYTES` | `1048576` |

La URL debe ser HTTP(S), con puerto válido si se indica, sin credenciales, query,
fragmento ni subruta. El container
debe cumplir el formato del backend blob oficial: 3–63 caracteres alfanuméricos
minúsculos/guiones, sin guiones consecutivos ni en los extremos. Los timeouts y
el límite deben ser positivos. La configuración Asset se valida al seleccionar
ese proveedor; local no exige una URL remota ni contacta el servicio remoto.
Un proveedor desconocido impide arrancar, sin fallback silencioso.

Se envía y solicita `application/octet-stream`; el body contiene los bytes UTF-8
de la envoltura, no otro transporte HTTP ni un SDK. Lecturas aceptan ese tipo o
`text/plain` UTF-8 y decodifican estrictamente, sin reemplazar bytes corruptos ni
surrogates inválidos. El límite de 1 MiB corresponde a bytes UTF-8 del código,
no al tamaño del JSON. No es un límite global del request HTTP público: el código
ya llega como String al storage.

### Formato persistido

```json
{"format":"snippet-content-v1","code":""}
```

El vacío produce un objeto no vacío; al leer se devuelve exactamente `""`. Todo
contenido usa el mismo formato, incluso si el propio código parece un JSON con
campos `format` y `code`: se envuelve una vez y se extrae una vez. No hay detección
heurística, fallback a crudo ni tratamientos especiales que agreguen espacios.

Se exige un objeto con exactamente esos dos campos string y la versión conocida.
Versiones desconocidas, campos duplicados, JSON extra al final, bytes inválidos,
contenido sobre el límite o un objeto sin envoltura resultan en `StorageUnavailable`,
no `Missing` ni código vacío inventado. No son errores de sintaxis de PrintScript.

El tope HTTP se deriva como `6 × límite_del_código + overhead_del_JSON_vacío`.
El factor 6 contempla el peor escape de un byte de control, por ejemplo NUL a
`\u0000`. Se lee hasta ese tope + 1, incluso sin Content-Length; después de decodificar
se vuelve a comprobar el límite real del código. Los límites que desbordarían el
contador de bytes HTTP se rechazan al arrancar. La validación no asume que el
tamaño declarado por el servidor sea correcto.

El costo es que leer los blobs fuera de Snippets exige conocer este formato.
Copiar de local a Asset requiere envolver; volver al local requiere extraer el
código. Un cambio de versión necesita una decisión de compatibilidad y migración,
no cambiar la constante dejando objetos viejos ilegibles.

En PUT se acepta 201/200 con body vacío. No se deduce la key del header Location:
la elige el cliente antes de escribir. En GET se diferencia 404 de falla técnica;
DELETE 204/404 es idempotente. No se siguen redirects ni se agregan reintentos.

Cada PUT utiliza un UUID aleatorio nuevo. Esto evita sobrescrituras entre operaciones
normales del adaptador, pero la API no ofrece create-only: no hay garantía ante una
colisión de UUID o escritores externos que usen la misma key. Mantener el container
interno y exclusivo del ambiente. No presentar esta convención como escritura
condicional provista por Asset.

## Resultados inciertos y huérfanos

Un timeout o respuesta inválida de PUT puede ocurrir después de escribir. Se devuelve
`StorageUnavailable` y se registra operación/key, nunca código ni credenciales.
La interfaz no devuelve una referencia en ese fallo: no se altera para introducir
recuperación distribuida. Un reintento crea otra key; el intento previo puede quedar
huérfano. DELETE incierto también registra su key; repetirlo es idempotente.

No hay borrado automático por antigüedad ni por un único inventario de BD. Para
resolver huérfanos se deben detener escritores, correlacionar keys del registro con
referencias confirmadas y PENDING, comprobar de nuevo los vínculos y autorizar la
limpieza. Si se perdieron los registros, Asset no proporciona aquí una API de listado:
el inventario operativo del backend corresponde a infra. Nunca inferir ausencia de
escritura a partir de `StorageUnavailable` ni borrar contenido posiblemente vinculado.

## Preparación del corte por ambiente

Pendiente: elegir qué datos se conservan y autorizar la ejecución por ambiente.
No se consultaron VMs ni se inventariaron bases de dev/prod. En la inspección local
solo había un PostgreSQL de otro proyecto en ejecución; no fue consultado.

Para conservar datos, el procedimiento propuesto es:

1. Identificar ambiente, versión, container y directorio/volumen local reales. Detener
   todas las instancias y procesos capaces de crear/editar/borrar; mantener el backend
   origen accesible para copia. No usar como origen el directorio efímero de `build`.
2. Respaldar PostgreSQL y el volumen de contenido. Guardar versiones y configuración
   sin secretos. Comprobar restauración y que no queden escritores activos.
3. Exportar el inventario completo de referencias y pendientes, sin filtrar CONFIRMED:

   ```sql
   SELECT state, count(*) AS snippets, count(content_reference) AS linked
   FROM snippets GROUP BY state;

   SELECT id, state, content_reference FROM snippets
   WHERE content_reference IS NOT NULL ORDER BY id;

   SELECT id, creation_key FROM snippets
   WHERE state = 'PENDING' AND content_reference IS NULL ORDER BY id;
   ```

4. Para cada referencia distinta validar UUID canónico, existencia, UTF-8, tamaño
   y SHA-256 del archivo origen. Ante un faltante, referencia inválida o tamaño que
   exceda el límite elegido, detenerse y decidir cómo resolverlo; nunca truncar.
5. En un container dedicado del ambiente, GET de cada key. Si existe, validar la
   envoltura y comparar los bytes UTF-8 del código extraído contra el archivo local.
   Si coinciden, marcarla verificada; si el objeto tiene otro formato o contenido,
   detenerse sin sobrescribir. Si falta, codificar el archivo con `AssetEnvelope`
   y hacer PUT de esa envoltura bajo el mismo UUID. La comprobación previa no es
   atómica: exige exclusividad de escritores durante toda la copia.
6. Tras cada PUT, GET, validar/decodificar la envoltura y comparar bytes/tamaño/hash
   del código extraído. No comparar directamente el JSON remoto con el archivo
   local. Ante timeout, consultar la misma key antes de decidir qué ocurrió. No
   generar otra key para la copia ni tratar red caída como ausencia. Mantener un
   manifiesto con versión de formato y hash del código, sin su contenido.
7. Verificar que todas las referencias inventariadas, incluidas PENDING, existen
   y coinciden. Las reservas sin contenido se mantienen como tales. No se cambian
   UUIDs, metadatos, fingerprints ni relaciones de Permissions.
8. Solo con aprobación del ambiente, activar `STORAGE_PROVIDER=asset` y su URL/
   container mediante SNI-33. Verificar lecturas y reintentos pendientes antes de
   admitir nuevas escrituras. Registrar resultado y versiones.

### Recuperación

Si la copia falla antes de activar, mantener local y sus datos sin modificar; no
borrar automáticamente objetos remotos copiados. Puede reanudarse verificando keys.
Si Asset se activó pero todavía no hubo escrituras nuevas, se puede volver al origen
tras verificar que conserva todo el inventario.

Si ya hubo escrituras remotas, no basta con volver a `local`: detener escritores,
inventariar referencias vigentes de nuevo, extraer al local el código de los objetos nuevos y
verificar coherencia con la BD actual antes de cambiar. Un backup viejo de BD no
contiene necesariamente las creaciones/actualizaciones posteriores. No restaurarlo
silenciosamente ni borrar versiones nuevas como estrategia de rollback.

Este procedimiento debe validarse en un ensayo y sobre el inventario del ambiente
antes de declarar la transición operativa completa. Las pruebas de fixture no
constituyen una migración de dev/prod.

## Verificación de la base

El 19 de octubre de 2026, sobre `dev` en `8aa8745`, `sh ./gradlew check build
--no-daemon` terminó correctamente con JDK 21 y Docker. La suite registró 189
tests: 180 exitosos, 9 omitidos y ninguna falla. Esto acredita únicamente la base
anterior a la implementación del adaptador remoto.

El índice publicado de Asset Service se resolvió al digest
`sha256:eacad54bf95717c5483619ad1fe45a9b4a0012a37b451c37baf13e6f43200656`.
Las pruebas reales usan ese digest, no `latest`. Azurite 3.35.0 está fijado a
`sha256:647c63a91102a9d8e8000aab803436e1fc85fbb285e7ce830a82ee5d6661cf37`;
PostgreSQL usa la versión 18.6 del fixture existente.

## Verificación final del adaptador versionado

El 19 de octubre de 2026, `sh ./gradlew check build --no-daemon` terminó con
`BUILD SUCCESSFUL`: 209 tests, 200 exitosos, 9 omitidos, sin fallas ni errores.
Las omisiones corresponden a pruebas opcionales de PrintScript/Permissions reales;
las pruebas nuevas de Asset no se omitieron. Compilación, ktlint y detekt pasaron.

Las siete pruebas de integración nuevas ejecutaron Asset Service oficial, Azurite
y PostgreSQL efímeros. Verificaron código vacío, Unicode, espacios/saltos, contenido
de 1 MiB y su peor escape JSON, borrado idempotente, copia de UUID local, reintento
de creación sin repetir ownership, concurrencia y fallos de commit con estado previo
accesible. El ensayo de copia transforma a la envoltura y conserva metadatos,
referencia y archivo origen.

Las pruebas de codec y HTTP verifican también versión desconocida, campos extra o
duplicados, JSON adicional, surrogates/UTF-8 inválidos, límite del código y de wire,
timeouts antes/después de headers y respuestas incompatibles. El wiring prueba
proveedor local por defecto, selección de Asset sin tocar directorio local y rechazo
de configuración inválida. No se instaló un fallback entre proveedores.

Esto habilita revisión del código, no declarar una migración de ambientes completa:
falta acordar qué datos conservar, inventariarlos y validar el procedimiento sobre
el ambiente elegido antes del corte. SNI-33 y SNI-36 conservan su alcance de infra
y recorrido integrado con UI. No se ejecutaron commits, push ni cambios en Linear.

## Diagnóstico previo y resolución aprobada

Antes de aprobar la envoltura, la suite sobre el adaptador crudo ejecutó 204 tests: 194 exitosos, 9 omitidos
(servicios externos opcionales) y 1 fallido, `real assets support empty content
without encoding a substitute`. Ktlint y detekt pasaron. `check/build` no finalizan
correctamente por esa falla. Ese resultado histórico no es la verificación final
del adaptador versionado. Se conserva el diagnóstico, no se omite la prueba del vacío.

Contra la imagen fijada, PUT de un cuerpo vacío devolvió HTTP 400. Unicode,
contenido de exactamente 1 MiB, GET/DELETE, reintentos de creación, concurrencia,
fallos de commit y copia de una referencia local a Asset sí pasaron contra Asset,
Azurite y PostgreSQL reales. Las fallas de BD se inyectan alrededor del adaptador
JDBC real; identidad, Language y registro de ownership son colaboradores de prueba
en estos tests focalizados, no proveedores reales. No es el recorrido UI de SNI-36.

Se repitió el PUT vacío con un cliente JDK independiente de RestClient: también
devolvió 400. La prueba actual mantiene la expectativa de recuperar exactamente
el vacío, utilizando la representación aprobada. Este diagnóstico descarta
que la respuesta dependa únicamente del adaptador HTTP de Spring usado en Snippets.

La copia ensayada conservó UUID, metadatos pendientes y archivo local, sin cambiar
el vínculo de BD. Esto valida el mecanismo de copia en un fixture, no un inventario
ni un corte sobre dev/prod. El timeout incluye cuerpo demorado tras los headers;
las pruebas de HTTP controlado también cubren respuestas chunked sobre el límite.

Se consultó a Thiago antes de cambiar el diseño para resolver el vacío:

- Recomendación: mantener contenido crudo y requerir una imagen oficial corregida
  antes de cerrar/activar. Mantiene interoperabilidad sin formato propio; cuesta
  depender de una corrección externa.
- Alternativa: aprobar una envoltura versionada para todos los contenidos. Permite
  representar el vacío sin perder código al leer, pero los blobs dejan de ser texto
  crudo y la migración debe transformar/verificar ese formato.

Thiago aprobó la segunda alternativa después de explicitar que no habría una
corrección oficial. No se agregan espacios al código, Base64 ni éxitos sin
persistencia. La elección del ambiente y de los datos a conservar sigue pendiente,
sin activaciones implícitas.
