# Verificación integrada de actualización — SNI-16

SNI-16 comprueba el backend compartido de US2 y US4. SNI-10 implementó la
preparación y SNI-18 completó la escritura; esta verificación reutiliza ambas
entregas y la creación comprobada en SNI-15.

El [contrato de actualización](update.md) sigue siendo una propuesta backend
para la UI: archivo y editor envían el mismo JSON a `PUT /snippets/{id}`.
No requiere un endpoint diferente ni un caso de uso adicional.

## Entorno y preparación

Se usan las imágenes locales de Snippets, Permissions y PrintScript, PostgreSQL
y el storage local persistente de SNI-8. Los repositorios están clonados como
carpetas hermanas y actualizados desde `dev`. Preparar el `.env` de infra según
su README; las credenciales no se incluyen en esta evidencia.

Desde `snippet-searcher-infra`:

```powershell
docker compose -f compose.yaml up --no-build -d
docker compose -f compose.yaml ps
```

Este comando presupone imágenes construidas desde las revisiones que se quieren
verificar. Si cambió el código, construir primero cada servicio en secuencia con
`docker compose -f compose.yaml build <servicio>`. Snippets y PrintScript comparten
actualmente la caché Gradle de BuildKit; el build paralelo puede fallar por un lock.

Esperar a que ambas bases estén saludables y las aplicaciones respondan. Compose
habilita el perfil Spring `dev`, necesario para la identidad por header. Se crean
registros específicos de SNI-16, sin modificar los registros de SNI-15 ni borrar
volúmenes. Esta comprobación no incluye la UI, Auth0, VMs/CD ni el storage oficial.

## Pruebas existentes

| Prueba | Evidencia |
| --- | --- |
| `UpdateLiveTest` | Cuatro casos: creación/actualización, no-owner, código inválido y cambio de versión, con Permissions y PrintScript reales |
| `UpdateHttpTest` | Contrato HTTP, rechazos, archivo exacto, pendientes y fallas de las dependencias, con HTTP de prueba controlable |
| `UpdateFlowTest` | Reemplazo, fallas de storage/base, resultado incierto, limpieza del contenido anterior y concurrencia |
| `UpdatePersistenceTest` | La preparación y los rechazos conservan las columnas; distingue pendientes |
| `PrepareUpdateTest`, `UpdateContractTest`, `UpdateOutcomeTranslatorTest`, `SnippetProblemTest` | Preparación, entradas, resultados y catálogo de errores |

Desde `snippets-service`, con JDK 21, Docker y las credenciales de Gradle:

```powershell
$env:PRINTSCRIPT_SERVICE_URL = "http://localhost:8082"
$env:PERMISSIONS_SERVICE_URL = "http://localhost:8081"
.\gradlew.bat test --tests '*Update*' --tests '*SnippetProblemTest' --no-daemon
```

En macOS/Linux usar `./gradlew`. Sin cualquiera de las dos variables live,
`UpdateLiveTest` se omite: su XML debe registrar cuatro casos y cero omisiones.
Las bases de Testcontainers son independientes de las bases de Compose; los tests
live sí agregan ownerships de prueba a Permissions.

