# Almacenamiento de contenido

PostgreSQL conserva metadatos y referencias. `SnippetContentStorage` guarda código original y derivados. Cada guardado obtiene una referencia nueva. Las actualizaciones confirman los metadatos y esa referencia juntos antes de intentar limpiar el contenido anterior. Los snapshots de trabajos retienen las referencias que necesitan.

| Configuración | Uso |
| --- | --- |
| `STORAGE_PROVIDER` | `local` por defecto, o `asset`. |
| `STORAGE_LOCAL_DIRECTORY` | `build/snippet-content` al ejecutar localmente. La imagen lo configura en `/var/lib/snippets/content` y requiere escritura para `10001:10001`. |
| `STORAGE_ASSET_BASE_URL` | URL interna de Asset Service, requerida en modo `asset`. |
| `STORAGE_ASSET_CONTAINER` | Contenedor exclusivo por ambiente. Por defecto `snippets`. |
| `STORAGE_ASSET_CONNECT_TIMEOUT` / `STORAGE_ASSET_READ_TIMEOUT` | Por defecto `2s` / `5s`. |
| `STORAGE_ASSET_MAX_CONTENT_BYTES` | Límite del código UTF-8. Por defecto 1 MiB. |

Local escribe archivos UTF-8 con nombre UUID mediante archivo temporal y renombre. Si el directorio deja de estar disponible, leer o borrar devuelve un fallo de storage. La ausencia de un blob sólo se confirma dentro de un storage disponible.


El adaptador remoto tiene su propio contrato de blobs y procedimiento de corte. [Asset Service](asset-storage.md)
