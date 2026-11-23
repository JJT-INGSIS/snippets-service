# SNI-100 — Persistencia, adaptadores y fallas de borrado

Revisión del 23/11/2026. Base Snippets `1297b77`, infra `cfe4956` y
Permissions en el checkout `sni-82-permissions`. No se modificaron migraciones,
proveedores ni datos de DEV/PROD. La cobertura siguiente distingue lectura de
código, pruebas aisladas y evidencia histórica; no certifica el inventario actual.

## Defecto reproducido

`LocalSnippetContentStorage.delete()` devolvía `Deleted` tras cualquier
`Files.deleteIfExists()`, incluso si el directorio completo había desaparecido.
Java devuelve `false` en ese caso: no demuestra que el storage esté disponible.
La lectura ya distinguía ese escenario de un objeto inexistente, pero el borrado
no. `RetainedContentCleanup.remove()` usa `Deleted` para marcar una referencia
como `DELETED` en PostgreSQL; por eso un éxito falso tiene consecuencias durables.

La prueba mueve el directorio a otra ubicación temporal, intenta borrar y exige
`StorageUnavailable`; luego lo devuelve a su lugar, comprueba que el contenido
sigue ahí y que el borrado real y su repetición funcionan. Falló antes del cambio
(12 pruebas, un fallo) y verifica el resultado observable, no llamadas privadas.

Ahora se confirma el borrado si se eliminó realmente el archivo o si, estando
ausente, el directorio sigue disponible. En otro caso se devuelve la misma falla
técnica tipada que utiliza la lectura. No se crea de nuevo el directorio durante
un pedido ni se considera que un montaje perdido está vacío.

| Alternativa | Qué aporta | Costo o riesgo |
| --- | --- | --- |
| Confirmar cualquier ausencia | Borrado repetido barato | Oculta indisponibilidad del directorio y puede cerrar una limpieza falsamente |
| Comprobar directorio cuando no se borró nada, elegida | Conserva idempotencia y distingue la pérdida observable del storage | Una consulta al filesystem adicional en ese camino; no detecta todos los tipos de fallo de un volumen |
| Detectar identidad y salud del montaje en cada operación | Podría detectar un mount reemplazado por otro directorio | Agrega estado y dependencia del despliegue; no es el contrato actual del adaptador |

Se aplica una garantía ya documentada en `storage.md`: las fallas del filesystem
no se informan como éxito. No se añade una política nueva de limpieza. La consulta
no elimina carreras posteriores ni garantiza persistencia frente a pérdida del
disco; la prueba simula un directorio ausente, no un desmontaje real del kernel.

