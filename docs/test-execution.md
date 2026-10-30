# SNI-48 — ejecución de un test y auditoría persistente

## Alcance aprobado

Thiago confirmó el 28 de octubre de 2026 conservar código, definición del test,
inputs, outputs, actor, fechas, lenguaje/versión, hashes y resultado/error. Se
persisten inicio, asociación con el intérprete y resultado final; **no cada evento**.
Esto conserva el historial sin agregar una escritura de PostgreSQL a cada output.
Un reinicio puede perder los outputs que sólo estaban en memoria.

El historial es **auditoría interna** por ahora: no hay rutas públicas de listado o
detalle histórico. Las rutas de sesión conservan la retención técnica de 60 s y
sólo permiten operar al actor que ejecutó, mientras tenga acceso vigente al snippet.
El propietario no obtiene control de las ejecuciones de otros lectores.

Se registra la versión de lenguaje elegida; el contrato actual no informa la
revisión binaria del servicio/intérprete, por lo que no se inventa ese dato.
Tener un hash no permite reconstruir contenido borrado: la auditoría conserva
copias explícitas, sin restaurarlas como contenido actual del snippet.

## API pública implementada

El navegador usa el prefijo `/api`; Snippets recibe las siguientes rutas:

| Operación | Respuesta |
| --- | --- |
| `POST /snippets/{snippetId}/tests/{testId}/executions` con `{}` | 201 con descriptor público |
| `GET /executions/{executionId}` | Descriptor de sesión; no es consulta de historial |
| `GET /executions/{executionId}/events?after=0` | `application/x-ndjson`, sin buffering/caché |
| `POST /executions/{executionId}/cancel` con `{}` | 202 con estado; CANCELLING no acredita cancelación efectiva |

Se rechazan campos extra, incluido código/actor/owner. Identidad dev explícita y
acceso mediante los componentes existentes; prod no acepta identidad arbitraria.
Cada operación verifica actor de sesión y acceso actual. El test debe pertenecer
al snippet autorizado antes de leer storage o llamar a Language. El descriptor y
los eventos ocultan el ID interno, código, referencias privadas y actor.

No implementa inicio ni inputs de sesiones INTERACTIVE: corresponden a SNI-49.
El índice de reserva por actor debe compartirse con ese modo; no agregar otro
contador independiente. Su modelo de auditoría deberá admitir la ausencia de test
antes de implementar ese modo. No se modificaron infraestructura ni CD.

## Recorrido y garantías

1. `ReadExecutionSnapshot` autoriza y selecciona el test. `ContentReading.readStable`
   lee el blob y verifica nuevamente referencia/lenguaje/versión. Ante cambios
   reintenta hasta tres veces; luego devuelve snapshot-conflict. Un fallo técnico
   no se presenta como una edición concurrente. No mantiene una transacción
   mientras consulta storage o ejecuta código.
2. `StartTestRun` reserva y guarda el inicio/snapshot en PostgreSQL antes de llamar
   al intérprete. Un índice único parcial impide dos reservas del mismo actor,
   incluyendo arranque, espera de resultado y cancelación. PrintScript conserva
   el límite global de intérpretes del contrato. Continúa la restricción de una
   instancia de cada coordinador, también durante actualizaciones.
3. Language abre y valida HTTP antes de entregar el canal. Este ajuste interno,
   aprobado por Thiago el 28/10, conserva los errores 400/409/503 antes del 200
   NDJSON. La alternativa era coordinar una tarea extra esperando el primer
   evento/error. `events` sigue como operación de conveniencia sobre apertura,
   consumo y cierre. La conexión abierta tiene deadline incluso si no se consume.
4. `RunChannel` transmite y hace flush de cada evento; conserva outputs en memoria.
   `TestRunProgress` devuelve estados nuevos y errores tipados. La comparación es
   igualdad exacta de listas, con orden, duplicados, vacíos, espacios y Unicode.
5. Sólo COMPLETED genera PASSED/FAILED. Los otros outcomes conservan su categoría,
   diagnósticos e inputs sobrantes. El resultado se guarda antes de emitir el
   terminal público. Si falla esa escritura no se comunica éxito confirmado.
