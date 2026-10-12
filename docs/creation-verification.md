# Verificación integrada de creación — SNI-15

SNI-15 verifica el backend compartido de US1 y US3. La implementación está en
SNI-6, SNI-7, SNI-8, SNI-9 y SNI-17: no requiere otro endpoint ni otro caso de uso.
El [contrato de creación](creation.md) sigue siendo la propuesta backend para la UI.

## Qué se verifica

Se usan Snippets, Permissions y PrintScript reales, las dos bases PostgreSQL y
el adaptador local de storage autorizado por la cátedra. Archivo y editor envían
el mismo JSON a `POST /snippets`; el origen del texto no cambia la lógica backend.
Esta comprobación no prueba la UI, Auth0, las VMs ni el futuro storage oficial.

Las pruebas existentes cubren dos niveles distintos:

- `CreationHttpTest` prueba el HTTP de Snippets, PostgreSQL y archivos locales;
  sustituye las dependencias HTTP para provocar timeouts y respuestas concretas.
- `CreationLiveTest` ejecuta el HTTP de Snippets en un contexto Spring de prueba,
  con PostgreSQL de Testcontainers y Permissions/PrintScript reales.
  **Se omite si falta cualquiera de sus dos variables de entorno.**
- La verificación con Compose comprueba también la imagen de Snippets, el DNS
  entre contenedores y el volumen de contenido al recrear la aplicación.

## Preparar el entorno

Los cuatro repositorios deben estar actualizados desde `dev`, clonados como
carpetas hermanas. Preparar el `.env` de infra siguiendo su README. No imprimir
ese archivo ni copiar sus credenciales a este documento.

Desde `snippet-searcher-infra`, construir en secuencia:

```powershell
docker compose -f compose.yaml build snippets-service
docker compose -f compose.yaml build permissions-service
docker compose -f compose.yaml build printscript-service
docker compose -f compose.yaml up --no-build -d
docker compose -f compose.yaml ps
```

Comprobar el código de salida de cada comando antes de continuar. Snippets y
PrintScript usan actualmente la misma caché de BuildKit para Gradle: construir
ambos en paralelo puede fallar por un lock de esa caché. La construcción en
secuencia permite verificar este ticket sin modificar los Dockerfiles.

Esperar a que las aplicaciones terminen de arrancar. `Up` solo informa que el
proceso está vivo; la base debe estar `healthy` y el endpoint debe responder.
Compose habilita el perfil Spring `dev`, necesario para `X-Dev-Actor-Id`.

## Ejecutar las pruebas existentes

Desde `snippets-service`, con JDK 21, Docker disponible y las credenciales locales
de GitHub Packages configuradas para Gradle:

```powershell
$env:PRINTSCRIPT_SERVICE_URL = "http://localhost:8082"
$env:PERMISSIONS_SERVICE_URL = "http://localhost:8081"
.\gradlew.bat build --no-daemon
```

En macOS/Linux usar `./gradlew build --no-daemon` y exportar las mismas variables.
Revisar `build/test-results/test/TEST-com.jjt.ingsis.snippets.http.CreationLiveTest.xml`:
debe registrar sus dos tests ejecutados, sin fallos ni omisiones. El reporte HTML
está en `build/reports/tests/test/index.html`.

Testcontainers usa su propia base de pruebas; estas pruebas no borran ni
reemplazan las bases de Compose. Las pruebas live sí registran ownerships de
prueba en Permissions.

### Alternativa usada en esta ejecución: Gradle dentro de Linux

El JDK de Windows falló al establecer la conexión local del daemon antes de
compilar. Se ejecutó Gradle con JDK 21 dentro de Docker Desktop Linux. Desde
`snippets-service`, con `GITHUB_ACTOR` y `GITHUB_TOKEN` definidos en la terminal:

