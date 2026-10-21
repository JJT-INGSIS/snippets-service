# Engineering Guidelines — Snippet Searcher

These instructions apply to every code change in this repository.

The objective is not only to make the code work.

The codebase must remain:

- easy to understand;
- easy to test;
- easy to modify;
- explicit about responsibilities;
- resistant to accidental complexity.

Prefer simple, readable and maintainable code over clever or over-engineered solutions.

Before implementing a change:

1. Understand the requested use case.
2. Identify which service owns the responsibility.
3. Identify which layer owns each part of the behavior.
4. Keep business logic independent from HTTP, persistence and framework details.
5. Prefer the smallest design that correctly solves the current requirement.
6. Do not implement future User Stories unless explicitly requested.
7. Preserve existing architectural boundaries unless the task explicitly requires changing them.
8. Look for real opportunities to simplify variation through appropriate design patterns.
9. Do not introduce abstractions or patterns without a concrete reason.


# 1. System responsibilities

Snippet Searcher is composed of independent services.

Respect service ownership.

Do not move responsibilities between services simply because doing so is locally convenient.


## snippets-service

Owns:

- snippet use cases;
- snippet metadata;
- orchestration of snippet workflows;
- the `Language` abstraction;
- coordination with storage;
- coordination with Permissions;
- coordination with language-specific services.

It does NOT own:

- PrintScript parsing or validation implementation;
- permissions persistence;
- authentication;
- Azure storage implementation.

Example:

`snippets-service` may ask:

> Validate this code using language X and version Y.

It must NOT know how PrintScript parsing or semantic validation works internally.


## permissions-service

Owns:

- snippet ownership;
- access relationships;
- sharing;
- permissions.

It does NOT own:

- authentication;
- snippet content;
- snippet metadata;
- PrintScript behavior.

Authentication will be delegated to Auth0 when introduced by the course.


## printscript-service

Owns PrintScript-specific operations such as:

- validation;
- execution;
- formatting;
- linting;
- other PrintScript-specific capabilities required by future User Stories.

It does NOT own:

- snippets;
- snippet metadata;
- ownership;
- permissions;
- users.


## Storage

The course will provide the official storage interface and its Azure-backed implementation.

Until then, as the course authorised on 2026-10-07, `snippets-service` uses its own minimal `SnippetContentStorage` interface with a local file adapter. See [storage.md](storage.md). Adapting it to the official contract is tracked in SNI-19.

Do NOT:

- implement Azure directly;
- bypass the abstraction;
- grow the provisional interface into a generic framework;
- duplicate snippet content as another authoritative source.

Integrate the official contract when it becomes available.


# 2. Architectural boundaries

Responsibilities must remain explicit.

Package names may follow the existing repository structure, but conceptually dependencies should look like this:

```text
Web / Controller
        ↓
Application / Use Case
        ↓
Domain
        ↓
Ports / capabilities
        ↓
Infrastructure adapters
```

Dependencies should point toward business logic.

Business logic must not depend on framework or infrastructure details.

A class should belong to the layer that owns its reason to change.


# 3. Web / Controller layer

The web layer owns HTTP concerns.

It is responsible for:

- routes;
- request DTOs;
- response DTOs;
- HTTP status codes;
- HTTP headers;
- serialization;
- transport-level input validation;
- translating application/domain results into HTTP responses.

HTTP decisions must NOT live inside application services, repositories or domain classes.

The important rule is:

```text
Application/domain decides WHAT happened.
The web layer decides HOW that becomes HTTP.
```

For example, the application may report:

```text
SnippetNotFound
InvalidCode
NotSnippetOwner
UnsupportedLanguage
LanguageServiceUnavailable
```

The web layer decides whether those become:

```text
404
422
403
400
503
```

according to the API contract.

Prefer centralized HTTP error translation using Spring Web mechanisms such as `@RestControllerAdvice` when it keeps controllers simple and consistent.

Example:

```kotlin
@RestControllerAdvice
class SnippetExceptionHandler {

    @ExceptionHandler(SnippetNotFoundException::class)
    fun handleNotFound(): ResponseEntity<ApiError> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiError("snippet_not_found"))
}
```

Or map explicit application results directly:

```kotlin
@PostMapping("/snippets")
fun create(
    @RequestBody request: CreateSnippetRequest,
): ResponseEntity<*> =
    when (val result = createSnippet.execute(request.toCommand())) {
        is CreateSnippetResult.Success ->
            ResponseEntity
                .status(HttpStatus.CREATED)
                .body(result.snippet.toResponse())

        is CreateSnippetResult.InvalidCode ->
            ResponseEntity
                .unprocessableEntity()
                .body(result.toResponse())

        CreateSnippetResult.UnsupportedLanguage ->
            ResponseEntity.badRequest().build()
    }
```

