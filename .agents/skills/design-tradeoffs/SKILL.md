---
name: design-tradeoffs
description: Preparar, consultar y registrar decisiones de diseño con el usuario al planificar o implementar cambios en este proyecto, incluyendo compromisos técnicos, operativos, económicos y de organización.
---

# Decisiones de diseño con trade-offs explícitos

El usuario participa en las decisiones, no solo recibe una explicación después
de implementarlas. Una elección por convención, facilidad o tiempo también tiene
beneficios y costos: nombrarlos sin presentarla como universalmente superior.

## Antes de elegir

- Leer el ticket, las instrucciones del repositorio y la implementación relevante.
  Separar evidencia comprobada, requisitos obligatorios y suposiciones.
- Identificar decisiones ya aprobadas. Aplicarlas sin volver a pedir aprobación,
  salvo que cambien sus condiciones, alcance o consecuencias.
- Consultar antes de implementar una decisión nueva con alternativas razonables.
  Incluir cambios en contratos, dependencias, datos, seguridad, consistencia,
  recuperación, operación, costos y carga de trabajo del equipo. También consultar
  elecciones por simplicidad o convención cuando determinen el diseño.
- No abrir elecciones ficticias sobre un requisito obligatorio. Explicar su motivo
  y costo; pedir dirección si cumplirlo exige ampliar el alcance.
- Se pueden resolver sin consulta ediciones mecánicas y detalles locales que sigan
  una convención ya establecida, sean fácilmente reversibles y no cambien garantías
  ni comportamiento observable. Si hay duda sobre su importancia, consultar.

## Cómo presentar una decisión

Formular preguntas concretas, en español, agrupando solo decisiones relacionadas
que hagan falta para el siguiente paso. Evitar un cuestionario especulativo de
todo el proyecto. Para cada decisión presentar:

1. Qué hay que decidir y qué evidencia o restricción la motiva.
2. Alternativas viables: ventajas, desventajas y trabajo/costo relevante de cada una.
3. Recomendación y por qué encaja en este proyecto, con sus límites y lo que pierde.
4. Reversibilidad: qué costaría cambiarla y qué datos o consumidores afectaría.
5. Pregunta que permita al usuario elegir o proponer otra alternativa.

No recomendar comparando una opción real con una alternativa caricaturizada.
No asumir que simplicidad, rendimiento, pureza o extensibilidad siempre tienen
prioridad: explicitar qué se optimiza y qué se sacrifica.

## Mientras se espera

- No implementar la parte que dependa de una decisión sin respuesta. El silencio,
  una opción preseleccionada o una recomendación del agente no son aprobación.
- Avanzar con inspección, pruebas diagnósticas y trabajo independiente seguro.
  Si no queda trabajo independiente, pausar y solicitar la decisión pendiente.
- Una aprobación vale para lo decidido, no para despliegues, migraciones, borrados,
  gastos o cambios de otros sistemas que requieran autorización adicional.
- Preservar las restricciones vigentes sobre Git. Esta skill no autoriza commits,
  push, pull, merges ni modificaciones de estado en Linear.

## Registro y cierre

Registrar las decisiones sustanciales en la documentación del ticket o diseño
existente, sin duplicar fuentes de verdad. Usar un ADR separado solo cuando su
impacto y duración lo justifiquen, no para cada detalle de código.

Cada registro debe indicar contexto, alternativas, elección y motivo, beneficios,
costos/riesgos aceptados, quién la aprobó y evidencia verificable de esa aprobación
(por ejemplo fecha y resumen del mensaje), estado y condiciones para revisarla.
No inventar aprobaciones. Distinguir propuesta, aprobada, implementada y verificada.
Las convenciones aplicadas sin consulta se identifican como tales, no como una
decisión nueva aprobada por el usuario.

Al entregar, resumir qué se implementó, por qué, alternativas descartadas,
desventajas que permanecen y verificación realizada. Identificar las decisiones
pendientes y no presentar una capacidad futura como ya implementada.