También se puede usar la alternativa Docker Linux/JDK 21 documentada en
[la verificación de SNI-15](creation-verification.md#alternativa-usada-en-esta-ejecución-gradle-dentro-de-linux),
cambiando la tarea `build` por `test --tests '*Update*' --tests '*SnippetProblemTest'`.
Las URLs live dentro de ese runner son `http://printscript-service:8080` y
`http://permissions-service:8080`, en la red de Compose.

Si cambia código o se agregan pruebas, ejecutar también `./gradlew build` con
las variables live. El build completo de SNI-15 ya comprobó esta misma
implementación y pasó sus 189 tests, ktlint, detekt, JaCoCo y empaquetado.

## Comprobar creación seguida de actualización en la imagen

Desde infra, crear un snippet exclusivo para esta comprobación:

```powershell
$actor = "sni-16-" + [guid]::NewGuid().ToString()
$headers = @{ "X-Dev-Actor-Id" = $actor }
$creationHeaders = @{ "X-Dev-Actor-Id" = $actor; "Idempotency-Key" = [guid]::NewGuid().ToString() }
$initial = @{
    name = "SNI-16 inicial"; description = "Descripción inicial"
    language = "printscript"; version = "1.0"; code = "println(1);"
} | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/snippets" `
    -ContentType "application/json; charset=utf-8" -Headers $creationHeaders `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($initial))
$id = [guid]::Parse($created.id).ToString()
$newCode = "println(2);"
$replacement = @{
    name = "SNI-16 actualizado"; description = "Descripción nueva"
    language = "printscript"; version = "1.1"; code = $newCode
} | ConvertTo-Json
$updated = Invoke-RestMethod -Method Put -Uri "http://localhost:8080/snippets/$id" `
    -ContentType "application/json; charset=utf-8" -Headers $headers `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($replacement))
docker compose -f compose.yaml exec -T snippets-db psql -U snippets -d snippets `
    -c "SELECT id, name, description, language, version, state, content_reference FROM snippets WHERE id = '$id'::uuid;"
Invoke-RestMethod "http://localhost:8081/ownership/$id"
$reference = (docker compose -f compose.yaml exec -T snippets-db psql -U snippets -d snippets `
    -t -A -c "SELECT content_reference FROM snippets WHERE id = '$id'::uuid;").Trim()
$reference = [guid]::Parse($reference).ToString()
$encoded = docker compose -f compose.yaml exec -T snippets-service base64 "/var/lib/snippets/content/$reference"
$stored = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($encoded -join "")))
if ($updated.id -ne $id -or $stored -cne $newCode) { throw "UUID o contenido incorrectos." }
```

Esperado: creación `201`, actualización `200`, mismo UUID y owner, metadatos
`CONFIRMED` con los datos nuevos y código exacto en la referencia vigente.
Los datos de idempotencia de la creación original tampoco deben cambiar.
La lectura directa del archivo es específica del adaptador local; su referencia
sigue siendo opaca para clientes HTTP.

## Casos a comprobar

Antes de cada rechazo guardar una copia de la fila completa, la referencia y el
contenido. Después comparar exactamente y comprobar que el owner sigue igual.

| Caso | Resultado esperado |
| --- | --- |
| Owner reemplaza código y metadatos | `200`, mismo UUID/owner, nuevos datos y contenido consistentes |
| Descripción ausente o `null` | `200`, descripción `null` |
| Descripción vacía | `200`, descripción `""` |
| Texto de archivo UTF-8 con CRLF, tabs y espacios finales | `200`, texto exacto |
| Repetir el mismo PUT | `200`, mismo resultado de negocio; la referencia interna puede cambiar |
| Actor diferente | `403 modification-denied`, sin cambios |
| Código inválido | `422 invalid-code`, diagnósticos con regla, mensaje, línea y columna; sin cambios |
| Lenguaje/versión no soportados | `422 unsupported-language/unsupported-version`, sin cambios |
| Campo obligatorio ausente o `ownerId` en el body | `400 invalid-request`, sin cambios |
| Identidad ausente | `401 identity-unavailable`, sin cambios |
| UUID inexistente | `404 snippet-not-found` |
| Creación pendiente | `409 snippet-pending`, conserva el pendiente |
| Permissions o PrintScript caídos | `502 dependency-failure`, sin cambios; recuperada la dependencia el PUT funciona |

Para verificar un cambio de versión, usar código que PrintScript 1.0 rechace y
1.1 acepte, por ejemplo el caso de `UpdateLiveTest` con `const`, `boolean` e `if`.
El código nuevo se valida contra la versión enviada, no contra la anterior.

Los fallos de dependencias se provocan con `docker compose -f compose.yaml stop
<servicio>`, de uno en uno, restaurando siempre con `start`. Esperar su arranque
y unos segundos de expiración de la caché DNS antes del reintento. Para obtener
un pendiente de prueba, crear mientras Permissions está detenido y comprobar
que el PUT se rechaza; después recuperar Permissions y completar el POST con
su clave original. No fabricar inconsistencias modificando filas existentes.

## Concurrencia, fallas parciales y persistencia

Dos PUT simultáneos del owner deben dejar los metadatos y el código completos de
uno de los pedidos. No hay control de revisiones: gana el último aplicado. Las
pruebas de concurrencia no deben suponer cuál de los dos ganará.

Las fallas de storage, SQL, ownership ausente y timeouts se provocan en las pruebas
controladas existentes. Un resultado incierto de la base puede dejar lo anterior
o lo nuevo, pero completo: no se promete conservar lo anterior si el commit ya
ocurrió. Si falla solo el borrado del archivo anterior, el cambio está confirmado
y devuelve `200`; ese archivo queda sin referenciar.

Para comprobar persistencia, guardar fila, referencia y contenido y recrear solo
Snippets:

```powershell
docker compose -f compose.yaml up --no-build -d --no-deps --force-recreate snippets-service
```

Esperar el arranque y volver a leer fila, contenido y ownership antes de enviar
otro PUT. Deben coincidir exactamente. Luego repetir el PUT: mismo UUID, owner y
datos/código, aunque se genere una nueva referencia. No usar `down --volumes`.

## Evidencia de cierre

Fecha: 12 de octubre de 2026. Revisiones verificadas:

| Repositorio | Commit de `dev` |
| --- | --- |
| Snippets | `3e233c9d8b54a8c247754282d4b5dadc7a4ff991` |
| Permissions | `ce7e20bfbfcded10fde39fcc0c3c963c67327616` |
| PrintScript | `aee17456de01505b27f562e07e7cac4bb6db5344` |
| Infra | `1743f457be700ad6f57c935bcd69db2b7e70d6dc` |

Se reutilizaron las imágenes locales construidas en SNI-15. Entre aquella
revisión de Snippets (`efe7576`) y esta solo se integró su documentación de
verificación; código, tests, configuración de Gradle y Dockerfile no cambiaron.

Las 13 comprobaciones de Compose pasaron: reemplazo autorizado; descripción
nula/ausente/vacía; archivo exacto y repetición; rechazo del no-owner; diagnósticos
de código inválido; restantes rechazos y recurso inexistente; cambio de versión;
fallos y recuperación de Permissions y PrintScript; pendiente rechazado antes
de consultar permisos; concurrencia; persistencia tras recrear Snippets; y
repetición del PUT después de esa recreación.

En cada rechazo de una actualización existente se compararon la fila completa,
la referencia y el texto anterior. Se comprobó el owner después de recuperar las
dependencias. Cada reemplazo exitoso conservó UUID, ownership y datos originales
de creación, vinculó el contenido correcto y eliminó el archivo que reemplazó.

Identidad usada: `sni-16-9ca4163e-0bd7-4d5d-ac33-479779b7783f`.
Registros exclusivos de esta verificación:

- `6f7692cb-dcae-42df-a193-d21c56282632` — reemplazos, rechazos y recuperación.
- `705f3229-9172-4006-a3b2-1263a9ac4922` — pendiente rechazado y luego confirmado.
- `a6826fbb-4c07-4749-a1f8-1d7ca8d5e7c6` — actualizaciones concurrentes.

En el caso concurrente ambos PUT respondieron `200` y quedó íntegro el pedido A;
esa elección es evidencia de esta ejecución, no una garantía de orden. Snippets
se recreó de `94cef8ba74c7` a `9bc7517ba061`, conservando fila, referencia, texto y
owner de los tres registros. Un PUT posterior del mismo pedido mantuvo el
resultado de negocio y generó otra referencia, como permite el contrato.

Los contenedores quedaron activos y ambas bases saludables. Los registros de
SNI-15 se conservaron; no se borraron volúmenes ni se publicaron imágenes.
El detalle local está en `build/sni16-results.json`, excluido de Git, que distingue
la referencia conservada en la recreación de la referencia final.

La ejecución seleccionada pasó: `BUILD SUCCESSFUL in 1m 7s`, con 50 tests en
10 suites, cero fallos, errores y omisiones. `UpdateLiveTest` ejecutó sus cuatro
casos; `UpdateHttpTest`, sus 15 casos, y `UpdateFlowTest`, sus 11 casos. El filtro
también ejecutó dos casos de metadatos relacionados con actualización.

Se usó Docker Linux/JDK 21, copiando las fuentes actuales al filesystem temporal
del runner y exportando los reportes a `build/sni16-tests/`, sin modificar las
fuentes locales. Las dependencias HTTP fueron los contenedores reales y
PostgreSQL de pruebas se creó mediante Testcontainers. El reporte HTML está en
`build/sni16-tests/report/index.html` y los XML en `build/sni16-tests/results/`.

No se agregaron pruebas ni se modificó código de producción, dependencias,
Gradle, Dockerfile o Compose. Se reutiliza la evidencia del build completo de
SNI-15 para esa misma implementación y se complementa con esta ejecución de
actualización y las 13 comprobaciones nuevas de Compose.