Application services must NOT return:

```kotlin
ResponseEntity<*>
HttpStatus
ServerResponse
```

Application, domain and persistence code must not know whether an error eventually becomes HTTP 400, 404, 409, 422, 500 or 503.


# 4. Application / Use Case layer

Application services represent system use cases.

Examples:

```text
CreateSnippetUseCase
UpdateSnippetUseCase
RegisterSnippetOwnerUseCase
ValidatePrintScriptUseCase
```

They are responsible for:

- orchestrating the use case;
- enforcing application rules;
- coordinating collaborators;
- deciding the order of business operations;
- returning meaningful application/domain results.

They must NOT contain:

- HTTP status codes;
- HTTP response construction;
- JSON serialization details;
- JPA queries;
- Spring MVC concerns;
- WebClient configuration;
- environment variable access;
- low-level infrastructure setup.


## Keep orchestration declarative

The main method of an important use case should read almost like a description of the workflow.

Prefer:

```kotlin
fun create(command: CreateSnippetCommand): CreateSnippetResult {
    val validation = validateSnippet(command)

    if (validation is ValidationResult.Invalid) {
        return CreateSnippetResult.InvalidCode(validation.diagnostics)
    }

    val contentReference = storeContent(command)
    val snippet = persistMetadata(command, contentReference)

    registerOwner(snippet.id, command.ownerId)

    return CreateSnippetResult.Success(snippet)
}
```

over:

```kotlin
fun create(command: CreateSnippetCommand): CreateSnippetResult {
    // 100 lines containing:
    // WebClient calls
    // response status handling
    // persistence mappings
    // JSON details
    // retries
    // validation
    // exception mapping
    // ownership
    // logging
}
```

High-level methods should expose intent.

Extract steps when doing so makes the workflow easier to understand.

Good:

```kotlin
validateSnippet()
storeContent()
persistMetadata()
registerOwner()
checkOwnership()
buildUpdatedSnippet()
```

Avoid meaningless extraction:

```kotlin
stepOne()
stepTwo()
process()
handle()
executeInternal()
```

A method name must explain its intention.


# 5. Domain layer

Domain code represents the problem and its rules.

Prefer:

- immutable objects;
- explicit states;
- explicit domain results;
- pure functions;
- meaningful domain types;
- behavior independent from infrastructure.

Domain code should not depend on:

- Spring;
- controllers;
- HTTP;
- WebClient;
- JPA;
- PostgreSQL;
- environment variables;
- infrastructure-specific APIs.

Important domain logic should normally be executable in a regular unit test without starting Spring.


# 6. Persistence layer

Persistence is responsible only for persistence.

It may contain:

- JPA entities;
- Spring Data repositories;
- persistence adapters;
- persistence mappings;
- database-specific queries.

It must NOT decide:

- HTTP responses;
- HTTP status codes;
- authorization policy;
- business workflows;
- language validation;
- application orchestration.

Bad:

```kotlin
fun findSnippet(id: UUID): ResponseEntity<SnippetEntity>
```

Bad:

```kotlin
repository.findById(id)
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
```

Prefer:

```kotlin
fun findById(id: SnippetId): Snippet?
```

or another explicit persistence/application-level result.

The persistence layer reports what happened at the persistence boundary.

The application decides what that means for the use case.

The web layer decides how that result is represented through HTTP.


# 7. External service clients

Remote HTTP integrations belong in explicit infrastructure adapters or clients.

Examples:

```text
PrintScriptClient
PermissionsClient
```

They own:

- building HTTP requests;
- serialization and deserialization;
- remote service URLs;
- HTTP headers;
- HTTP-specific response handling;
- timeout configuration;
- translating transport failures into application-level failures.

They must NOT own the use case.

Example:

```text
printscript-service returns HTTP 503
        ↓
PrintScriptClient
        ↓
LanguageServiceUnavailable
        ↓
CreateSnippetUseCase
        ↓
CreateSnippetResult.ServiceUnavailable
        ↓
Controller / ControllerAdvice
        ↓
HTTP response
```

Do not leak infrastructure types such as:

```text
WebClient
ClientResponse
ResponseEntity
HttpStatus
```

into application or domain code.


# 8. DTOs and models

Models at different boundaries have different responsibilities.

