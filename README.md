# Snippets Service

## Asset Service — SNI-19

El storage se selecciona con `STORAGE_PROVIDER=local|asset`; `local` sigue siendo
el default. Para Asset configurar `STORAGE_ASSET_BASE_URL` y, opcionalmente,
`STORAGE_ASSET_CONTAINER`, timeouts y límite de bytes. No hay fallback entre
proveedores: activar Asset sin copiar/verificar los datos locales deja referencias
sin contenido accesible. No se ejecuta una migración al arrancar.

El contrato, los trade-offs aprobados y el procedimiento de transición/recuperación
están en [docs/asset-storage.md](docs/asset-storage.md). Las pruebas de Asset usan
el servicio oficial y Azurite con imágenes fijadas, y PostgreSQL mediante Docker.
Asset guarda una envoltura `snippet-content-v1` con el código exacto; también admite
código vacío. PostgreSQL solo guarda metadatos y la referencia UUID. Una copia
desde local debe envolver el código y verificarlo al leer, no copiar bytes crudos.

Servicio HTTP de Snippet Searcher con Kotlin y Spring Boot. Expone la creación de snippets en `POST /snippets` y su actualización en `PUT /snippets/{id}`: valida el código mediante HTTP, guarda los metadatos en PostgreSQL y el código en un storage local provisional, y usa Permissions para registrar al owner y comprobar que solo él modifique.

## Catálogo de lenguajes — SNI-54

`GET /languages` es público, también fuera del perfil `dev`. Devuelve directamente
`[{"language":"printscript","extension":"prs","versions":["1.0","1.1"]}]`.
No requiere identidad ni consulta servicios externos. Describe capacidades
configuradas; no es un healthcheck. `Language` usa las mismas versiones para
rechazar pedidos no soportados antes de validar el código con PrintScript.

