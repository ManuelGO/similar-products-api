---
name: backend-quality
description: Review and implement the Backend Inditex challenge following its quality requirements.
---

# Backend Challenge Quality Skill

Use this skill when designing, implementing or reviewing the backend challenge.

## Primary goals

The solution must prioritize:

- Clear and maintainable code
- SOLID principles
- REST API best practices
- Resilience
- Performance
- Pragmatic DDD
- Testability

## Architecture

Prefer a lightweight Hexagonal Architecture:

Controller
    ↓
Application / Use Case
    ↓
Domain Port
    ↓
Infrastructure Adapter
    ↓
External Product API

Rules:

- Domain must not depend on Spring.
- Domain must not know HTTP details.
- Controllers must contain no business logic.
- External API access must be behind a port.
- Avoid unnecessary abstractions.
- Do not introduce infrastructure that the problem does not require.

## REST

Implement the supplied OpenAPI contract exactly.

Do not modify response structures without justification.

Map domain/application failures explicitly to HTTP responses.

## External API

External calls must:

- have explicit timeouts
- handle 404 explicitly
- distinguish transient from permanent failures
- retry only transient failures
- use bounded retries
- avoid retry storms

Consider circuit breaker only when justified.

## Performance

Similar product details should not be retrieved sequentially when they
can safely be retrieved concurrently.

Avoid unbounded concurrency.

## Testing

Before considering a task complete:

1. Compile the project.
2. Run unit tests.
3. Run integration tests.
4. Run static analysis if available.
5. Review changed code.

Test at minimum:

- successful similar-product retrieval
- product not found
- upstream failure
- timeout
- malformed/unexpected upstream response where relevant

## SOLID review

Before completing implementation, explicitly check:

- SRP
- OCP where useful
- LSP
- ISP
- DIP

Do not create abstractions solely to claim SOLID compliance.

## DDD

Use DDD pragmatically.

Keep domain concepts independent from infrastructure.

Do not introduce aggregates, repositories, value objects or domain
services unless they model an actual domain concept.

## Final review

Before declaring the challenge complete, review:

- architecture
- readability
- unnecessary complexity
- exception handling
- concurrency
- resilience
- tests
- REST contract
- static-analysis findings

Never suppress warnings simply to obtain a clean quality report.