Prefer:

```text
CreateSnippetRequest
        ↓
CreateSnippetCommand
        ↓
Snippet
        ↓
CreateSnippetResponse
```

Do not expose persistence entities directly through HTTP APIs.

Bad:

```kotlin
@PostMapping
fun create(
    @RequestBody entity: SnippetEntity,
): SnippetEntity
```

Prefer:

```kotlin
data class CreateSnippetRequest(
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val content: String,
)
```

A DTO describes an external contract.

A domain/application model describes an internal concept.

A persistence entity describes storage.

Do not duplicate model classes mechanically.

Separate them when sharing the same class would create coupling between responsibilities.


# 9. SOLID — use it pragmatically

Apply SOLID when it improves the design.

Do not apply principles mechanically.


## Single Responsibility Principle

A class should have one clear reason to change.

Bad:

```text
SnippetService
├── parses HTTP
├── validates PrintScript
├── calls Permissions
├── writes PostgreSQL
├── accesses storage
├── maps errors
└── builds HTTP responses
```

Better:

```text
CreateSnippetUseCase
├── LanguageValidator
├── SnippetContentStorage
├── SnippetRepository
└── OwnershipClient
```

Each collaborator represents a real responsibility.


## Open/Closed Principle

Prefer designs where new variants can be added without repeatedly modifying central orchestration code.

For example, adding a new language or validation strategy should preferably involve adding a new implementation rather than editing several unrelated `when` expressions throughout the application.

Do not apply this mechanically.

A small local conditional is better than unnecessary abstraction when variation is not expected.


## Dependency Inversion

Business logic should depend on capabilities rather than infrastructure implementations.

Example:

```kotlin
interface LanguageValidator {
    fun validate(
        code: String,
        version: String,
    ): ValidationResult
}
```

An infrastructure implementation may be:

```text
PrintScriptLanguageValidator
        ↓
PrintScriptClient
        ↓ HTTP
printscript-service
```

The use case does not know about HTTP.


## Interface Segregation

Depend on the smallest capability required by the consumer.

Do not create:

```kotlin
interface LanguageService {
    fun validate(...)
    fun execute(...)
    fun format(...)
    fun lint(...)
    fun debug(...)
    fun transpile(...)
}
```

when a consumer only requires validation.

Prefer:

```kotlin
interface LanguageValidator
```

and introduce additional capabilities when they are actually required.


## Do not over-engineer SOLID

Do NOT:

- create an interface for every class;
- create factories without a real construction problem;
- introduce design patterns because they exist;
- create generic frameworks for hypothetical future requirements;
- abstract code before real variation exists;
- implement architecture for future User Stories that are not currently requested.

Prefer simple concrete code until a real architectural boundary or variation justifies abstraction.


# 10. Use design patterns when they simplify real variation

Actively look for opportunities to use well-known design patterns when they make the code easier to extend, understand or test.

Patterns are encouraged when the problem naturally fits them.

Patterns that have been especially useful in this team's previous projects include:

- Strategy;
- Dispatcher;
- Composite;
- Strategy + Dispatcher;
- Strategy + Composite.

Other patterns may be used when they clearly fit the problem.

Do NOT force a pattern into a problem that can be solved more clearly with simple code.

The goal is:

```text
real variation or composition
        ↓
recognizable structure
        ↓
small interchangeable components
```

not:

```text
simple problem
        ↓
unnecessary pattern
        ↓
extra classes and indirection
```


## Strategy

Use Strategy when multiple interchangeable behaviors implement the same capability.

Good candidates include:

- behavior that varies by language;
- behavior that varies by language version;
- validation rules;
- formatting rules;
- linting rules;
- policies;
- algorithms that may have multiple implementations.

Example:

```kotlin
interface LanguageValidator {

    fun supports(
        language: Language,
        version: LanguageVersion,
    ): Boolean

    fun validate(code: String): ValidationResult
}
```

Possible implementations:

```text
PrintScriptV1Validator
PrintScriptV11Validator
FutureLanguageValidator
```

This may be preferable to growing conditionals such as:

```kotlin
when (language) {
    PRINTSCRIPT_V1 -> ...
    PRINTSCRIPT_V11 -> ...
    PYTHON -> ...
    JAVASCRIPT -> ...
}
```

spread throughout the application.

Variation should be encapsulated in the strategies.


## Dispatcher

Use a Dispatcher when several strategies or handlers exist and a single component should select the appropriate implementation.

Example:

