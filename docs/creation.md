# Preparación de creación — SNI-9

Propuesta backend sujeta a revisión con la UI. **No hay un endpoint funcional
POST /snippets**. SNI-9 prepara piezas internas; SNI-17 habilitará la creación tras
integrar el storage real en SNI-8. No se inventa una interfaz de almacenamiento.

## Request propuesto

POST `/snippets`, Content-Type `application/json`, con `Idempotency-Key` UUID
canónico de 36 caracteres y `X-Dev-Actor-Id` obtenido del contexto de desarrollo:

```json
{
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0",
  "code": "println(1);"
}
```

Nombre, lenguaje, versión y código son strings obligatorios no nulos. Los primeros
tres no pueden estar en blanco. El código puede estar vacío; Language decide su
validez. No hay versión por defecto. Descripción es opcional: ausente y null son
equivalentes, vacío es distinto. Se conservan valores sin recortar ni normalizar.
Archivo y editor entregan el mismo contenido al mismo caso de uso.

`DraftJsonDecoder` rechaza tipos incorrectos, campos desconocidos (incluidos ownerId,
actorId y fingerprint), JSON mal formado y campos requeridos ausentes. La futura
capa HTTP debe usar esta decodificación estricta, no coerción automática de strings.

`ActorContext` conserva todos los valores de X-Dev-Actor-Id para rechazar headers
múltiples. `DevelopmentActorIdentityResolver` solo acepta el header con perfil
Spring `dev` explícitamente activo. Sin ese perfil devuelve Disabled. No hay actor
por defecto ni Auth0; identidad ausente, blanca o múltiple se rechaza. Identidades
válidas se comparan exactamente. Este mecanismo no es autenticación y no debe
habilitarse como identidad de producción. El owner nunca se obtiene del body.

## Preparación interna

`PrepareCreation.prepare(draft, keyHeader, context)` valida identidad, clave, campos
y Language antes de reservar metadatos. Solo Valid permite preparar la creación.
Devuelve Prepared(metadata, replayed), Conflict o Rejected con motivo tipado.
**Prepared no es un alta exitosa**: una nueva reserva es PENDING, una existente
conserva su UUID y estado actual. No se confirma ni se registra ownership.

La huella se calcula en el backend: SHA-256 de `[name, description, language,
version, code]` serializado como array JSON de orden fijo en UTF-8, con digest
hexadecimal y prefijo `sha256-v1:`. No depende del orden del JSON recibido, no usa
concatenaciones ambiguas y distingue descripción nula y vacía. La identidad se
compara por separado. El cliente no puede elegir la huella.

Se consume prepareCreation según [el contrato de SNI-6](metadata.md). Misma clave,
identidad y huella recuperan UUID y estado vigente; otra identidad o huella producen
conflicto sin exponer el registro. Cada preparación vuelve a validar el código.
SNI-17 definirá la respuesta pública de un reintento ya confirmado, incluyendo
el comportamiento ante indisponibilidad de Language.

`FindPreparedCreation.find(keyHeader, context)` traduce findCreation a Found,
Missing, IdentityConflict o rechazo. Una consulta encontrada no comprueba la huella:
para comparar un nuevo pedido se debe usar prepareCreation. No se genera otra
clave ante timeout o commit incierto. MetadataPersistenceException se convierte
en TechnicalFailure("metadata"), sin SQL ni credenciales. No se mantiene una
transacción JDBC abierta durante operaciones externas.

`CreationResponse.fromConfirmed` rechaza los pendientes. Ninguna preparación
confirma metadatos, almacena código o llama al registro de ownership. La vinculación
entre contenido y metadatos queda exclusivamente en SNI-8.

## Permissions y configuración

OwnershipRegistrar y OwnershipReader son interfaces pequeñas separadas. Sus
adaptadores consumen PUT `/ownership/{uuid}` con ownerId y GET de la misma ruta.
Respuestas exitosas requieren application/json, UUID correcto y owner válido;
registro también exige el mismo owner solicitado. 201 es nuevo, 200 compatible,
409 conflicto y 404 de consulta inexistencia. Problem Details requieren su tipo
estable, status e instance; nunca se decide por mensajes humanos.

400, 5xx, timeout, respuestas vacías o incompatibles son fallas técnicas, no código
inválido ni inexistencia. Un timeout de registro o éxito incompatible es incierto:
la operación puede haber ocurrido. No hay reintentos automáticos; el flujo futuro
puede repetir el mismo UUID y owner. El cliente es inyectable y está probado, pero
el flujo parcial no lo llama. Charset en Content-Type es aceptado.

PERMISSIONS_BASE_URL tiene default http://localhost:8081. Los límites configurables
PERMISSIONS_CONNECT_TIMEOUT y PERMISSIONS_READ_TIMEOUT tienen defaults 2s y 5s.
En Docker la URL es http://permissions-service:8080. Language toma como fallback
PRINTSCRIPT_BASE_URL, conservando prioridad de LANGUAGE_PRINTSCRIPT_BASE_URL.
El default local de PrintScript sigue siendo http://localhost:8082.

## Respuestas futuras — no habilitadas

SNI-17 habilitará 201 Created para alta confirmada y 200 OK para reintento compatible
ya confirmado. CreationResponse contiene id UUID, name, description, language y
version, sin entidades JDBC ni reservas PENDING. Ejemplo futuro probado:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Example",
  "description": null,
  "language": "printscript",
  "version": "1.0"
}
```

Propuesta de Problem Details con
type, title, status, detail e instance:

| Status | type propuesto | Significado |
| --- | --- | --- |
| 400 | urn:snippets:problem:invalid-request | Campos o clave inválidos. |
| 401 | urn:snippets:problem:identity-unavailable | Identidad inválida o deshabilitada. |
| 409 | urn:snippets:problem:creation-conflict | Clave incompatible. |
| 422 | urn:snippets:problem:invalid-code | Diagnósticos con rule, message, line y column. |
| 422 | urn:snippets:problem:unsupported-language | Lenguaje no soportado. |
| 422 | urn:snippets:problem:unsupported-version | Versión no soportada. |
| 502 | urn:snippets:problem:dependency-failure | Falla técnica externa. |
| 503 | urn:snippets:problem:metadata-unavailable | Falla técnica de base. |

Esta propuesta es para Snippets: en PrintScript el código inválido sigue siendo
HTTP 200 con valid=false. No se prometen todavía recuperación ni errores de storage.

## Verificación

`sh ./gradlew check build --no-daemon` verifica request documentado, tipos, huella,
identidad, diagnósticos, reintentos, errores, wiring y ausencia de endpoints. Las
reservas se prueban sobre PostgreSQL 18.6 mediante Testcontainers, con concurrencia
y pendientes invisibles para findConfirmed. Los tests HTTP usan un servidor local
controlado; no requieren ni simulan un contrato desconocido de storage.
