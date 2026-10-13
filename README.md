# Snippets Service

Servicio HTTP de Snippet Searcher con Kotlin y Spring Boot. Expone la creación de snippets en `POST /snippets` y su actualización en `PUT /snippets/{id}`: valida el código mediante HTTP, guarda los metadatos en PostgreSQL y el código en un storage local provisional, y usa Permissions para registrar al owner y comprobar que solo él modifique.

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

## CI/CD y branches — SNI-23 / SNI-25

Crear ramas cortas desde `dev`, abrir PR a dev y promover mediante PR dev → main.
Dev es la default branch; usar squash para cambios individuales y merge commit
para promociones. Main debe exigir PR y CI con base actualizada, aplicar las reglas
a administradores y no admitir bypass de pushes directos. El merge de esa PR es
la autorización humana; no se agrega otro approval en GitHub Environments.

El caller `.github/workflows/pipeline.yml` prepara
`kotlin-service-pipeline.yml@v0.4.0` de [github-workflows](https://github.com/JJT-INGSIS/github-workflows).
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