```kotlin
class LanguageValidatorDispatcher(
    private val validators: List<LanguageValidator>,
) {

    fun validate(
        language: Language,
        version: LanguageVersion,
        code: String,
    ): ValidationResult {
        val validator =
            validators.firstOrNull {
                it.supports(language, version)
            } ?: return ValidationResult.UnsupportedLanguage

        return validator.validate(code)
    }
}
```

The caller should not need to know which concrete strategy handles the request.

Prefer:

```text
CreateSnippetUseCase
        ↓
LanguageValidatorDispatcher
        ↓
appropriate LanguageValidator
```

instead of:

```text
CreateSnippetUseCase
        ↓
if language == ...
else if version == ...
else if ...
```

A Dispatcher should mainly:

1. identify the appropriate implementation;
2. delegate the operation.

It should NOT absorb the business logic implemented by the strategies.


## Strategy + Dispatcher

Strategy + Dispatcher is encouraged when:

1. several implementations share the same capability;
2. one implementation must be selected based on input;
3. callers should remain independent from concrete implementations.

Conceptually:

```text
                  ┌── Strategy A
Caller → Dispatcher ── Strategy B
                  └── Strategy C
```

Typical variation axes include:

```text
Language
Version
Rule type
Operation type
```

This combination helps keep callers declarative while keeping individual variations small and independently testable.


## Composite

Use Composite when multiple components implementing the same abstraction must be treated as a single component.

Example:

```kotlin
interface ValidationRule {
    fun validate(snippet: Snippet): List<ValidationDiagnostic>
}
```

Individual rules:

```text
SyntaxRule
VariableDeclarationRule
TypeCompatibilityRule
```

Composite:

```kotlin
class CompositeValidationRule(
    private val rules: List<ValidationRule>,
) : ValidationRule {

    override fun validate(
        snippet: Snippet,
    ): List<ValidationDiagnostic> =
        rules.flatMap { it.validate(snippet) }
}
```

The caller sees:

```text
ValidationRule
```

instead of needing to know how many rules exist internally.


## Strategy + Composite

Strategy + Composite is encouraged when several independent behaviors:

- implement the same abstraction;
- may be added or removed independently;
- must all participate in the operation.

Conceptually:

```text
                 ┌── Strategy A
Caller → Composite ├── Strategy B
                 └── Strategy C
```

Typical examples include:

- formatter rules;
- linter rules;
- validators;
- policies;
- transformations.

This allows new behavior to be added without turning important workflows into long sequences of conditionals.


## Dispatcher vs Composite

Use them for different problems.

Dispatcher:

```text
many implementations exist
        ↓
choose ONE
```

Composite:

```text
many implementations exist
        ↓
apply MANY as if they were one
```

Example:

```text
Language validators
        ↓
Dispatcher
        ↓
choose the validator for the requested language/version
```

Example:

```text
Lint rules
        ↓
Composite
        ↓
execute all applicable rules
```

They may also be combined when the problem requires both selection and composition.


## Prefer polymorphism over growing conditionals

A conditional is not automatically bad.

Simple local branching is often clearer than introducing several classes.

However, reconsider the design when conditionals:

- select behavior based on a type;
- appear in multiple places;
- keep growing when new variants are added;
- require modifying central classes every time a variant appears.

Repeated structures such as:

```kotlin
when (type) {
    A -> handleA()
    B -> handleB()
    C -> handleC()
    D -> handleD()
}
```

may indicate that behavior should be represented by strategies and a dispatcher.

Do not replace a small one-time `when` with a pattern unless real variation exists.


## Patterns should protect important code

One of the main reasons to introduce a pattern is to keep frequently changing business code simple.

Prefer:

```text
CreateSnippetUseCase
        ↓
validateSnippet(...)
```

while internally:

```text
LanguageValidatorDispatcher
        ↓
PrintScriptValidator
```

The use case remains simple even if languages or versions are added later.

Patterns should move variation away from important workflows rather than adding complexity to them.


## Patterns are not goals

Never introduce a pattern only to demonstrate knowledge of design patterns.

Before introducing one, verify:

1. What real variation or composition does it represent?
2. Does it remove duplicated decision logic or growing conditionals?
3. Does more than one meaningful implementation exist or realistically need to exist?
4. Does it make the caller easier to understand?
5. Can the resulting components be tested more independently?
6. Is the result simpler than the alternative?

If these answers are mostly no, prefer the simpler implementation.


# 11. Immutability by default

Prefer immutable data.

In Kotlin prefer:

```kotlin
val
```

over:

```kotlin
var
```