```powershell
docker run --rm --name sni15-gradle-verification `
    --network snippet-searcher-infra_default `
    --mount "type=bind,source=$((Get-Location).Path),target=/workspace" `
    --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock `
    --mount type=volume,source=sni15-gradle-verification,target=/root/.gradle `
    --workdir /workspace `
    --env GITHUB_ACTOR --env GITHUB_TOKEN `
    --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
    --env PRINTSCRIPT_SERVICE_URL=http://printscript-service:8080 `
    --env PERMISSIONS_SERVICE_URL=http://permissions-service:8080 `
    eclipse-temurin:21-jdk sh ./gradlew build --no-daemon `
    --project-cache-dir /tmp/gradle-project-cache
```

Esta alternativa es para Docker Desktop: el socket permite a Testcontainers
crear sus bases y `host.docker.internal` permite alcanzar sus puertos publicados.
Las URLs de las dependencias usan el DNS de la red de Compose. Las credenciales
se pasan al proceso temporal por nombre de variable, sin incluir sus valores en
el comando, y el contenedor se elimina al terminar. El volumen adicional conserva
solo la caché de Gradle; los reportes quedan en el `build/` del servicio.

## Comprobar la imagen y las tres fuentes de datos

Desde infra, crear una clave nueva por ejecución y una identidad de prueba:

```powershell
$key = [guid]::NewGuid().ToString()
$actor = "sni-15-verification"
$headers = @{ "Idempotency-Key" = $key; "X-Dev-Actor-Id" = $actor }
$code = "println(1);"
$body = @{
    name = "Verificación SNI-15"
    description = "Creación integrada"
    language = "printscript"
    version = "1.0"
    code = $code
} | ConvertTo-Json
$jsonBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
$created = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/snippets" `
    -ContentType "application/json; charset=utf-8" -Headers $headers -Body $jsonBytes
$id = [guid]::Parse($created.id).ToString()
docker compose -f compose.yaml exec -T snippets-db psql -U snippets -d snippets `
    -c "SELECT id, name, description, language, version, state, content_reference FROM snippets WHERE id = '$id'::uuid;"
Invoke-RestMethod "http://localhost:8081/ownership/$id"
$reference = (docker compose -f compose.yaml exec -T snippets-db psql -U snippets -d snippets `
    -t -A -c "SELECT content_reference FROM snippets WHERE id = '$id'::uuid;").Trim()
