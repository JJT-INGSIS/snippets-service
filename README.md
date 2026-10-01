# Snippets Service

Servicio HTTP de Snippet Searcher. Este repositorio contiene por ahora solo el arranque con Kotlin y Spring Boot; los contratos y las integraciones se definirán durante el TP.

## Requisitos

- JDK 21 para ejecutar Gradle y la aplicación.
- No hace falta instalar Gradle: el repositorio incluye su wrapper.

La configuración compartida vive en `gradle-conventions`, versión `0.2.0`. El wrapper usa Gradle 9.3.0.

## Verificación

```powershell
.\gradlew.bat check
```

`check` ejecuta tests, ktlint y detekt, y genera el reporte JaCoCo. Gradle resuelve la convención publicada en GitHub Packages mediante `GITHUB_ACTOR` y `GITHUB_TOKEN`.

El test de arranque usa H2 en memoria, disponible solo en tests, y comprueba que Spring crea una conexión válida. No requiere PostgreSQL ni Docker y no verifica compatibilidad con PostgreSQL; las pruebas de persistencia contra PostgreSQL se incorporarán cuando exista esa implementación.

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

Con `JAVA_HOME` apuntando a un JDK 21 y las credenciales de GitHub Packages configuradas, preparar `.env` como se indica en la sección de Docker y levantar solo PostgreSQL:

```powershell
docker compose up -d --wait snippets-db

$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/snippets"
$env:SPRING_DATASOURCE_USERNAME = "snippets"
$databasePassword = Read-Host "Contraseña definida en SNIPPETS_DB_PASSWORD de .env" -AsSecureString
$env:SPRING_DATASOURCE_PASSWORD = [System.Net.NetworkCredential]::new("", $databasePassword).Password
Remove-Variable databasePassword

.\gradlew.bat bootRun
```

El servidor arranca en el puerto 8080 predeterminado de Spring Boot. Todavía no expone endpoints propios.

## Arranque con Docker

El `Dockerfile`, `compose.yaml` y `.dockerignore` están en la raíz de este repositorio.
Este Compose es para desarrollar y ejecutar snippets de forma independiente. Usa el nombre de proyecto `snippets-service` y levanta dos contenedores:

- `snippets-service`: construye la imagen de la aplicación desde el `Dockerfile`.
- `snippets-db`: usa la imagen oficial `postgres:18.6-bookworm` y conserva sus datos en el volumen `snippets_data`, montado en `/var/lib/postgresql`.

La infraestructura completa se definirá en un repositorio separado, con su propio Compose. El Dockerfile de esta aplicación seguirá en este repositorio y podrá usarse desde ambos entornos.

La conexión JDBC se configura mediante `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD`. Dentro del contenedor, la URL usa `snippets-db:5432`; con `bootRun` en la máquina, usa `localhost:5432`. `depends_on` espera a que PostgreSQL esté listo antes de iniciar la aplicación. Todavía no hay tablas, migraciones ni operaciones de persistencia del dominio.

### Preparación

Hace falta Docker Desktop iniciado, con contenedores Linux, Buildx y Docker Compose 5.3.1 como versión de referencia. El soporte de esta versión para leer secretos de build desde `.env` se revisó en su implementación. El build corre dentro de Docker con JDK 21, por lo que no requiere Java ni Gradle instalados en la máquina. Cada integrante puede consultar su versión con `docker compose version`.

Desde la raíz de `snippets-service`, crear la configuración local una sola vez:

```powershell
Copy-Item .env.example .env
```

Completar una sola vez las tres variables de `.env`:

- `SNIPPETS_DB_PASSWORD`: contraseña local de PostgreSQL.
- `GITHUB_ACTOR`: usuario de GitHub dueño del token.
- `GITHUB_TOKEN`: PAT con `read:packages` y acceso a `gradle-conventions`.

`.env` está excluido de Git y del contexto del build. Contiene credenciales locales en texto plano: cada integrante prepara su propio archivo y no lo comparte. Los secrets de GitHub Actions no se descargan a la computadora.

Compose lee `.env` automáticamente y entrega las credenciales de GitHub como secretos de BuildKit, disponibles únicamente en el paso de Gradle. No se definen como `ARG` ni `ENV` de la imagen ni se pasan al contenedor de la aplicación. No hace falta cargar `GITHUB_ACTOR` ni `GITHUB_TOKEN` en PowerShell para usar Docker. Si ya están definidas en la sesión, sus valores tienen prioridad sobre `.env`.

Solo hay que actualizar el token en `.env` si vence o se revoca. Para ejecutar Gradle directamente en la máquina (`check`, `bootRun` o los hooks), se mantienen las credenciales locales de Gradle existentes: Gradle no lee `.env` automáticamente.

### Construir e iniciar

```powershell
docker compose config --quiet
docker compose up -d
docker compose ps
docker compose logs -f snippets-service
```

`up -d` construye la imagen si todavía no existe y luego inicia los contenedores. Si cambió el código o el Dockerfile, usar `docker compose up --build -d` para reconstruirla. En ambos casos, los secretos se leen desde `.env` y no necesitan cargarse en la sesión de PowerShell.

El `Dockerfile` genera el `bootJar` con el wrapper y luego copia ese JAR a una imagen con JRE 21. BuildKit conserva una caché de Gradle entre construcciones para reutilizar dependencias descargadas; esa caché no se copia a la imagen final. La aplicación se ejecuta con un usuario sin privilegios de root. Las verificaciones de calidad y tests siguen ejecutándose con `check` y en CI; el build de Docker solo empaqueta la aplicación.

El servidor queda disponible en `http://localhost:8080`. Una respuesta HTTP 404 en `/` es esperable porque todavía no hay endpoints propios. `Ctrl+C` deja de seguir los logs y mantiene los contenedores en ejecución.

PostgreSQL queda accesible dentro de la red de Compose con el host `snippets-db` y el puerto `5432`. Desde IntelliJ o DBeaver, usar:

- Host: `localhost`.
- Puerto: `5432`.
- Base: `snippets`.
- Usuario: `snippets`.
- Contraseña: el valor de `SNIPPETS_DB_PASSWORD` en `.env`.

El puerto se publica únicamente en la máquina local. Si se levanta también otro Compose, sus puertos publicados no deben coincidir con `8080` ni `5432`. Por ejemplo, la base de permisos puede usar el puerto local `5433` aunque dentro de su contenedor siga usando `5432`.

Para abrir una sesión SQL desde el contenedor:

```powershell
docker compose exec snippets-db psql -U snippets -d snippets
```

### Detener y conservar los datos

```powershell
docker compose down
```

El volumen se conserva para el próximo arranque. Para borrar la base local y empezar desde cero, el siguiente comando **elimina todos sus datos**:

```powershell
docker compose down --volumes
```

Las variables `POSTGRES_*` inicializan una base nueva. Cambiar la contraseña en `.env` no modifica la contraseña de una base ya existente: hay que cambiarla mediante SQL o recrear el volumen si sus datos son descartables.

Los servicios de permisos y PrintScript se incorporarán al Compose del repositorio de infraestructura. Este Compose seguirá levantando únicamente snippets y su base.

## Responsabilidad prevista

Este servicio administrará los metadatos y el ciclo de vida de los snippets, los listados y los tests, y coordinará operaciones con los servicios de permisos y de lenguaje. La persistencia, el almacenamiento del código y los contratos HTTP aún no están definidos.