Prefer immutable collections.

Avoid mutable shared state.

Bad:

```kotlin
snippet.name = command.name
snippet.language = command.language
snippet.version = command.version
```

Prefer constructing a new state explicitly:

```kotlin
val updatedSnippet =
    snippet.copy(
        name = command.name,
        language = command.language,
        version = command.version,
    )
```

Mutable state is acceptable when it is:

- local;
- controlled;
- not shared;
- materially simpler than the immutable alternative.

Never use mutable in-memory state as durable application storage.


# 12. Prefer pure functions

Use pure functions whenever practical for:

- mapping;
- validation;
- transformations;
- calculations;
- domain rules.

Example:

```kotlin
fun ValidationResponse.toDomain(): ValidationResult =
    ValidationResult(
        valid = valid,
        diagnostics = diagnostics.map { it.toDomain() },
    )
```

A pure function:

- depends only on its arguments;
- returns a result;
- does not mutate external state;
- does not access the database;
- does not make HTTP requests;
- does not read environment variables.

Prefer a functional core and explicit side-effect boundaries.

Conceptually:

```text
Pure / business logic
        ↓
explicit ports
        ↓
side effects
    ├── HTTP
    ├── DB
    ├── storage
    └── logging
```


# 13. Keep important code simple

The code that changes most often and contains important business decisions should be the easiest code in the repository to read.

Prioritize readability in:

- use cases;
- domain rules;
- policies;
- workflows;
- validation logic.

Important classes should remain:

- small;
- explicit;
- focused;
- easy to read from top to bottom;
- easy to test;
- easy to modify.

A developer should be able to open an important use-case class and understand the main workflow without first understanding Spring, HTTP, PostgreSQL or external SDK details.

Move incidental complexity toward explicit infrastructure components.

Examples of incidental complexity:

- Spring configuration;
- WebClient setup;
- HTTP serialization;
- JPA mappings;
- PostgreSQL configuration;
- Azure integration;
- retries;
- timeout configuration;
- environment variable mapping;
- Docker-specific details;
- framework adapters.

The goal is:

```text
Frequently changing business code
→ small, explicit and easy to modify

Stable infrastructure details
→ isolated behind clear boundaries
```


# 14. Optimize the codebase for change

Organize code according to responsibilities and reasons to change.

Pay special attention to the distinction between:

```text
code that changes frequently
```

and:

```text
technical details that change for different reasons or change rarely
```

Frequently changing business code must be protected from infrastructure complexity.

Examples of frequently changing code:

- use-case orchestration;
- business policies;
- domain validation;
- authorization rules;
- workflow decisions.

Keep this code:

- small;
- readable;
- explicit;
- independently testable;
- minimally coupled to infrastructure.


## Isolate stable technical details

Stable or incidental technical details should be pushed toward infrastructure boundaries and grouped by their actual responsibility.

Examples:

```text
HTTP client configuration
serialization
database mappings
framework configuration
environment variable mapping
retry configuration
timeout configuration
storage SDK integration
```

A change in:

```text
PostgreSQL
WebClient
JSON serialization
Azure storage
environment configuration
```

should affect as little business code as possible.

Likewise, changing a business rule should not require modifying infrastructure code unless the external contract itself changed.


## Do not create junk drawers

"Group stable code" does NOT mean putting unrelated things together.

Do NOT create generic containers such as:

```text
Utils
Common
Helpers
Misc
SharedManager
GeneralService
```

Stable code must still be grouped according to responsibility.

Good:

```text
infrastructure/
├── persistence/
├── http/
├── storage/
└── config/
```

Bad:

```text
common/
├── Utils
├── Helpers
├── SharedLogic
└── CommonService
```

The objective is not to hide complexity.

The objective is to put each kind of complexity in the place that owns it.


# 15. Naming

Names must communicate intent.

Prefer specific names.

Good class names:

```text
CreateSnippetUseCase
SnippetRepository
PrintScriptValidationClient
RegisterSnippetOwnerUseCase
ValidationDiagnostic
SnippetContentStorage
```

Avoid vague names:

```text
Manager
Helper
Utils
Processor
CommonService
GeneralHandler
ThingService
```

unless that term genuinely represents a domain concept.


## Function names

Good:

```kotlin
validateSnippet()
registerOwner()
storeContent()
findSnippetById()
mapValidationResult()
checkOwnership()
buildUpdatedSnippet()
```

Avoid:

```kotlin
process()
handle()
manage()
doStuff()
executeAction()
runInternal()
```

Boolean names should read naturally:

