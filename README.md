# Snippets Service

API de Snippet Searcher. Gestiona el ciclo de vida de los snippets, coordina el acceso y expone a la UI las operaciones de formato, lint, ejecución y pruebas.

## Arquitectura

- **PostgreSQL** almacena metadatos, reglas, casos de prueba y estado de los trabajos. Flyway aplica las migraciones. El código fuente se guarda mediante el adaptador local o Asset Service, según `STORAGE_PROVIDER`. [Almacenamiento de contenido](docs/storage.md)
- **Permissions Service** registra la propiedad y las relaciones de acceso compartido. **PrintScript Service** valida, formatea, analiza y ejecuta código. Snippets consume ambos por HTTP.
- **Auth0** autentica las solicitudes en modo `AUTH_MODE=auth0`. Snippets valida issuer y audience, y usa el `sub` como identidad del actor. El directorio para compartir usa una credencial de backend separada. El modo `dev` admite identidad por header únicamente con el perfil Spring `dev`. [Autenticación y configuración](docs/authentication.md)
- **Redis Streams** transporta eventos de un outbox transaccional. El consumidor procesa cambios de formato, lint, pruebas automáticas y revalidación del parser. El estado de los trabajos queda en PostgreSQL y su progreso se consulta en `GET /jobs/{jobId}`. [Outbox](docs/background-outbox.md) · [Consumidor y recuperación](docs/background-consumer.md) · [Revalidación del parser](docs/parser-revalidation.md)

## Contratos HTTP

| Funcionalidad | Rutas principales | Contratos |
| --- | --- | --- |
| Crear, actualizar, listar y consultar snippets | `POST /snippets`, `PUT /snippets/{id}`, `GET /snippets`, `GET /snippets/{id}`, `GET /languages` | [Creación](docs/creation.md) · [Actualización](docs/update.md) · [Lectura](docs/reading.md) |
| Buscar destinatarios y compartir | `GET /users`, `PUT /snippets/{id}/shares/{userId}` | [Acceso compartido](docs/sharing.md) |
| Configurar formato y lint, y descargar el resultado formateado | `GET` y `PUT /formatting-rules/{language}/{version}`, `GET` y `PUT /linting-rules/{language}/{version}`, `GET /snippets/{id}/formatted` | [Formato](docs/formatting-rules.md) · [Lint](docs/linting-rules.md) · [Descarga](docs/formatted-download.md) |
| Administrar casos de prueba y consultar resultados automáticos | `GET` y `POST /snippets/{id}/tests`, `GET /snippets/{id}/automatic-tests` | [Casos de prueba](docs/tests.md) · [Resultados automáticos](docs/automatic-tests.md) |
| Ejecutar código y pruebas, enviar entradas, leer eventos NDJSON y cancelar | `POST /snippets/{id}/executions`, `POST /snippets/{id}/tests/{testId}/executions`, `GET /executions/{executionId}/events` | [Ejecución interactiva](docs/interactive-execution.md) |

Integración del navegador, prefijo `/api` y transporte NDJSON. [Contrato con la UI](docs/ui-integration.md)

## Desarrollo local

Para ejecutar el stack, prepará el `.env` en el repo de infraestructura según su README. Desde ese repo, `docker compose up --build -d` levanta el entorno. La API local escucha en `http://localhost:8080`. `GET /languages` permite comprobar que responde. [Configuración del entorno local](https://github.com/JJT-INGSIS/snippet-searcher-infra#readme)

Para verificar el repo, usá JDK 21 y el wrapper Gradle 9.3.0. El plugin `jjt.spring-service:0.3.1` se descarga de GitHub Packages mediante `GITHUB_ACTOR` y `GITHUB_TOKEN` con acceso de lectura.

```powershell
.\gradlew.bat check
```

En Linux y macOS usá `./gradlew check`. `check` ejecuta los tests, ktlint y Detekt, y genera el reporte JaCoCo. Los tests de integración levantan PostgreSQL y Redis con Testcontainers, por lo que necesitan Docker. Los ejemplos ejecutables de los contratos se mantienen en los ocho documentos declarados como entradas de `test` en [Gradle](build.gradle.kts).

## Empaquetado y despliegue

El [Dockerfile](Dockerfile) construye `build/libs/app.jar` con Temurin JDK 21, caché de Gradle y secretos de BuildKit para GitHub Packages. La imagen final usa Temurin JRE 21, corre como `10001:10001` y conserva el directorio de almacenamiento local `/var/lib/snippets/content`. El script de arranque carga el agente de New Relic cuando se habilita en el ambiente.

El [pipeline](.github/workflows/pipeline.yml) usa el workflow reutilizable `v0.6.8`. Las PRs a `dev` y `main` corren CI. Un push a `dev` publica la imagen después de CI y despliega DEV si `CD_ENABLED=true`. Las ejecuciones manuales en `dev` también pueden pedir publicación con `publish`. En `main`, el despliegue requiere además `PROD_DEPLOY_ENABLED=true` y promueve por digest la imagen verificada en DEV para el mismo árbol Git. [Infraestructura y manual de operación](https://github.com/JJT-INGSIS/snippet-searcher-infra#readme)
