# Engineering Guidelines — Snippet Searcher

These instructions apply to every code change in this repository.
Prefer simple, readable, testable and maintainable code over clever or over-engineered solutions.

## 1. Scope and preparation

- Treat each service as an independent repository; respect service ownership.
- Understand the requested use case and identify the service and layer responsible before implementing.
- Inspect existing code, tests, interfaces and public contracts before changing architecture.
- Preserve existing behavior and architectural boundaries unless the task explicitly changes them.
- Extend working code instead of rewriting it solely to match a preferred style.
- Implement only the requested ticket; do not implement future User Stories or unrelated refactors.
- Mention unrelated problems and propose separate work rather than expanding the current change.
- Keep each pull request focused on one coherent purpose.

The complete reference, including the original examples, is in
[docs/engineering-guidelines.md](docs/engineering-guidelines.md).
Read the relevant sections when making architectural decisions, choosing patterns,
designing error contracts or tests, or when these concise rules need clarification.
The reference explains these rules; it is not a reason to expand the requested scope.

## 2. Service responsibilities

### snippets-service

Owns snippet use cases, metadata, workflow orchestration, the `Language` abstraction,
and coordination with storage, Permissions and language-specific services.

Does not own PrintScript parsing or validation implementation, permissions persistence,
authentication or Azure storage implementation.
It may request validation for a language/version without knowing how PrintScript works internally.

### permissions-service

Owns snippet ownership, access relationships, sharing and permissions.

Does not own authentication, snippet content, snippet metadata or PrintScript behavior.
Authentication will be delegated to Auth0 when introduced by the course.

### printscript-service

Owns PrintScript validation, execution, formatting, linting and other PrintScript-specific
capabilities only when required by requested User Stories.

Does not own snippets, snippet metadata, ownership, permissions or users.

### Storage

The course will provide the official storage interface and its Azure-backed implementation.
Until then, as the course authorised on 2026-10-07, snippets-service uses its own minimal
`SnippetContentStorage` interface with a local file adapter, described in
[docs/storage.md](docs/storage.md). Adapting it to the official contract is tracked in SNI-19.
Do not implement Azure directly, bypass the abstraction, grow it into a generic framework,
or duplicate snippet content as another authoritative source.

## 3. Architectural boundaries

Follow the existing package structure while keeping responsibilities explicit.
Dependencies must point toward business logic; infrastructure implements required capabilities.

- Web/controller: routes, request/response DTOs, status codes, headers, serialization,
  transport-level validation and translation of application/domain outcomes to HTTP.
- Application/use case: orchestrate business operations, enforce application rules,
  coordinate collaborators and return meaningful application/domain results.
- Domain: problem concepts and rules, independent of HTTP, Spring, WebClient, JPA,
  PostgreSQL, environment variables and infrastructure-specific APIs.
- Ports/capabilities: the narrow contracts needed by business logic.
- Infrastructure: persistence, external clients, configuration and technical adapters.

HTTP decisions belong only to the web boundary.
Application/domain decides what happened; the web layer decides how it becomes HTTP.
Use `@RestControllerAdvice` or explicit controller result mapping when appropriate.
Application, domain and persistence must not return or construct `ResponseEntity`,
`HttpStatus`, `ServerResponse` or `ResponseStatusException`.

Keep Spring annotations and setup at the edges: controllers, configuration and adapters.
Do not design business classes around framework details.

## 4. Use cases and workflows

- Keep important business code small, focused and readable from top to bottom.
- Make the main use-case method a declarative description of the workflow.
- Extract meaningful steps such as `validateSnippet`, `storeContent`,
  `persistMetadata`, `registerOwner` and `checkOwnership` when they clarify intent.
- Use cases coordinate collaborators; they do not implement their internal infrastructure details.
- Do not put HTTP construction, JSON details, JPA queries, Spring MVC concerns,
  WebClient setup, environment access or low-level configuration in use cases.
- Keep business operations visible instead of hiding them in entity hooks, listeners,
  setters, constructors or unexpected framework callbacks.
- Different entry points for the same operation must converge on the same use case.
  Transport differences must not create duplicate business logic.
- If a class has many unrelated collaborators or reasons to change, reconsider its responsibility.
- Isolate frequently changing rules and workflows from stable technical complexity.

## 5. Persistence and external clients

Persistence owns JPA entities, repositories, database queries, adapters and persistence mappings.
It reports persistence outcomes; it must not decide authorization policy, business workflows,
language validation, application orchestration or HTTP responses.

Remote HTTP integrations belong in explicit clients/adapters such as
`PrintScriptClient` and `PermissionsClient`.
They own requests, serialization, URLs, headers, timeouts and remote HTTP handling.
Translate transport failures into application-level failures such as
`LanguageServiceUnavailable`; do not leak `WebClient`, `ClientResponse` or HTTP statuses
into application/domain code.
Clients must not absorb the use case.

## 6. Models, mappings and errors

- Distinguish external DTOs, application commands/results, domain models and persistence entities
  when sharing a model would couple different responsibilities.
- Do not expose persistence entities directly through HTTP.
- Do not duplicate model classes mechanically.
- Place mappings at the boundary they translate: web, persistence or remote client.
- Keep mappings pure; do not hide HTTP or database operations in transformations.
- Use explicit states/results and meaningful domain types when they prevent realistic mistakes.
  Do not introduce wrapper types mechanically.
