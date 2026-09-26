# Snippets Service

Servicio HTTP de Snippet Searcher. Este repositorio contiene por ahora solo el arranque con Kotlin y Spring Boot; los contratos y las integraciones se definirán durante el TP.

## Requisitos

- JDK 21 para ejecutar Gradle y la aplicación.
- No hace falta instalar Gradle: el repositorio incluye su wrapper.

La configuración compartida vive en `gradle-conventions`, versión `0.1.0`. El wrapper usa Gradle 9.3.0.

## Verificación

```powershell
.\gradlew.bat check
```

`check` ejecuta tests, ktlint y detekt, y genera el reporte JaCoCo. Para usar la convención publicada localmente durante el desarrollo, ejecutar `gradle-conventions\gradlew.bat publishToMavenLocal` desde ese repositorio y luego agregar `-PuseLocalConventions=true` al comando del servicio. Sin esa propiedad, Gradle resuelve la versión publicada en GitHub Packages mediante `GITHUB_ACTOR` y `GITHUB_TOKEN`.

## Arranque local

Con `JAVA_HOME` apuntando a un JDK 21:

```powershell
.\gradlew.bat bootRun
```

El servidor arranca en el puerto 8080 predeterminado de Spring Boot. Todavía no expone endpoints propios.

## Responsabilidad prevista

Este servicio administrará los metadatos y el ciclo de vida de los snippets, los listados y los tests, y coordinará operaciones con los servicios de permisos y de lenguaje. La persistencia, el almacenamiento del código y los contratos HTTP aún no están definidos.
