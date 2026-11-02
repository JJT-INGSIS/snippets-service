# SNI-60 — lint en Language

Language expone `lintingCatalog(language, version)`, `checkLintingRules(language,
version, rules)` y `lint(language, version, code, rules)`. Selecciona la capacidad
LanguageLinter y rechaza lenguajes/versiones no soportados sin enviar HTTP.

PrintScriptLinter utiliza PrintScriptExchange de SNI-59 y las mismas propiedades
language.printscript.base-url, connect-timeout y read-timeout. No hay otro cliente
HTTP, reintentos automáticos ni framework genérico de reglas.
El [contrato del proveedor](https://github.com/JJT-INGSIS/printscript-service/blob/dev/docs/linting.md)
es la fuente de las rutas, límites y semántica.

El mapa de reglas viaja sin reinterpretación: **{} es una selección vacía y no
activa los defaults de la biblioteca**. Las opciones CHOICE y acceptsNull se
conservan desde el catálogo; no se mantienen listas de reglas u opciones locales.

LintResult.Analyzed indica análisis completo; su propiedad compliant se deriva de
las infracciones. InvalidCode representa errores léxicos/sintácticos y no tiene
valor de cumplimiento. InvalidRules, límites, versión no soportada y Failed son
resultados distintos. Una respuesta contradictoria, timeout, JSON ilegible o un
error técnico es Failed, nunca una infracción inventada.

Todas las operaciones devuelven Attributed con la ProcessorIdentity del header
Processor-Revision, conforme a SNI-76. Missing/Malformed no se reemplazan por la
versión del lenguaje. Los consumidores que persistan resultados deben comparar
la revisión recibida con la esperada antes de acreditar vigencia.

No cambia el guardado, permisos, storage, rutas públicas, NDJSON ni OAuth. US14
guarda preferencias en SNI-63; Redis/reprocesamiento se conecta en SNI-64.
El plan y las cuatro condiciones fueron aprobados por Juan el 02/11/2026.

## Verificación

`./gradlew build`, con JDK 21 y Docker para las integraciones existentes.
PrintScriptLinterTest prueba transporte, selección vacía, CHOICE/null, revisión,
infracciones/código inválido/fallas técnicas, respuesta incoherente, límites,
timeout y proveedor caído. LanguageLintingTest prueba dispatch, rechazo local y
duplicación de capacidades. El proveedor prueba el linter real y el contrato HTTP.

Para comprobar el recorrido contra el JAR real, iniciar printscript-service
localmente y definir PRINTSCRIPT_LINT_URL con su URL antes de ejecutar el build.
PrintScriptLintLiveTest compara la revisión de catálogo/análisis con
capabilities.lint y comprueba selección vacía, infracciones, sintaxis y CHOICE.
Sin esa variable sólo se omiten estas dos pruebas de integración externa.