- Represent expected failures explicitly: for example `SnippetNotFound`, `NotSnippetOwner`,
  `InvalidCode`, `UnsupportedLanguage`, `UnsupportedVersion` and `StorageUnavailable`.
- Do not use generic exceptions for expected flow when an explicit result communicates it better.
- Do not catch every exception and silently turn it into a generic failure.
- Preserve useful diagnostics, including rule, line and column, when translating failures.
- Persistence and remote errors are not public HTTP decisions; translate only at the web boundary
  according to the API contract.

## 7. SOLID and design patterns

Apply SOLID pragmatically: one clear reason to change, narrow capabilities,
business dependencies on capabilities rather than infrastructure, and extension without
repeatedly editing central orchestration when real variation exists.

Actively consider patterns when they simplify a concrete problem:

- Strategy: interchangeable behaviors implementing one capability.
- Dispatcher: select one suitable strategy and delegate; do not absorb strategy logic.
- Composite: apply multiple components through the same abstraction as a single component.
- Strategy + Dispatcher: select behavior by language, version, rule or operation.
- Strategy + Composite: compose independent rules, validators, formatters or policies.

Keep callers independent from concrete variants.
Reconsider growing or duplicated type-based conditionals, but keep small local conditionals
when they are clearer than extra classes.

Before adding a pattern or abstraction, check that it represents a real boundary, variation
or composition; removes repeated decisions; clarifies the caller; improves independent testing;
and is simpler than the alternative.
Do not create an interface for every class, unnecessary factories, generic frameworks,
or architecture for hypothetical future requirements.
Patterns are tools, not goals.

## 8. Immutability and visible side effects

- Prefer Kotlin `val`, immutable objects/collections and pure functions.
- Use pure functions for mapping, validation, transformations, calculations and domain rules.
- Mutable state is acceptable when local, controlled, unshared and materially simpler.
- Never use mutable in-memory state as durable application storage.
- Keep HTTP, database, storage and logging side effects behind explicit boundaries.
- Do not hide side effects in getters, property access, constructors or apparently pure functions.

## 9. Naming, organization and documentation

- Name classes and methods by intent, such as `CreateSnippetUseCase`,
  `SnippetRepository` and `PrintScriptValidationClient`.
- Prefer meaningful boolean names such as `isValid`, `hasAccess`, `isOwner` and `canUpdate`.
- Avoid vague `Manager`, `Helper`, `Utils`, `Processor` or generic method names
  unless they genuinely express a domain concept.
- Group infrastructure by responsibility: persistence, HTTP, storage and configuration.
  Do not create junk drawers such as `Common`, `Misc` or global `MapperUtils`.
- Comments explain why, not obvious behavior.
- Document decisions that cannot be reconstructed from the code, including ordering,
  abstraction reasons, retry policies and accepted limitations.
- Keep relevant documentation versioned near the code; do not mechanically duplicate code.

## 10. Configuration, security and dependencies

- Externalize environment-specific URLs, database settings, credentials, ports, timeouts,
  feature flags and storage endpoints.
- The same artifact must run in different environments through configuration, not source edits.
- Never commit passwords, access tokens, API keys or private credentials in source,
  committed environment/YAML files, Dockerfiles or fixtures.
- Use environment variables or platform secret mechanisms.
- Write application logs to stdout/stderr with useful operational context.
  Do not create application-managed log files unless explicitly required.
- Never log secrets, tokens, credentials or sensitive authentication information.
- Declare dependencies in Gradle and prefer fixed, reproducible versions.
- Do not rely on undocumented software manually installed on a developer's machine.
- Prefer the standard library or existing dependencies when adequate.
- Do not update unrelated dependencies during a ticket.

## 11. Tests and verification

- Test behavior with meaningful assertions, not private implementation details or call counts alone.
- Prefer unit tests for domain rules, pure functions, mappings, validation and application behavior.
  These should normally run without Spring startup, network, PostgreSQL or Docker.
- Use integration tests for persistence, HTTP, serialization, Spring wiring and real boundaries.
- Extensive mocking of business logic is a reason to reconsider hidden dependencies,
  mixed responsibilities, shared state or infrastructure coupling.
- Test strategies independently; test dispatcher selection and composite orchestration separately
  when useful so failures identify the responsible behavior.
- Coverage is useful information, not proof of correctness.
- Respect the Kotlin compiler, ktlint, detekt, tests and configured Gradle checks.
- Do not disable global rules, comment out failing tests, ignore build failures,
  introduce temporary bypasses or leave unresolved TODOs merely to make checks pass.
- Keep justified suppressions local and explain their reason.

For code changes, verify through the project's reproducible command:

```bash
./gradlew build
```

The build must compile, run configured checks/tests and package the application.
Do not require undocumented preparation or consider implementation finished with a red build.
AI-generated code still requires compiler, static-analysis, test and build verification.
Fix or explicitly report existing failures; do not normalize a broken main branch.

## 12. Before finishing

Confirm correct service/layer ownership, explicit workflows, isolated HTTP/persistence details,
focused scope, readable names, appropriate immutability, visible side effects, no duplicated rules,
no speculative abstractions, preserved diagnostics, external configuration and absence of secrets.
Check relevant tests, independently testable strategies and a green build.
Fix unmet rules or explicitly justify necessary exceptions.