```kotlin
isValid
hasAccess
isOwner
canUpdate
```


# 16. Comments and documentation

Prefer code that explains itself through naming and structure.

Comments should explain WHY.

Do not comment obvious behavior.

Bad:

```kotlin
// Validate snippet
validateSnippet()
```

Useful:

```kotlin
// Register ownership only after content and metadata are available.
// Otherwise Permissions could expose a snippet that cannot be retrieved.
registerOwner(...)
```

Document decisions that cannot be reconstructed from the code.

Examples:

- why a particular operation order exists;
- why an interface exists;
- why a retry strategy exists;
- why a limitation was intentionally accepted.

Keep relevant technical documentation close to the code and versioned with the repository.

Do not create documentation that merely duplicates what can be understood directly from the code.


# 17. Error handling

Expected application failures should be represented explicitly.

Examples:

```text
SnippetNotFound
NotSnippetOwner
InvalidCode
UnsupportedLanguage
UnsupportedVersion
LanguageServiceUnavailable
StorageUnavailable
```

Do not use generic exceptions for normal expected business flow when an explicit result communicates the case better.

Do not catch every exception and silently convert it into a generic failure.

Preserve useful information while translating errors across boundaries.

Example:

```text
PrintScript diagnostic
rule + line + column
        ↓
Language boundary
        ↓
CreateSnippetResult.InvalidCode
        ↓
Web layer
        ↓
HTTP error response
```

Do not discard diagnostics unnecessarily.


## HTTP errors belong to the web boundary

Never throw or construct HTTP-specific errors from:

- domain classes;
- application services;
- repositories;
- persistence adapters.

Bad:

```kotlin
throw ResponseStatusException(
    HttpStatus.NOT_FOUND,
    "Snippet not found",
)
```

inside a repository or use case.

Prefer an application/domain concept such as:

```text
SnippetNotFound
```

and translate it to HTTP at the web boundary.


## Persistence errors are not HTTP errors

A database operation may report a persistence failure.

It must not decide that the failure means:

```text
500 Internal Server Error
404 Not Found
409 Conflict
```

That interpretation belongs to higher boundaries.


## Remote HTTP errors must not leak through the application

An external HTTP client may receive:

```text
404
409
500
503
```

The HTTP adapter/client should translate transport details into a meaningful application-level failure.

Example:

```text
HTTP 503 from PrintScript
        ↓
PrintScriptClient
        ↓
LanguageServiceUnavailable
```

The rest of the application should not need to understand the remote service's HTTP implementation.


# 18. Configuration

Configuration that changes between environments must come from outside the application.

Examples:

- service URLs;
- database URLs;
- credentials;
- ports;
- timeouts;
- feature flags;
- storage endpoints.

Bad:

```kotlin
val permissionsUrl = "http://localhost:8081"
```

Prefer external configuration:

```kotlin
@ConfigurationProperties("services.permissions")
data class PermissionsProperties(
    val baseUrl: String,
)
```

The same application artifact should be able to run in different environments by changing configuration rather than source code.


# 19. Secrets

Never commit:

- passwords;
- personal access tokens;
- API keys;
- private credentials.

Do not store secrets in:

- source code;
- committed `.env` files;
- committed YAML configuration containing real credentials;
- Dockerfiles;
- test fixtures.

Use environment variables or platform secret mechanisms.


# 20. Logging

Application logs should be written to stdout/stderr.

Do not create application-managed local log files unless explicitly required.

Logs should provide useful operational context.

Never log:

- passwords;
- access tokens;
- secrets;
- private credentials;
- sensitive authentication information.


# 21. Dependencies

Dependencies must be explicit and reproducible.

- Declare required dependencies in Gradle.
- Prefer fixed versions.
- Do not rely on software being manually installed on a developer machine.
- Do not add a dependency if the standard library or an existing project dependency solves the problem adequately.
- Do not update unrelated dependencies while implementing a ticket.

Every dependency increases maintenance and supply-chain risk.


# 22. Tests

Tests exist to protect behavior and make future changes safer.


## Unit tests

Prefer unit tests for:

- domain rules;
- pure functions;
- mappings;
- validation;
- application behavior without real infrastructure.

A unit test should normally not require:

- network access;
- PostgreSQL;
- Docker;
- a full Spring application startup.


## Integration tests

Use integration tests for real boundaries such as:

- PostgreSQL;
- HTTP clients;
- serialization;
- Spring wiring;
- persistence adapters;
- integrations between infrastructure components.


## Test behavior, not implementation

