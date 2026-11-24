# Almacenamiento en Asset Service

Con `STORAGE_PROVIDER=asset`, Snippets usa la URL interna y el contenedor configurados para el ambiente. Envía un objeto por referencia UUID, con la envoltura UTF-8 `{"format":"snippet-content-v1","code":"…"}`. Una envoltura inválida es un fallo de storage. El `404` de lectura indica ausencia y el `404` de borrado se acepta como borrado idempotente. [Configuración](storage.md)

Un PUT remoto con timeout puede haber escrito el blob. Para reconciliar huérfanos, pausá escritores y cruzá el inventario con referencias confirmadas, pendientes y retenidas antes de borrar. Para cambiar de proveedor, copiá y verificá el inventario en el formato de destino antes del corte. Para volver después de nuevas escrituras, reconciliá las referencias con la base actual.