La UI local accede mediante `/api/languages`: el proxy de Vite elimina `/api`.
Esto no depende del futuro NGINX. No se agregan CORS ni rutas con `/api` al backend.
Ver contrato, decisiones aprobadas y costos en [docs/reading.md](docs/reading.md#lenguajes-disponibles).

La verificación completa usa `.\gradlew.bat build` con Docker disponible. Para
comprobar además compatibilidad con PrintScript real, definir
`PRINTSCRIPT_SERVICE_URL=http://127.0.0.1:8082` antes del build. Sin esa variable,
los tests optativos de integración con ese proveedor se omiten explícitamente.

## Compartir snippets — SNI-32

`PUT /snippets/{id}/shares/{userId}` permite al owner conceder lectura a un usuario
conocido. No lleva body ni `Idempotency-Key`: el primer registro responde `201` y
repetirlo responde `200` con `{snippetId,userId}`, sin duplicar accesos. La identidad
proviene del contexto del request; Permissions comprueba el ownership al registrar.

El destinatario ve el mismo contenido mediante listado/detalle y no puede editar
ni volver a compartir. Un timeout se informa como falta de confirmación: se puede
repetir explícitamente el PUT; no hay reintentos automáticos ni borrado compensatorio.
Ver [contrato, decisiones y pruebas reales](docs/sharing.md).

## Requisitos

- JDK 21 para ejecutar Gradle y la aplicación.
- No hace falta instalar Gradle: el repositorio incluye su wrapper.
- Docker disponible para las pruebas de persistencia PostgreSQL con Testcontainers.

La configuración compartida vive en `gradle-conventions`, versión `0.2.0`. El wrapper usa Gradle 9.3.0.

## Verificación

```powershell
.\gradlew.bat check
```

`check` ejecuta tests, ktlint y detekt, y genera el reporte JaCoCo. Gradle resuelve la convención publicada en GitHub Packages mediante `GITHUB_ACTOR` y `GITHUB_TOKEN`.

El test de arranque y el wiring de lenguaje usan H2 en memoria, disponible solo en tests, con Flyway desactivado. Las pruebas de metadatos usan PostgreSQL 18.6 mediante Testcontainers: requieren Docker y comprueban persistencia, reintentos, concurrencia, rollback y reinicio de la aplicación.

Para probar cambios de `gradle-conventions` sin publicarlos, con ese repositorio clonado al lado de este:

```powershell
.\gradlew.bat check --include-build ..\gradle-conventions
```

## Git hooks

Una vez por clon:

```powershell
.\gradlew.bat installGitHooks
```

- `pre-commit`: formatea con ktlint los archivos Kotlin en stage, los vuelve a agregar al commit y corre detekt.
- `pre-push`: corre `check` completo, con los tests.

El detalle está en el README de `gradle-conventions`.

## Arranque local

Para ejecutar Spring desde el IDE o con `bootRun`, configurar `JAVA_HOME` con JDK 21 y las credenciales de GitHub Packages para Gradle. Preparar primero el `.env` de `snippet-searcher-infra` según su README.

Desde la raíz de `snippets-service`, detener su contenedor de aplicación si estaba ejecutándose para liberar `8080`, y levantar solo su base desde infra:

```powershell
cd ..\snippet-searcher-infra
docker compose stop snippets-service
docker compose up -d --wait snippets-db
cd ..\snippets-service

$env:SPRING_PROFILES_ACTIVE = "dev"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/snippets"
$env:SPRING_DATASOURCE_USERNAME = "snippets"
$databasePassword = Read-Host "SNIPPETS_DB_PASSWORD del .env de infra" -AsSecureString
$env:SPRING_DATASOURCE_PASSWORD = [System.Net.NetworkCredential]::new("", $databasePassword).Password
Remove-Variable databasePassword

.\gradlew.bat bootRun
```

El servidor arranca en el puerto 8080 predeterminado de Spring Boot. El perfil `dev` habilita la identidad de desarrollo por header; sin él, `POST /snippets` y `PUT /snippets/{id}` responden `401`. Para crear o actualizar snippets también deben estar disponibles Permissions y PrintScript en sus destinos configurados.

## Arranque con Docker

Este repositorio conserva el `Dockerfile`, `.dockerignore` y `.gitattributes`. El único Compose del proyecto vive en [snippet-searcher-infra](https://github.com/JJT-INGSIS/snippet-searcher-infra), clonado como carpeta hermana:

```text
snippet-searcher/
├── snippets-service/
├── permissions-service/
└── snippet-searcher-infra/
    ├── compose.yaml
    └── .env
```

Preparar el `.env` de infra siguiendo su [README](https://github.com/JJT-INGSIS/snippet-searcher-infra#readme). Ese archivo contiene las credenciales de GitHub Packages y las contraseñas de las bases. No se necesita un `.env` en este servicio para usar Compose; cualquier archivo local existente sigue excluido de Git y del contexto de build.

Desde la raíz de `snippets-service`:

```powershell
cd ..\snippet-searcher-infra
docker compose up -d snippets-service
docker compose logs -f snippets-service
```

Compose levanta snippets y su base PostgreSQL, y espera a que la base esté saludable antes de iniciar la aplicación. Seleccionar snippets no detiene otros servicios que ya estén corriendo. Para levantar todo el entorno, ejecutar `docker compose up -d` desde infra.

Cuando cambie el código o el Dockerfile, reconstruir solo snippets desde infra:

```powershell
docker compose up --build -d snippets-service
```

El `Dockerfile` genera el `bootJar` con JDK 21 y lo ejecuta con JRE 21 y un usuario sin privilegios de root. Mantiene una caché de Gradle y recibe las credenciales de GitHub mediante secretos de BuildKit. Las verificaciones de calidad y tests siguen en `check` y en CI.

La conexión JDBC se configura mediante `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD`. Dentro del contenedor se usa `snippets-db:5432`; con `bootRun` en la computadora, `localhost:5432`. Gradle ejecutado directamente no lee el `.env` de infra automáticamente y sigue necesitando sus credenciales locales.

El servidor queda disponible en `http://localhost:8080`. Los endpoints son `POST /snippets` y `PUT /snippets/{id}`; un HTTP 404 en `/` es esperable.

Para detener solo snippets y su base sin afectar permisos, desde infra:

```powershell
docker compose stop snippets-service snippets-db
```

Los datos se conservan. Los volúmenes del antiguo Compose de este servicio no se importan automáticamente a infra. Los comandos de conexión SQL, limpieza de volúmenes y configuración del entorno completo están documentados en el README de infra.

## Language: validación de código

Los casos de uso validan código mediante `Language`, sin conocer qué servicio atiende cada lenguaje:

```kotlin
language.validate(language = "printscript", version = "1.0", code = "println(1);")
```

La respuesta es un `ValidationResult`, que obliga a tratar por separado cada caso:

| Resultado | Significado |
| --- | --- |
| `Valid` | El código es válido. |
| `Invalid` | No lo es. Cada diagnóstico conserva regla, mensaje, línea y columna. |
| `UnsupportedLanguage` | Ningún validador atiende ese lenguaje. |
| `UnsupportedVersion` | El servicio del lenguaje no soporta esa versión. |
| `Failed` | Falla técnica: el servicio está caído, tardó más que el límite o no respondió según su contrato. No dice nada sobre el código. |

Para PrintScript, `PrintScriptValidator` llama por HTTP a `POST /validate` de [printscript-service](https://github.com/JJT-INGSIS/printscript-service/blob/main/docs/validation.md). Es la única clase que conoce ese contrato; este servicio no usa la biblioteca PrintScript. Las versiones soportadas las decide `printscript-service`.

| Propiedad | Variable de entorno | Valor por defecto |
| --- | --- | --- |
| `language.printscript.base-url` | `LANGUAGE_PRINTSCRIPT_BASE_URL` | `http://localhost:8082` |
| `language.printscript.connect-timeout` | `LANGUAGE_PRINTSCRIPT_CONNECT_TIMEOUT` | `2s` |
| `language.printscript.read-timeout` | `LANGUAGE_PRINTSCRIPT_READ_TIMEOUT` | `5s` |

No hay reintentos. Si `printscript-service` no está disponible, este servicio arranca igual y cada validación devuelve `Failed`.

Los tests usan un servidor HTTP local. Para comprobar además contra el servicio real, levantarlo y definir su dirección:

```bash
PRINTSCRIPT_SERVICE_URL=http://localhost:8082 ./gradlew test
```

Sin esa variable, `PrintScriptServiceLiveTest` se omite.

## Responsabilidad prevista

Este servicio administrará los metadatos y el ciclo de vida de los snippets, los listados y los tests, y coordinará operaciones con los servicios de permisos y de lenguaje.

## Metadatos — SNI-6

La persistencia JDBC y sus garantías están documentadas en [docs/metadata.md](docs/metadata.md). Flyway prepara el esquema PostgreSQL al arrancar. El usuario de base necesita permisos para crear la tabla y el historial de migraciones.

`SnippetMetadataStore` permite reservar creaciones, consultar pendientes o confirmados, confirmar y actualizar metadatos. No expone endpoints nuevos ni guarda código o propietarios. Cada escritura confirma su propia transacción antes de devolver un resultado.

La vinculación con contenido está disponible desde SNI-8. `confirmCreation` es una operación interna: por sí sola no demuestra que existan contenido y ownership. `CreateSnippet` (SNI-17) cumple su precondición antes de invocarla.

## Storage de contenido — SNI-8

[docs/storage.md](docs/storage.md) documenta la interfaz `SnippetContentStorage`, su adaptador con archivos locales, las garantías y el vínculo con los metadatos. Es provisional, autorizado por la cátedra el 7 de octubre de 2026; la adaptación al contrato oficial se seguirá en SNI-19.

El contenido guardado nunca se sobrescribe. Reemplazar es guardar contenido nuevo, cambiar la referencia en una sola sentencia SQL y borrar el anterior. Lo usan la creación de SNI-17 y la actualización de SNI-18.

`STORAGE_LOCAL_DIRECTORY` define el directorio; por defecto es `build/snippet-content`, apto solo para trabajo local. En la imagen es `/var/lib/snippets/content`, declarado como volumen y propiedad del usuario `10001`. Si el servicio no puede escribir en el directorio, no arranca.

## Creación de snippets — SNI-9 y SNI-17

`POST /snippets` crea un snippet. El [contrato](docs/creation.md) documenta el pedido,
las respuestas, los errores, la idempotencia y las fallas parciales, con ejemplos que
los tests ejecutan. Es una propuesta backend sujeta a revisión con la UI.

La [verificación integrada de SNI-15](docs/creation-verification.md) explica cómo
comprobar la creación con los tres servicios reales, metadatos, contenido y owner,
los fallos parciales y la persistencia al recrear el contenedor.

```bash
curl -i -X POST http://localhost:8080/snippets \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7c9e6679-7425-40de-944b-e07fc1f90ae7' \
  -H 'X-Dev-Actor-Id: dev-juan' \
  -d '{"name":"Example","description":null,"language":"printscript","version":"1.0","code":"println(1);"}'
```

`CreateSnippet` ejecuta la secuencia: validar y reservar (`PrepareCreation`, SNI-9),
guardar y vincular el código (`CreationContent`), registrar el owner con el UUID
reservado (`CreationOwner`) y confirmar (`CreationConfirmation`). No hay una transacción
entre PostgreSQL, el storage y Permissions: cada paso se puede repetir y el reintento
con la misma `Idempotency-Key` completa lo que falte. Una reserva `PENDING` nunca se
responde como snippet creado. El owner es la identidad del pedido, nunca un dato del body.

Compose entrega `PRINTSCRIPT_BASE_URL` como fallback de Language; el override
`LANGUAGE_PRINTSCRIPT_BASE_URL` conserva prioridad. Permissions utiliza
`PERMISSIONS_BASE_URL` (default `http://localhost:8081`), con
`PERMISSIONS_CONNECT_TIMEOUT=2s` y `PERMISSIONS_READ_TIMEOUT=5s` por defecto.

Los resultados son datos tipados. `MetadataPersistenceException` se traduce a falla
técnica en el caso de uso, sin modificar el contrato de persistencia. La identidad
por header solo está disponible con el perfil Spring `dev`; no es autenticación.

## Actualización de snippets — SNI-10 y SNI-18

`PUT /snippets/{id}` reemplaza el código y los datos de un snippet. El
[contrato](docs/update.md) documenta el pedido, qué pasa con los campos ausentes o
nulos, las respuestas, los errores, las fallas parciales y la concurrencia, con
ejemplos que los tests ejecutan. Es una propuesta backend sujeta a revisión con la UI.

La [verificación integrada de SNI-16](docs/update-verification.md) documenta la
actualización con los servicios reales, los permisos, los rechazos sin cambios,
la concurrencia y la persistencia al recrear el contenedor.

```bash
curl -i -X PUT http://localhost:8080/snippets/<id> \
  -H 'Content-Type: application/json' \
  -H 'X-Dev-Actor-Id: dev-juan' \
  -d '{"name":"Renamed","description":null,"language":"printscript","version":"1.1","code":"println(2);"}'
```

`UpdateSnippet` ejecuta la secuencia. `PrepareUpdate` (SNI-10) distingue snippet
inexistente y pendiente, pregunta a Permissions si la identidad puede modificarlo y
valida el reemplazo completo, sin escribir nada. `SnippetReplacement` guarda el código
nuevo, cambia datos y referencia en una sola sentencia SQL y borra el código anterior.
El contenido vigente nunca se sobrescribe: el snippet muestra lo anterior completo o lo
nuevo completo. Un pedido rechazado no modifica nada, y una falla de Permissions es un
`502`, nunca un permiso denegado.

`findMetadata` permite consultar el estado interno sin cambiar `findConfirmed`.
El cliente de Permissions codifica `actorId` y distingue denegación, ownership
inexistente y fallas técnicas; nunca convierte un timeout en permiso denegado.

## Contrato con la UI — SNI-26

Contrato acordado para conectar la UI de la cátedra con US1–7. El listado
(SNI-30), el detalle (SNI-31) y la búsqueda de destinatarios (SNI-29) están
implementados, al igual que el catálogo de lenguajes (SNI-54) y sharing (SNI-32).

| Documento | Contenido |
| --- | --- |
| [docs/ui-integration.md](docs/ui-integration.md) | Representación común, traducción de los tipos de la UI, lint, capacidades, identidad y usuarios de dev, 403/404, operaciones conectadas y deshabilitadas |
| [docs/reading.md](docs/reading.md) | `GET /snippets`, `GET /snippets/{id}` y `GET /languages` |
| [docs/sharing.md](docs/sharing.md) | `GET /users` y `PUT /snippets/{id}/shares/{userId}` |
| [docs/tests.md](docs/tests.md) | `POST` y `GET /snippets/{id}/tests` (SNI-46) |

El listado obtiene de Permissions los IDs accesibles y después filtra, ordena y
pagina en la base de Snippets, sin leer código. El detalle lee un solo contenido,
después de comprobar el acceso. El lint se informa como `not-implemented`: US5 y
US6 no están completas hasta que existan lint y tests.

## Listado de snippets — SNI-30

`GET /snippets` lista los snippets que el actor puede leer, propios, compartidos o
ambos, con filtros por nombre y lenguaje, orden estable y paginación desde `0`.
El [contrato](docs/reading.md) documenta parámetros, respuestas, errores y
decisiones, con ejemplos que `ListingHttpTest` ejecuta.

```bash
curl -i 'http://localhost:8080/snippets?relation=all&name=hello&page=0&page_size=10' \
  -H 'X-Dev-Actor-Id: dev-juan'
```

`ListSnippets` resuelve la identidad, pide a Permissions (`GET /access`, SNI-28)
los IDs accesibles y busca solo esos en PostgreSQL. Si Permissions falla responde
`502` y si falla la base `503`: nunca una lista vacía. No lee código del storage.
No cierra US5: el filtro por lint sigue en SNI-37.

## Detalle de un snippet — SNI-31

`GET /snippets/{id}` devuelve un snippet que el actor puede leer, con su código y
sus capacidades: el owner puede modificar y compartir; un destinatario, solo leer.
El [contrato](docs/reading.md) documenta la secuencia, los errores, la lectura
durante una actualización y las decisiones, con ejemplos que `DetailHttpTest` ejecuta.

```bash
curl -i 'http://localhost:8080/snippets/<id>' -H 'X-Dev-Actor-Id: dev-juan'
```

`ReadSnippet` comprueba la existencia, pregunta a Permissions la relación y recién
entonces lee el código, así un actor sin acceso nunca llega al storage. Metadatos y
referencia salen de una misma fila; si una actualización borró el código leído, se
relee una vez y se responde la versión nueva completa, o `503` reintentable. No
cierra US6: las infracciones de lint y los tests siguen en SNI-37.

## Destinatarios de desarrollo — SNI-29

`GET /users` busca destinatarios para compartir por parte del nombre, sin
distinguir mayúsculas, sin incluir a quien pregunta y con paginación desde `0`.
El [contrato](docs/sharing.md) documenta parámetros, errores, el directorio y sus
decisiones, con ejemplos que `UsersHttpTest` ejecuta.

```bash
curl -i 'http://localhost:8080/users?name=an&page=0&page_size=5' \
  -H 'X-Dev-Actor-Id: dev-juan'
```

Todavía no hay usuarios reales. Los destinatarios salen de
`src/main/resources/application-dev.properties`, que Spring carga solo con el perfil
`dev`: `dev-juan`, `dev-juanfran`, `dev-thiago`, `dev-ana` y `dev-bruno`. Son los
mismos `id` que se usan en `X-Dev-Actor-Id`. Sin ese perfil el catálogo queda vacío
y la identidad de desarrollo está deshabilitada. Auth0 reemplazará la fuente
implementando `UserDirectory`, sin cambiar el contrato.

## Tests de un snippet — SNI-46

`POST /snippets/{id}/tests` guarda un test (`inputs` y `expectedOutputs`) y
`GET /snippets/{id}/tests` lista los del snippet en orden de creación. Solo el owner
crea; owner y usuarios compartidos listan; cualquier otro, o un snippet pendiente,
recibe `404`. Crear un test **no lo ejecuta** ni llama a PrintScript (eso es US9).
El [contrato](docs/tests.md) documenta body, límites, secuencia, errores y
decisiones, con ejemplos que `TestCasesHttpTest` ejecuta.

```bash
curl -i -X POST 'http://localhost:8080/snippets/<id>/tests' \
  -H 'X-Dev-Actor-Id: dev-juan' -H 'Content-Type: application/json' \
  -d '{"inputs":["Ana"],"expectedOutputs":["Hola, Ana"]}'
```

```bash
curl -i 'http://localhost:8080/snippets/<id>/tests' -H 'X-Dev-Actor-Id: dev-juan'
```

Los tests viven en PostgreSQL (`snippet_test_cases`, migración V3) como arrays JSONB:
se conservan exactos el orden, los duplicados, los strings vacíos y el Unicode, y
sobreviven a un reinicio. El acceso lo decide `ReadGate`, el mismo chequeo que usa
el detalle. Un compartido que intenta crear recibe `403 access-denied`; superar un
límite (100 items, 8 KiB por string, 50 tests por snippet) responde
`413 payload-too-large`. Si Permissions o la base fallan responde `502`/`503`,
nunca un test creado ni `{"items":[]}`.

## CI/CD y branches — SNI-23 / SNI-25

Crear ramas cortas desde `dev`, abrir PR a dev y promover mediante PR dev → main.
Dev es la default branch; usar squash para cambios individuales y merge commit
para promociones. Main debe exigir PR y CI con base actualizada, aplicar las reglas
a administradores y no admitir bypass de pushes directos. El merge de esa PR es
la autorización humana; no se agrega otro approval en GitHub Environments.

El caller `.github/workflows/pipeline.yml` prepara
`kotlin-service-pipeline.yml@v0.5.1` de [github-workflows](https://github.com/JJT-INGSIS/github-workflows).
Esta versión espera la convergencia de un rollback al digest esperado antes de
declarar la recuperación; una candidata fallida mantiene la Action en rojo.
**Esta versión debe integrarse, publicarse y comprobarse antes de integrar el caller.**
El candidato se prueba por SHA siguiendo la
[guía central](https://github.com/JJT-INGSIS/github-workflows/blob/main/docs/sni-25-integration.md).

| Evento | Resultado |
| --- | --- |
| PR dev/main | `build` completo y agregador requerido; sin publicación ni despliegue |
| Push dev | CI → publicar imagen; con CD habilitado, desplegar y verificar dev |
| Push main | CI → recuperar digest verificado del mismo Git tree → prod, sin reconstruir |
| Manual dev | CI; publicación opcional o incluida por CD habilitado |
| Manual main | CI y reintento de promoción; activar `publish` falla |

CI fallido o cancelado bloquea CD. `Resultado requerido` solo comprueba el estado
de CI y no repite Gradle. Conservar el check `verify / verify / build` durante la
transición; exigir el agregador nuevo cuando aparezca y pase en GitHub.

Imagen pública: `ghcr.io/jjt-ingsis/snippets-service`. Usar siempre `imagen@sha256:...`;
los tags `sha-<SHA completo>` y `run-<run_id>-<run_attempt>` sirven para localizarla.
El resumen y las etiquetas registran origen, Git tree, digest e intento de build.
Prod exige coincidencia exacta de todo el repositorio, incluso README/workflows;
sin evidencia confiable falla. Dev puede haber avanzado al promover.

Secrets `GH_PACKAGES_USER` y `GH_PACKAGES_READ_TOKEN`: lectura de dependencias
Gradle y secretos BuildKit. GHCR usa `GITHUB_TOKEN` automático con `packages: write`;
CD agrega lectura de Actions/PR y `deployments: write`, sin otro PAT para las VMs.
`DOCKER_PLATFORMS` tiene default linux/amd64, compatible con las VMs x64.

En **este repo**, environments dev (solo dev) y prod (solo main): variables
`SSH_HOST`, `SSH_USER`, `SSH_PORT`; secrets `SSH_PRIVATE_KEY`, `SSH_KNOWN_HOSTS`.
Los resuelve el job final del workflow reutilizable. No son las contraseñas de
runtime ni activan el perfil Spring. Configurar una clave dedicada y host keys
verificadas con Thiago.

Habilitación gradual con variables **de repositorio**:

- Sin `CD_ENABLED=true`, continúa CI/publicación sin despliegue remoto.
- Habilitar dev después del bootstrap, credenciales y revisión fija de infra.
- Configurar `PROD_DEPLOY_ENABLED=true` solo después de verificar las reglas
  completas de main y prod. El 13/10/2026 se activó y se verificó `Do not allow
  bypassing the above settings` en main: PR, CI y base actualizada se exigen también
  a administradores. El despliegue automático a producción sigue deshabilitado.

El despliegue cambia solo esta aplicación y conserva bases y otras imágenes.
Una pérdida SSH se reconcilia con el journal de la VM; rollback mantiene la Action
roja y no revierte migraciones ni datos. Conservar contratos y esquemas compatibles
con la versión anterior. Una VM apagada produce fallo, sin encendido automático.

Ver [operación y recuperación SNI-25](https://github.com/JJT-INGSIS/snippet-searcher-infra/blob/dev/docs/sni-25-cd.md).
La integración por tag, resolución de environment secrets y el recorrido real
dev → prod quedan pendientes de la configuración y pruebas con VMs encendidas.

## Acceso desde navegador y CORS

La UI utiliza `/api` en el mismo origen mediante Vite o NGINX. Para autorizar
acceso desde otros orígenes, configurar `SNIPPETS_CORS_ALLOWED_ORIGINS` en el backend.
El valor por defecto es vacío; Spring administra la política y NGINX no debe duplicarla.
Ver [configuración, pruebas y límites de autenticación de CORS](docs/cors.md).
SNI-52 amplía esa política exactamente a las rutas/métodos públicos de tests y
ejecución de SNI-45. La aceptación local del transporte NGINX/NDJSON usa un upstream
controlado; la ejecución funcional con el intérprete se verifica posteriormente.

## SNI-43 — actualización de herramientas de CD

El caller preparado usa `github-workflows@v0.6.0`. Integrar únicamente después de
publicar y verificar esa versión central con el SHA definitivo de infra. La versión
nueva admite el stack anterior; actualizar los tres callers del ambiente antes de
incorporar NGINX/Asset/UI. No reejecutar versiones antiguas para operar el stack ampliado.
El procedimiento y la protección del registro están en
[rollout SNI-43](https://github.com/JJT-INGSIS/snippet-searcher-infra/blob/dev/docs/sni-43-cd.md).