El análisis es propio, usando el marco de trade-offs y decisiones de los capítulos
2/21/27 de Richards/Ford, *Fundamentals of Software Architecture*, 2.ª edición,
según el [índice editorial](https://www.oreilly.com/library/view/fundamentals-of-software/9781098175504/).

## Revisión por responsabilidad

| Piezas leídas | Garantía y decisiones conservadas | Límite identificado |
| --- | --- | --- |
| `SnippetContentStorage`, `StorageResults`, `ContentReference` | Puerto pequeño; referencia opaca y resultados explícitos; sin HTTP en el caso de uso | No ofrece transacción con PostgreSQL ni reemplazo en el lugar |
| `StorageConfiguration`, propiedades local/Asset | Proveedor explícito, error al seleccionar uno desconocido; local no exige URL Asset | Cambiar configuración no copia contenido |
| `ContentDirectory` | Sólo UUID canónico convertido en ruta bajo un root preparado al iniciar | Supone directorio administrado y confiable; no verifica identidad del mount ni protege de un administrador que cambie archivos/symlinks |
| `AtomicTextFile` | Temporal, UTF-8 y rename atómico: no publica una escritura parcial | No equivale a backup ni garantiza todos los cortes de energía; no se forzó una caída de disco |
| `LocalSnippetContentStorage` | Escrituras nuevas, lectura exacta, ausencia separada de falla y borrado repetible | Corregida la ausencia del root al borrar |
| `AssetContentStorage` | UUID nuevo por escritura, 404 distinto de red/5xx; timeout no confirma una escritura | PUT oficial no es create-only: UUID aleatorio no constituye una garantía condicional |
| `AssetHttpTransport`, `AssetStorageProperties` | URL sin credenciales, deadlines, tope de bytes leídos y sin retry de escritura | Un PUT incierto puede dejar un objeto sin referencia; no inventar borrado compensatorio |
| `AssetEnvelope`, `AssetText` | Formato versionado, JSON y UTF-8 estrictos; límite del código distinto del JSON | Copiar a Asset exige transformar el formato; dos proveedores no son intercambiables sólo por variable |
| `RetainedContentCleanup` | Reclama limpieza, verifica referencias retenidas y confirma resultado en DB | Borrar remoto y actualizar DB siguen sin ser una transacción única |

## Esquema y recuperación

Se leyeron todas las migraciones presentes V1–V10 y V12–V14 de Snippets, y
V1–V2 de Permissions. No se renumeró ni agregó una V11: una secuencia sin ese
archivo no demuestra por sí misma una migración pendiente.

- Snippets guarda metadatos, referencias, tests JSONB, auditoría/snapshot de
  ejecuciones, capacidad, trabajos/outbox y resultados derivados. Las claves
  únicas y foráneas protegen relaciones dentro de esa base.
- Permissions guarda ownership y sharing en otra base. No hay FK distribuida
  hacia Snippets; la coordinación pertenece a los flujos de aplicación.
- El contenido fuente vive en archivos o Asset. Una auditoría puede conservar
  una copia/snapshot: no confundir «PostgreSQL no es el storage fuente» con
  «ninguna tabla puede contener código».
- Se mantienen los permisos de ejecución sin TTL: una lease vencida no demuestra
  que el proceso remoto haya terminado. No se liberan como optimización.

Restaurar sólo PostgreSQL puede dejar referencias sin objetos; restaurar sólo
contenido puede dejar objetos sin metadatos. El procedimiento debe detener o
coordinar escritores, registrar release/proveedor y pendientes, respaldar ambas
bases y el contenido de ese mismo corte, y copiar el conjunto fuera de la VM.
Para PostgreSQL usar dump lógico consistente por base con herramienta compatible;
no copiar su directorio de datos en caliente como supuesto backup coherente.

Restaurar primero en bases y volúmenes **nuevos y aislados**, conservar IDs y
referencias, comprobar migraciones, relaciones, hashes y lectura real de cada
objeto antes de autorizar un corte. Si se incluye Redis, usar también su runbook:
un RDB aislado no reconstruye el estado completo del proyecto. Un rollback de
imagen no revierte migraciones ni escrituras.

Este es el procedimiento requerido, **no evidencia de que existan backups
recientes o de que se haya restaurado DEV/PROD**. No se declara recuperación
integral comprobada por haber recreado contenedores.

## Matriz de definiciones y límites del inventario

En [infra](https://github.com/JJT-INGSIS/snippet-searcher-infra), `compose.yaml`
y `swarm/stack.yaml` montan `snippets_data` y `permissions_data` en
`/var/lib/postgresql`; `snippets_content` en `/var/lib/snippets/content`.
Los nombres efectivos llevan el prefijo del proyecto/stack. `stack.asset.yaml`
monta `asset_blobs:/data` en Azurite y no publica Asset/Azurite al host.

| Ambiente | Bases y contenido definidos | Qué falta probar como estado actual |
| --- | --- | --- |
| Local | Dos PostgreSQL, contenido local por defecto; Asset/Azurite optativos | No se arrancó el stack completo de aplicaciones en esta revisión |
| DEV | Stack independiente y volúmenes con prefijo `snippet-searcher-dev`; proveedor del release confirmado | Inventario fresco de `release.json`, mounts, referencias y backups |
| PROD | Stack independiente y volúmenes con prefijo `snippet-searcher-prod`; proveedor del release confirmado | El inventario del 19/11 registró `local`, sin Asset/Redis; no se presenta como estado del 23/11 |

Azurite es un emulador con volumen, no una cuenta de Azure Storage administrada.
En las definiciones revisadas Asset apunta a `http://azurite`; no hay evidencia
aquí de un bucket/contenedor de una cuenta Azure real activo en esos ambientes.

## Infraestructura revisada y evidencia aislada

Se leyeron `stack.asset.yaml`, `validate_asset_config.py`,
`verify_asset_persistence.py`, `asset_probe.py`, `storage_health.py`,
`docs/sni-33-asset.md` y `observability/sql/postgresql-monitor.sql` de infra.
El monitor local usa archivos propios y evita symlinks; el de Asset usa un fixture
privado por ambiente y requiere una sola instancia. Un probe sano no acredita
backup ni recuperación de objetos de negocio.

El rol de monitoreo concede CONNECT y `pg_read_all_stats`, sin SELECT de tablas
de negocio. `default_transaction_read_only` es un valor por defecto, no una
frontera de seguridad contra SQL arbitrario: la restricción relevante son los
privilegios. Las estadísticas pueden contener información operativa sensible.

El 23/11 pasó `verify_asset_persistence.py` con el proyecto aislado
`sni33-check-deaac17e1aab`: escritura/lectura exactas, reemplazo efectivo de ambos
contenedores, caída de Azurite como error técnico, recuperación y borrado
repetido. Se retiraron sólo sus contenedores/red y se conservó el volumen
`sni33-check-deaac17e1aab_asset_blobs`. No se usaron credenciales reales ni puertos
públicos; el contenedor ajeno `chopped_db` no se tocó.

La evidencia acredita el runtime Asset/Azurite de prueba, no la integración actual
de Snippets con Asset ni una restauración de backups de las bases reales. SNI-100
continúa en curso hasta contrastar inventario/backup y completar revisión cruzada.

Verificación local del cambio Kotlin: 15 pruebas de storage local/directorio/wiring,
`ktlintCheck`, `detekt` y `bootJar` aprobados con JDK 21 y un solo worker. Se usó
`--include-build ../gradle-conventions` para las convenciones locales: la resolución
del plugin privado publicado no estaba disponible en esta terminal. El build
completo con el plugin publicado debe quedar acreditado por CI antes del merge;
estas pruebas enfocadas no lo sustituyen.