6. La reserva se libera ante rechazo conocido o término comprobado. Un 202 o una
   falla de red no prueban que se haya detenido el intérprete. Un segundo consumidor
   recibe 409. Los cursores futuros son 400 y el historial perdido es 409.

## Recuperación aprobada y límites

Se guarda el ID de PrintScript apenas responde el inicio, antes del 201 público.
Una nueva instancia de Snippets recupera la asociación desde la base y reconstruye
los outputs desde eventos retenidos si se perdió su memoria. Sólo entrega al
navegador los posteriores a su cursor, sin volver a iniciar el programa.

`RecoverTestRuns` revisa reservas sin consumidor cada 5 s (configurable por
`execution.recovery.delay-ms`). Recupera resultados terminales aunque se haya
cerrado el navegador. No abre un consumidor para prolongar un programa abandonado:
PrintScript mantiene su política de desconexión. Una caída de comunicación conserva
la reserva y el motivo pendiente; vuelve a intentar sin repetir escrituras del
mismo fallo en cada ciclo. Un resultado durable conserva su descriptor/terminal
público durante la retención técnica aunque PrintScript se reinicie. No se
reconstruyen secuencias intermedias que ya no existen.

Si los eventos necesarios se perdieron o la sesión expiró, la auditoría conserva
el snapshot y un motivo de resultado desconocido, sin fabricar outputs ni
comparación. La alternativa de recuperación garantizada requería persistencia
adicional e inicio idempotente en PrintScript; Thiago eligió usar el contrato actual.

Si se pierde el acknowledgment de inicio antes de guardar el ID, no existe una
correlación segura para localizar ese trabajo. La reserva queda conservada con
resultado desconocido. **No se libera por un timeout local.** Requiere intervención:
comprobar externamente que ese intérprete ya no puede seguir ejecutando (por ejemplo,
tras reemplazar y verificar el proceso de PrintScript) y liberar esa reserva
específica en la auditoría. No hay endpoint para reiniciar o liberar a ciegas.
Esta es una limitación deliberada del contrato sin inicio idempotente.

`remote.state` en la auditoría es el estado observado al asociar la sesión, no un
indicador actual de trabajo corriendo. `result IS NULL` significa que no hay
resultado final confirmado. `failure` conserva el motivo conocido; `reserved`
representa la reserva de capacidad, no una afirmación de actividad del worker.
La historia no se borra al expirar el canal. Su crecimiento y las copias de código
histórico son el costo aceptado de la trazabilidad. La auditoría no es otra fuente
para editar ni restaurar el contenido actual del snippet.

## Verificación reproducible

Con JDK 21 y Docker disponibles:

```sh
./gradlew build
```

Para incluir las pruebas con PrintScript real, levantar la imagen del servicio en
un puerto local y ejecutar:

```sh
PRINTSCRIPT_EXECUTION_URL=http://127.0.0.1:18082 ./gradlew build
```

Se verificaron 390 tests: 381 ejecutados sin fallos y 9 integraciones de otros
proveedores omitidas explícitamente por falta de sus variables de entorno. Incluye
PostgreSQL real con Testcontainers, concurrencia de reservas, snapshot durante
cambios, recuperación con un coordinador nuevo, historial perdido, fallo de escritura
final, autorización HTTP y entrega de output antes de liberar el terminal.

`TestExecutionLiveTest` usa Snippets HTTP + PostgreSQL + PrintScript real: aprobado,
diferencias, input agotado, error del programa e inputs/outputs exactos. Permissions
es un proveedor HTTP de contrato y storage es el adaptador local real. No sustituye
la aceptación completa de UI/Permissions/Asset de SNI-53. Las pruebas del cliente
Language con PrintScript real también se ejecutaron.

Las elecciones anteriores son aplicación del proyecto: priorizan latencia y
trazabilidad, aceptando recuperación parcial. El [índice verificado de Fundamentals
of Software Architecture, 2.ª ed.](https://www.oreilly.com/library/view/fundamentals-of-software/9781098175504/)
(cap. 2, análisis de trade-offs; cap. 21, decisiones) es referencia conceptual,
no una recomendación del libro sobre estos endpoints o PostgreSQL.
