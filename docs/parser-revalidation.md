# Activación y revalidación del parser

La activación operativa selecciona una revisión exacta del parser para un lenguaje y versión. Verifica `capabilities.validate` y acredita la revisión con una validación vacía antes de confirmar. En una transacción guarda referencia, historial, trabajo y evento `ParserVersionActivated`. Un despliegue de PrintScript por sí solo no activa la referencia.

Repetir el mismo UUID con los mismos argumentos vuelve al trabajo original. Otro UUID representa una nueva activación, aunque la revisión coincida. El consumidor exige la revisión esperada en cada respuesta, recorre los snippets confirmados por cursor UUID y fija un snapshot de cada elemento. Si contenido, lenguaje, versión o activación cambian, el resultado viejo queda `SUPERSEDED`. Un resultado `valid=false` registra diagnósticos de validación. Los originales se conservan.

## Comando operativo

`parser-admin` usa el jar y la configuración del contenedor con Linux y UID 0. Registra el operador del sistema, no inicia servidor HTTP ni migra la base. La JVM adicional necesita memoria disponible. Los resultados de revalidación son internos.

```sh
docker exec --user 0 <contenedor-snippets> java -Xmx192m -jar /app/app.jar parser-admin activate \
  --request-id <uuid-nuevo> --language printscript --version 1.1 \
  --processor sha256-v1:<64-hex> --reason 'Corrección revisada del parser'

docker exec --user 0 <contenedor-snippets> java -Xmx192m -jar /app/app.jar parser-admin status \
  --job-id <uuid-del-trabajo>
```

`status` informa el progreso con la misma autorización operativa. Los análisis comparten el presupuesto de [capacidad de fondo](background-consumer.md). Su admisión temporal se puede recuperar al vencer; el vencimiento no acredita el término remoto.
