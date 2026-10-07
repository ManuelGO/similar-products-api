# Similar Products API

## Project

Backend technical challenge implementing the Similar Products API.

The goal is to produce a small, production-quality backend solution that
is easy to understand, test, maintain, and defend during a technical
interview.

## Technology

- Java 17
- Spring Boot
- Maven

Additional technologies, libraries, and frameworks must not be assumed.
They should only be introduced when they solve a concrete requirement
and their inclusion has been justified.

## Source of truth

The challenge specifications are:

- `readme.md`
- `existingApis.yaml`
- `similarProducts.yaml`

These files are the authoritative source for functional requirements
and API contracts.

Do not change these files unless explicitly requested.

Specifications created during development may clarify the original
requirements but must never contradict, extend, or silently reinterpret
them.

If a requirement is ambiguous, identify the ambiguity and present the
assumption before implementing it.

## Development principles

- Prefer simple, readable, and maintainable solutions.
- Apply SOLID principles pragmatically.
- Avoid overengineering.
- Keep infrastructure concerns outside the domain/business logic.
- Keep controllers focused on HTTP concerns rather than business logic.
- Do not introduce dependencies without a concrete justification.
- Follow the supplied REST contract exactly.
- External failures must be handled explicitly.
- Prefer explicit behaviour over hidden framework magic when practical.
- Optimize only where the challenge requirements or measurements justify it.
- Never suppress tests, compiler warnings, or quality-analysis findings
  merely to obtain a clean report.
- Do not introduce abstractions solely to demonstrate design patterns,
  SOLID, DDD, or architectural sophistication.

## Spec-Driven Development

Development must be performed incrementally from reviewed specifications.

Specifications must be stored under:

`specs/`

For each meaningful feature:

1. Read the relevant original challenge requirements.
2. Create or update the corresponding specification under `specs/`.
3. Present the specification for human review.
4. Stop and wait for explicit approval.
5. After approval, produce a short implementation plan.
6. Implement the feature incrementally.
7. Compile the project.
8. Run the tests defined by the specification.
9. Verify every acceptance criterion.
10. Review the resulting code.
11. Mark the specification as implemented only after successful verification.

Each specification should contain, when applicable:

- Goal
- Source requirements
- Expected behaviour
- Acceptance criteria
- Edge cases
- Error/failure cases
- Out-of-scope items
- Technical decisions
- Verification strategy

Specifications should remain concise and proportional to the size of
the feature.

Do not create specifications for trivial implementation details.

The original challenge files remain the source of truth.

Specifications may clarify requirements but must not invent new ones.

## Human approval gates

Explicit human approval is required before:

- implementing a new specification;
- adding a new production dependency;
- introducing a new architectural pattern;
- making a significant architectural decision;
- significantly changing an already approved specification;
- changing behaviour derived from an ambiguity in the original requirements.

Creating, discussing, or reviewing a specification does not imply
approval.

Previous approval of a related feature does not imply approval of a new
feature or architectural decision.

When approval is required, stop and wait for explicit confirmation.

## Implementation workflow

Before implementing a significant change:

1. Identify the requirement or approved specification being addressed.
2. Explain any important technical or architectural decisions.
3. Confirm that required approval has already been given.
4. Implement the smallest meaningful increment.
5. Compile.
6. Run relevant tests.
7. Review the resulting code.
8. Verify that the implementation still conforms to the specification.

Avoid large implementation batches when the work can be divided into
independently understandable increments.

## Learning and decision rationale

For technical decisions involving Java or Spring concepts that are not
already established in the project:

- Explain the concept briefly before using it.
- Explain why it is appropriate for this specific requirement.
- Mention a simpler alternative when relevant.
- Explain relevant trade-offs.
- Prefer concepts that the candidate can understand and defend in a
  technical interview.

Do not introduce a framework, pattern, annotation, abstraction, or
Spring feature only because it is considered common practice.

When proposing something unfamiliar, explanations should assume the
candidate is an experienced software engineer but is relatively new to
the Java/Spring ecosystem.

The objective is not only to produce working code, but also to ensure
that the candidate understands the important decisions behind it.

## Testing and verification

Tests should be derived from requirements and acceptance criteria rather
than implementation details.

For each feature, consider as appropriate:

- successful behaviour;
- boundary and edge cases;
- invalid input;
- upstream failures;
- timeout behaviour;
- contract compliance.

Avoid tests that exist only to increase coverage without validating
meaningful behaviour.

Before considering the complete solution finished:

- Compile the complete project.
- Run the complete test suite.
- Run static analysis.
- Review SonarQube findings.
- Review test coverage.
- Run the performance tests provided by the challenge when applicable.
- Verify the implementation against the original API contracts.
- Review all specifications and confirm their acceptance criteria are met.

## Quality analysis

SonarQube is available through the configured MCP integration.

Use quality analysis as a review tool, not as a substitute for engineering
judgement.

When reviewing findings:

- explain meaningful issues;
- prioritize correctness, maintainability, reliability, and security;
- fix justified findings;
- do not change correct code merely to satisfy a metric;
- do not suppress findings without an explicit technical justification.

Quality metrics and coverage percentages are indicators, not objectives
by themselves.

## Architecture

Do not assume an architectural style before analyzing the requirements.

Architecture must remain proportional to the scope of the challenge.

When proposing an architectural pattern:

1. Identify the concrete problem it solves.
2. Explain why a simpler structure would or would not be sufficient.
3. Explain the cost or complexity introduced.
4. Wait for approval before adopting it.

Do not introduce unnecessary infrastructure such as databases, message
brokers, caches, distributed systems, or additional services unless a
requirement clearly justifies them.

## Dependencies

Every new production dependency must have a concrete purpose.

Before adding one:

1. Explain what problem it solves.
2. Explain whether the Java standard library or existing Spring
   capabilities could solve the same problem.
3. Mention relevant trade-offs.
4. Wait for explicit approval.

Test-only dependencies may be proposed as part of an approved testing
strategy, but their purpose must still be clear.

## AI usage

AI-assisted development is allowed, but generated code must be
understandable and defensible by the candidate.

Do not introduce code, patterns, abstractions, or dependencies solely to
make the solution appear more sophisticated.

Do not implement a technical decision that has not been understood and
approved when approval is required.

When uncertain about a requirement, architecture decision, or trade-off,
surface the uncertainty instead of silently choosing an interpretation.

The final solution should reflect deliberate engineering decisions rather
than unrestricted code generation.