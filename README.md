# Snippets Service

Servicio HTTP de Snippet Searcher. Este repositorio contiene por ahora solo el arranque con Kotlin y Spring Boot; los contratos y las integraciones se definirán durante el TP.

## Requisitos

- JDK 21 para ejecutar Gradle y la aplicación.
- No hace falta instalar Gradle: el repositorio incluye su wrapper.

La configuración inicial usa Spring Boot 4.1.1, Kotlin 2.4.10 y Gradle 9.3.0.

## Arranque local

Con `JAVA_HOME` apuntando a un JDK 21:

```powershell
.\gradlew.bat bootRun
```

El servidor arranca en el puerto 8080 predeterminado de Spring Boot. Todavía no expone endpoints propios.

## Responsabilidad prevista

Este servicio administrará los metadatos y el ciclo de vida de los snippets, los listados y los tests, y coordinará operaciones con los servicios de permisos y de lenguaje. La persistencia, el almacenamiento del código y los contratos HTTP aún no están definidos.
