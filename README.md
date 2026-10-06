# Snippets Service

Servicio HTTP de Snippet Searcher con Kotlin y Spring Boot. Incluye validación de lenguaje mediante HTTP y persistencia interna de metadatos; el flujo público de creación y el storage siguen pendientes.

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

$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/snippets"
$env:SPRING_DATASOURCE_USERNAME = "snippets"
$databasePassword = Read-Host "SNIPPETS_DB_PASSWORD del .env de infra" -AsSecureString
$env:SPRING_DATASOURCE_PASSWORD = [System.Net.NetworkCredential]::new("", $databasePassword).Password
Remove-Variable databasePassword

.\gradlew.bat bootRun
```

El servidor arranca en el puerto 8080 predeterminado de Spring Boot. Todavía no expone endpoints propios.

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

El servidor queda disponible en `http://localhost:8080`. Un HTTP 404 en `/` es esperable porque todavía no hay endpoints propios y no comprueba la conexión JDBC a PostgreSQL.

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

La vinculación con contenido espera el contrato real de storage. `confirmCreation` es una operación interna cuya precondición debe cumplir SNI-9; por sí sola no demuestra que existan contenido y ownership. La generación de la huella del pedido y la coordinación de reintentos externos también corresponden a SNI-9.