Prefer:

```text
invalid PrintScript is rejected with its diagnostic information
```

over:

```text
validate() was called exactly once
```

Avoid tests that fail only because a private implementation detail was refactored.


## Design for testability

If important business logic requires extensive mocking to test, reconsider the design.

Common causes are:

- hidden dependencies;
- too many responsibilities;
- shared mutable state;
- infrastructure mixed with business logic;
- global state.

Important business logic should ideally be testable without infrastructure.


## Test strategies independently

When using Strategy, Dispatcher or Composite:

- test individual strategies independently;
- test dispatcher selection separately from strategy behavior;
- test composite orchestration separately from individual rule behavior when useful.

Avoid large tests where a failure makes it unclear which strategy or rule caused the problem.


## Coverage

Coverage is useful information, not proof of correctness.

Tests must contain meaningful assertions and verify relevant behavior.


# 23. Static analysis and formatting

The configured project tools are part of the engineering contract.

Respect:

- Kotlin compiler;
- ktlint;
- detekt;
- tests;
- Gradle build.

Do not:

- disable a rule globally just to make a change pass;
- comment out a failing test;
- ignore a build failure;
- suppress warnings broadly without justification.

If suppression is genuinely necessary:

- keep it as local as possible;
- make the reason understandable.


# 24. Build and reproducibility

The project must be verifiable through a single reproducible command:

```bash
./gradlew build
```

The build should compile, run configured static checks, execute tests and package the application as configured by the project.

Do not require undocumented manual preparation steps before running the build.

Do not consider a task finished while the build is red.


# 25. Keep the main branch healthy

Changes intended for merge must leave the project build green.

Do not normalize:

- broken builds;
- ignored tests;
- disabled checks;
- temporary linter bypasses;
- unresolved TODOs introduced only to make the task pass.

If a change breaks the build, fix it before considering the implementation complete.

A red main branch is a problem to fix or revert, not a state to tolerate.


# 26. Keep changes focused

Implement the requested ticket or use case.

Do not opportunistically implement unrelated future User Stories.

Do not perform a large unrelated refactor while solving a small ticket.

If an unrelated problem is discovered:

1. mention it;
2. create or propose a separate ticket if necessary;
3. keep the current change focused.

A pull request should have one coherent purpose.


# 27. Avoid speculative architecture

Do not build functionality simply because it may be useful later.

Before introducing an abstraction, ask:

1. Does the current requirement need it?
2. Does it represent a real architectural boundary?
3. Is there actual variation?
4. Does it make the important code easier to understand?
5. Would Strategy, Dispatcher, Composite or another pattern actually simplify the variation?

If the answer is no, prefer the simpler solution.


# 28. Framework code should stay at the edges

Spring is infrastructure.

Avoid spreading Spring-specific concerns through business code.

Prefer framework annotations and configuration in:

- controllers;
- configuration classes;
- adapters;
- persistence implementations.

Avoid designing business classes around Spring rather than around the problem being solved.


# 29. Side effects should be visible

Code performing side effects should make that fact clear.

Examples:

```text
repository.save(...)
permissionsClient.registerOwner(...)
contentStorage.store(...)
printScriptClient.validate(...)
```

Avoid hiding important side effects inside:

- constructors;
- getters;
- property access;
- mapping functions;
- apparently pure transformations.

Bad:

```kotlin
val snippet = request.toSnippet()
```

if `toSnippet()` secretly performs HTTP or persistence.

Mapping functions should normally be pure.


# 30. Prefer explicit workflows over hidden behavior

Important behavior should be visible from the use case.

Good:

```kotlin
fun update(command: UpdateSnippetCommand): UpdateSnippetResult {
    val current = findSnippet(command.snippetId)

    checkOwnership(
        actorId = command.actorId,
        snippetId = current.id,
    )

    val candidate = buildUpdatedSnippet(current, command)

    validateSnippet(candidate)
    updateContent(candidate)
    updateMetadata(candidate)

    return UpdateSnippetResult.Success(candidate)
}
```

Avoid hiding important business behavior through:

- entity lifecycle hooks;
- magic listeners;
- unexpected framework callbacks;
- setters with side effects;
- overly generic interceptors.

Use framework mechanisms when they simplify infrastructure, but keep business workflows explicit.


# 31. Prefer composition over oversized classes

If a class has many unrelated collaborators, reconsider its responsibility.

A use case may orchestrate several collaborators, but it should not implement their internal details.

Warning signs:

