# SNI-64 — Consulta de resultados vigentes

Estado: parte de consulta implementada; **ticket completo pendiente de SNI-78**
(operaciones de lint recuperables en proveedor/Language) y de integrar SNI-63/77.
No se registra un consumidor ficticio que libere capacidad tras un timeout.
El lote US15 y su progreso recuperable todavía no se acreditan en esta entrega.

## Decisiones aprobadas

Thiago eligió el 02/11/2026:

- Conservar los estados públicos existentes. Resultados superados se muestran
  `pending` con motivo, sin diagnósticos históricos; no agregar `superseded` a UI.
- Verificar la revisión actual por lenguaje/versión antes de filtrar y paginar.
  Si no se puede verificar, devolver error explícito. Se descarta usar la última
  revisión conocida: toleraría caídas con menor latencia, pero podría certificar
  resultados de un proveedor recién reemplazado.
- Ampliar el proveedor para recuperación automática del trabajo de lint en vez
  de depender de intervención operativa tras un timeout incierto. La forma del
  protocolo queda en SNI-78; no se eligieron endpoints ni persistencia remota aquí.

## Recorrido de lectura

Permissions entrega IDs/owners autorizados. Una consulta corta de metadatos
identifica lenguajes/versiones dentro del filtro de nombre/lenguaje. Se verifica
un catálogo atribuido por cada scope distinto; no se ejecuta lint ni se leen blobs.
No se mantiene transacción PostgreSQL abierta durante HTTP.

Luego el search usa un snapshot repeatable-read para count, resultados y página.
La misma expresión SQL define vigencia en listado y detalle: referencia de código,
lenguaje/versión, owner actual de Permissions, reglas actuales y procesador.
Un scope que aparezca después de la verificación produce error de dependencia,
incluso si el filtro de cumplimiento lo habría ocultado. No se transforma esa
carrera en una lista vacía certificada.

`lint` acepta compliant/not-compliant/pending/failed; `sort=lint` usa orden
lexicográfico del estado y desempate por ID ascendente, siguiendo el mecanismo
de orden existente. Es un orden por campo, no una prioridad de severidad.
El filtro se aplica en SQL antes de count/paginación. No hay filtrado de la página
en memoria ni lecturas del código de todos los snippets.

Detalle conserva la lectura coherente de código/metadatos existente. Si el puntero
cambia antes de adjuntar lint, informa pending/superado y oculta los diagnósticos;
no adjunta resultados nuevos al código que se leyó antes del cambio.
Las filas de auditoría anteriores no se exponen como historial público.

El contrato y ejemplos ejecutables están en [reading.md](reading.md). La UI
SNI-67 mantiene su entrega propia. Se reutilizan puertos de acceso y Language;
la definición de vigencia se centraliza en LintProjectionSql para evitar que
listado y detalle diverjan. Costo: más complejidad SQL y consultas de catálogo
por lectura, aceptadas a favor de vigencia y count correcto.

## Verificación

JDK 21 y Docker: `./gradlew build`.

- LintQueryPersistenceTest usa PostgreSQL real para filtros/count antes de página,
  empate estable, mezcla de owners, invalidación por código/reglas/procesador,
  estados técnicos, carrera de scope y coherencia de detalle.
- VerifyLintReadTest usa proveedor controlado para comprobar una consulta por scope,
  identidad verificable, caída explícita y ausencia de HTTP en resultados vacíos.
- Los contratos HTTP existentes se actualizaron, conservando autorización/storage.

La prueba HTTP opt-in `scripts/verify_lint_queries.py` se ejecuta con todos los
proveedores reales y una base aislada. Configurar cuatro actores dev existentes
(no OAuth), API/PrintScript/Permissions/Asset/Azurite, sin worker de US15 activo.
El fixture actual incluye la limitación explícita de defaults SNI-77 para obtener
un error técnico persistido: **no prueba defaults reales**. Debe actualizarse
cuando se entregue esa capacidad, sin relajar el criterio de defaults.

```sh
python3 scripts/verify_lint_queries.py --phase healthy --evidence /tmp/sni64-evidence.json
# Detener sólo el PrintScript del entorno aislado.
python3 scripts/verify_lint_queries.py --phase processor-down --evidence /tmp/sni64-evidence.json
# Restaurar y esperar recuperación funcional real.
python3 scripts/verify_lint_queries.py --phase recovered --evidence /tmp/sni64-evidence.json
# Detener sólo Asset del entorno aislado.
python3 scripts/verify_lint_queries.py --phase storage-down --evidence /tmp/sni64-evidence.json
# Restaurar y esperar recuperación funcional real.
python3 scripts/verify_lint_queries.py --phase recovered --evidence /tmp/sni64-evidence.json
```

Ejecución local 02/11/2026: las cinco fases pasaron con PrintScript dev
32953ef508763732dee3a3c7323c166c6adb18ac, Permissions, PostgreSQL, Asset y Azurite.
Lectura compartida mostró cuatro estados reales/persistidos; filtro/count y
páginas fueron correctos; tercero no obtuvo snippets compartidos ajenos.
Apagar PrintScript produjo 502, sin lista ficticia; apagar Asset mantuvo listado
200 y detalle 503. Restaurar proveedores recuperó las lecturas. Sin mocks en
estas fases HTTP, sin cambios de CD, Azure ni volúmenes existentes.

Pendiente para cerrar: contrato SNI-78, consumidor del lote, integración de
SNI-77/defaults, progreso/recuperación real con Redis y aceptación con UI SNI-67.
Estas verificaciones parciales no declaran terminadas US15 ni SNI-37.
