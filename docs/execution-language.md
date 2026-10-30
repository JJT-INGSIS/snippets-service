# SNI-47 — Capacidad de ejecución de Language

Implementa el cliente del [contrato interno de SNI-45](https://github.com/JJT-INGSIS/snippet-searcher-infra/blob/dev/docs/architecture/execution-contract.md).
Esta capacidad por sí sola no habilita endpoints públicos. SNI-48 agrega la
[coordinación de tests y auditoría](test-execution.md); [SNI-49](interactive-execution.md) completa el modo interactivo. Ambos coordinan
lectura, autorización, snapshot, cupo por actor y asociación de IDs públicos/internos.
SNI-50 crea/lista definiciones; este transporte no las persiste ni compara outputs.

## Dónde está cada responsabilidad

- Language descubre un LanguageExecutor por identificador de lenguaje y reporta
  explícitamente capacidad no soportada. `validate` y `/languages` no cambian.
- `language/execution` define requests, descriptores, eventos y resultados tipados,
  sin Spring HTTP, Jackson ni conocimiento del intérprete.
- `language/printscript/execution` adapta inicio, consulta, eventos, input y cancelación.
  El ID del descriptor es **interno**, no una credencial ni un ID público autorizado.
  Snippets debe verificar actor y acceso en cada operación futura de SNI-48/SNI-49.

PrintScript posee el intérprete; no duplicamos lógica ni lanzamos procesos por snippet.
Un mismo transporte sirve para TEST e INTERACTIVE; conserva todas las posiciones y
diagnósticos recibidos y no decide PASSED/FAILED.

## Decisiones y límites

Decisiones de canal, formato, límites y no reintentar un inicio incierto: aprobadas
en SNI-45. Thiago aprobó la propuesta de implementación de SNI-47 el 26/10/2026
con «dale hacelo apruebo todo». Los detalles siguientes aplican esas garantías:

- **Capacidad separada de validación:** se puede validar un lenguaje sin disponer
  de ejecutor. Se descartó meter ejecución dentro del validador o del controller:
  mezcla responsabilidades y obliga a implementar capacidades inexistentes.
- **JDK HTTP + parsing en el borde:** no se agrega una dependencia ni WebClient al
  dominio. El cliente valida status, media type, UUID asociado, secuencia, payloads
  y diagnósticos antes de entregarlos. Cuesta código de adaptación, pero evita
  confiar solo en tipos Kotlin frente a JSON remoto.
- **NDJSON realmente incremental:** se ensambla por bytes hasta LF; un chunk no
  equivale a un evento y un carácter UTF-8 puede estar fragmentado. Líneas vacías,
  truncadas, IDs distintos, saltos de secuencia y eventos tras terminal son fallas.
  El lector acota cada línea a 320 KiB y JSON de control a 64 KiB. Si se aumentan
  los límites de eventos en PrintScript, revisar estos límites de transporte.
- **Sin reintentos automáticos:** perder la respuesta de un POST produce Failed
  con uncertain=true; no implica que no se ejecutó. Reenviar input solo es seguro
  con el mismo requestId y valor según el protocolo. Una desconexión no es éxito:
  se conservan eventos ya entregados y se devuelve interrupción/incertidumbre.
- **Timeout de cuerpo explícito:** JDK HttpClient no limita la lectura completa
  de un InputStream con el timeout de headers. Un timer cierra el cuerpo y se
  retira al terminar, también ante falla. Se descartó confiar solo en el timeout
  inicial: podría dejar un lector bloqueado. No es cancelación del intérprete;
  el coordinador debe consultar/cancelar según el contrato.
- **Sin inferir reinicios:** un 404 significa sesión remota ausente; sin evidencia
  no distinguimos expiración de reinicio. El futuro coordinador conoce si seguía
  activa y debe traducirlo a INTERRUPTED o falla técnica/estado desconocido.

## Configuración

Se reutiliza `language.printscript.base-url`: override existente
LANGUAGE_PRINTSCRIPT_BASE_URL; fallback PRINTSCRIPT_BASE_URL; default local
`http://localhost:8082`. Conexión 2 s y control 5 s desde PrintScriptProperties.
`language.printscript.execution.stream-timeout` / LANGUAGE_PRINTSCRIPT_EXECUTION_STREAM_TIMEOUT
tiene default 135 s y acota la lectura completa de cada stream. Revisarlo junto
al timeout total efectivo del intérprete. No cambiar el timeout de validación
para que una ejecución interactiva pueda durar más.

## Verificación

ExecutionClientTest usa HTTP controlado: framing fragmentado, Unicode, errores,
ack incompatible, EOF sin terminal, timeout de cuerpo y POST perdido sin repetición.
La prueba real exige un PrintScript de la rama SNI-47 levantado, no un fixture:

```bash
# En printscript-service, con JDK 21 y credenciales de Packages configuradas:
./gradlew build
java -jar build/libs/app.jar --server.address=127.0.0.1 --server.port=18147

# En snippets-service; Docker requerido por sus pruebas PostgreSQL/Asset existentes:
PRINTSCRIPT_EXECUTION_URL=http://127.0.0.1:18147 ./gradlew check build
```

PrintScriptExecutionLiveTest comprueba descubrimiento mediante Language, output
antes de input, entrega de Unicode, estado final, TEST 1.0, input agotado, error de
programa y cancelación con el protocolo HTTP real. Sin esa variable la prueba live
se omite expresamente; un build sin proveedor no acredita la integración.
No se desplegó ni se cambió el estado de datos de dev/prod.

### Evidencia local — 26/10/2026

`check build` pasó: 358 casos, 349 aprobados y 9 pruebas live preexistentes omitidas
por sus condiciones de proveedor. Los 10 casos nuevos de SNI-47 sí se ejecutaron:
8 con HTTP controlado y 2 contra el proceso PrintScript real. Las pruebas existentes
de PostgreSQL/Asset conservaron sus fixtures y no tocaron volúmenes de dev/prod.
Rama local `feat/sni-47-language-execution`, lista para commit/PR, sin despliegue.

## Apertura separada del consumo — SNI-48

Thiago aprobó el 28/10/2026 `openEvents` para validar status/headers antes de
comprometer el 200 NDJSON público. Devuelve un canal cerrable con deadline;
`events` se conserva y delega en apertura/lectura/cierre. Esto evita una tarea
concurrente adicional para negociar el primer evento y conserva errores de cursor
antes de abrir la respuesta al navegador. No cambia las rutas ni eventos de PrintScript.