- a class has many unrelated dependencies;
- methods operate on unrelated concepts;
- unrelated reasons cause the same class to change;
- a class mixes HTTP, persistence, mapping and business rules;
- understanding one method requires understanding most of the framework.

Prefer composing small components with clear responsibilities.


# 32. Do not duplicate business logic across entry points

Different entry points representing the same use case should converge on the same application logic.

For example:

```text
Create snippet from file
Create snippet from browser editor
            ↓
    CreateSnippetUseCase
```

Do not create separate business implementations simply because the input arrived through different UI mechanisms.

Likewise:

```text
Update from file
Update from editor
        ↓
UpdateSnippetUseCase
```

Transport differences belong at the boundary.

Business behavior belongs in the shared use case.


# 33. Prefer explicit ownership of mappings

Mapping logic should live near the boundary it translates.

Examples:

```text
HTTP DTO → application command
web boundary

JPA entity → domain model
persistence boundary

remote HTTP response → application model
HTTP client / adapter boundary
```

Avoid a global:

```text
MapperUtils
```

class containing unrelated mappings from the entire application.


# 34. Make invalid states difficult to represent

When practical, model important concepts explicitly instead of relying on loosely related primitive values.

For example, where justified:

```kotlin
@JvmInline
value class SnippetId(
    val value: UUID,
)
```

rather than passing unrelated `UUID` values everywhere.

Another example:

```kotlin
sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class Invalid(
        val diagnostics: List<ValidationDiagnostic>,
    ) : ValidationResult
}
```

instead of:

```kotlin
Pair<Boolean, List<String>>
```

Do not introduce wrapper types mechanically.

Use them when they:

- prevent realistic mistakes;
- clarify important domain concepts;
- make invalid combinations harder to construct.


# 35. Before changing existing code

Before refactoring or replacing existing architecture:

1. understand why the current code exists;
2. inspect existing tests;
3. inspect existing interfaces and public contracts;
4. preserve behavior unless the ticket explicitly changes it;
5. avoid broad rewrites when a small change is enough.

Do not rewrite working code purely to match a preferred style.

If the existing implementation already solves the problem cleanly, extend it instead of replacing it unnecessarily.


# 36. AI-generated changes must still be verified

An AI agent is a contributor and reviewer, not the source of truth.

Do not assume code is correct because it was generated successfully.

Use the automated project tools to verify the result.

The expected flow is:

```text
AI proposes or implements
        ↓
compiler
        ↓
static analysis
        ↓
tests
        ↓
build
```

The agent should use judgment to propose a design.

The project's automated checks provide reproducible verification.


# 37. Before finishing a change

Verify all of the following:

- Is the responsibility in the correct service?
- Is the responsibility in the correct layer?
- Are HTTP concerns confined to the web boundary?
- Are HTTP status codes absent from application, domain and persistence code?
- Is persistence isolated from HTTP and business orchestration?
- Are external HTTP details isolated in clients/adapters?
- Is important business code small and easy to read?
- Are the most frequently changing classes simple and focused?
- Is the main business workflow obvious from the main use-case method?
- Are important methods declarative?
- Are names descriptive?
- Is mutable state avoided where practical?
- Could transformations or domain rules be pure?
- Are side effects explicit?
- Are stable technical details isolated from frequently changing business logic?
- Are stable details grouped by responsibility rather than placed in generic containers?
- Did we avoid generic `Utils`, `Helpers`, `Common` or similar junk drawers?
- Was there a real opportunity for Strategy, Dispatcher, Composite or another design pattern?
- If a pattern was introduced, does it simplify real variation rather than add unnecessary indirection?
- If multiple interchangeable behaviors exist, should they be represented as Strategies?
- If one implementation must be selected, would a Dispatcher simplify the caller?
- If multiple implementations must participate as one, would a Composite simplify the caller?
- Did we avoid unnecessary abstractions?
- Did we avoid unnecessary dependencies?
- Are DTOs separated from persistence entities where appropriate?
- Are expected errors represented explicitly?
- Are HTTP errors translated only at the web boundary?
- Are relevant behaviors tested?
- Are strategies/rules independently testable where applicable?
- Are infrastructure boundaries integration-tested where appropriate?
- Is configuration externalized?
- Are secrets absent from the repository?
- Is logging suitable for stdout/stderr?
- Is the change focused on the requested ticket?
- Did we avoid implementing future User Stories without being asked?
- Did we preserve existing behavior that was outside the scope of the ticket?
- Does `./gradlew build` pass?

If one of these answers is no, fix it or explicitly justify why the exception is necessary.