$reference = [guid]::Parse($reference).ToString()
$encoded = docker compose -f compose.yaml exec -T snippets-service base64 "/var/lib/snippets/content/$reference"
$stored = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($encoded -join "")))
if ($stored -cne $code) { throw "El contenido guardado no coincide con el enviado." }
```

Esperado: `201`, metadatos `CONFIRMED`, referencia de contenido existente, texto
exacto y ownership con el mismo UUID y `$actor`. Inspeccionar el archivo por su
referencia es una comprobación del adaptador local; los clientes HTTP deben
seguir tratando esa referencia como opaca.

Para la variante archivo, obtener `$code` mediante
`[System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)` antes de armar
el JSON. Usar código válido con CRLF, tabulaciones, caracteres Unicode y espacios
finales, y comparar el contenido exacto. Repetir para PrintScript `1.1`.

## Reintentos y persistencia

Repetir el POST con el mismo body, clave e identidad: debe responder `200` y el
mismo UUID, sin cambiar metadatos, referencia ni owner. Cambiar el nombre o la
identidad conservando la clave debe responder `409` y conservar el registro.

Recrear únicamente la aplicación, conservando los volúmenes:

```powershell
docker compose -f compose.yaml up --no-build -d --no-deps --force-recreate snippets-service
docker compose -f compose.yaml logs --tail 30 snippets-service
```

Después del arranque, repetir el POST original y las consultas de la sección
anterior: `200`, mismo UUID, referencia, contenido y owner. No usar `down --volumes`:
el objetivo es comprobar la persistencia, no reiniciar las bases.

## Rechazos y fallos parciales

Usar una clave nueva para cada caso. Cuando se indica «sin reserva», comprobar
que no haya filas con esa `creation_key` en `snippets`.

| Caso | Resultado esperado |
| --- | --- |
| `let total: number = 5\nprintln(total);` | `422 invalid-code`, diagnósticos con regla, mensaje, línea y columna; sin reserva |
| Lenguaje `python` | `422 unsupported-language`; sin reserva |
| PrintScript `9.9` | `422 unsupported-version`; sin reserva |
| Agregar `ownerId` al body | `400 invalid-request`; sin reserva |
| Omitir `X-Dev-Actor-Id` | `401 identity-unavailable`; sin reserva |
| PrintScript detenido | `502 dependency-failure`; sin reserva |
| Permissions detenido | `502 dependency-failure`; reserva `PENDING`, con contenido, sin owner |
| Misma clave después de recuperar Permissions | `201`, mismo UUID pendiente, contenido y owner consistentes |

Para provocar los dos fallos de dependencia, usar `docker compose -f compose.yaml
stop printscript-service` o `stop permissions-service`, de uno en uno, y restaurar
siempre con `start` al terminar. Esperar el arranque antes de continuar. La JVM
puede conservar una resolución DNS negativa durante unos segundos después de
detener un contenedor: no interpretar ese `502` transitorio como código inválido.

Antes de completar una reserva pendiente, recrear Snippets y comprobar que
`PENDING`, UUID y referencia persisten. Recuperar Permissions y reintentar con
la misma clave, identidad y body: solo entonces puede confirmarse el alta.

Los timeouts con resultado incierto, las fallas de storage y persistencia y los
conflictos de ownership se provocan en las pruebas existentes de HTTP y del flujo.
No se interrumpen ni alteran datos existentes para simularlos en Compose.

## Resultado de esta ejecución

Fecha: 12 de octubre de 2026 (UTC). Revisiones usadas:

| Repositorio | Commit de `dev` |
| --- | --- |
| Snippets | `efe757637fc753bac35a267fb32d32dfb3cb8d2d` |
| Permissions | `ce7e20bfbfcded10fde39fcc0c3c963c67327616` |
| PrintScript | `aee17456de01505b27f562e07e7cac4bb6db5344` |
| Infra | `1743f457be700ad6f57c935bcd69db2b7e70d6dc` |

Las diez comprobaciones de Compose pasaron: altas/reintentos 1.0 y 1.1,
texto de archivo exacto, conflictos de clave/identidad, diagnósticos de código
inválido, restantes rechazos, caída de PrintScript, caída de Permissions,
recuperación de una reserva tras recrear Snippets y persistencia de confirmados
tras una segunda recreación. En las cuatro altas se compararon metadatos,
contenido y ownership; el UUID pendiente se conservó al completar la operación.

Identidad de esta verificación: `sni-15-b3fb1733-a825-4b48-8b50-91b5ad3f9c17`.
UUIDs confirmados:

- `45f153ef-23ab-4923-8372-2ba76677316a` — PrintScript 1.0.
- `cb680be2-4206-44b4-b69e-7b69df2a53a9` — PrintScript 1.1.
- `a1ad855c-d5dd-4aee-9c6c-0cd40799c3b3` — archivo UTF-8 con CRLF y tabulaciones.
- `74262713-2286-4882-b66e-7c6e979916b0` — recuperación del pendiente de Permissions.

El contenedor de Snippets cambió de `57410b39c8db` a `94cef8ba74c7` en la segunda
recreación, conservando los cuatro registros, sus referencias, textos y owners.
Los datos y los contenedores de Compose quedaron disponibles para inspección.
No se borraron volúmenes ni se publicaron imágenes desde esta rama.

`./gradlew build --no-daemon` pasó dentro del runner Linux con JDK 21:
`BUILD SUCCESSFUL in 9m 22s`. Se ejecutaron 189 tests en 41 suites, sin fallos,
errores ni omisiones, además de ktlint, detekt, JaCoCo y empaquetado.
`CreationLiveTest` ejecutó sus dos casos; las variables live también habilitaron
los tres tests de `PrintScriptServiceLiveTest` y los cuatro de `UpdateLiveTest`.
Estos últimos pasaron como parte de la suite existente, sin ampliar SNI-15.

Los reportes quedan en `build/reports/tests/test/index.html` y
`build/test-results/test/`. El resumen de las diez comprobaciones de Compose
queda en `build/sni15-results.json`, un artefacto local excluido de Git.
