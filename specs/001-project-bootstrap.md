# SPEC-001 — Project bootstrap

Status: **Implemented** (manual port-5000 smoke test completed during SPEC-004 Part 1, after AirPlay Receiver was disabled: the packaged jar ran on port 5000 with its default configuration and served the k6 test with only `200` responses. That superseded the bootstrap-era expectation of a `404`.)

## Goal

Create the smallest runnable Spring Boot application that the following
specs can build on: it compiles, starts on port 5000, and runs an
automated test and coverage report through a single Maven command.

No business behaviour is delivered by this spec.

## Source requirements

- `readme.md`: "Create a Spring boot application that exposes the agreed
  REST API on port 5000."
- `CLAUDE.md`: Java 17, Spring Boot, Maven; no dependency without a
  concrete justification; test coverage must be reviewable.

## Expected behaviour

- `./mvnw verify` compiles the project, runs the test suite and produces a
  coverage report.
- `./mvnw spring-boot:run` starts the application listening on HTTP port
  5000.
- No endpoint is exposed yet. Any request is answered by Spring Boot's
  default error handling (e.g. `404` for `GET /product/1/similar`).

## Acceptance criteria

1. **AC1** — `./mvnw verify` succeeds on a clean checkout with JDK 17.
2. **AC2** — The build targets Java 17 (`java.version` = 17).
3. **AC3** — A context-load test starts the Spring application context
   successfully.
4. **AC4** — The application is configured to use port 5000
   (`server.port`), and an automated test verifies this configuration
   while the Spring context starts successfully. Starting the application
   and calling it on port 5000 is a **manual smoke test** and is not
   covered by `./mvnw verify`.
5. **AC5** — `./mvnw verify` generates a JaCoCo HTML report at
   `target/site/jacoco/index.html`. No minimum coverage threshold is
   enforced.
6. **AC6** — The only dependencies are the ones listed under *Technical
   decisions*.
7. **AC7** — Build output (`target/`) is not tracked by git (already
   covered by `.gitignore`).
8. **AC8** — Original challenge files (`readme.md`, `existingApis.yaml`,
   `similarProducts.yaml`, `docker-compose.yaml`, `shared/`) are
   unchanged.

## Edge cases

- **Port 5000 already in use.** On macOS the AirPlay Receiver listens on
  port 5000 by default. Startup then fails with "Port 5000 was already in
  use". This is an environment issue, not something the application
  should work around: the challenge requires port 5000, so the port is not
  silently changed. The fix (disabling AirPlay Receiver in System Settings
  → General → AirDrop & Handoff) will be documented in the solution README
  (SPEC-004).

## Error / failure cases

- Running with a JDK older than 17 fails at compile time. This is
  intended.
- No runtime failure handling applies: there is no business logic yet.

## Out of scope

- The `/product/{productId}/similar` endpoint, domain model, port,
  adapter, and upstream configuration (base URL, timeouts) — SPEC-002 and
  SPEC-003. The upstream base URL is not added here because nothing would
  read it yet.
- RestClient, WireMock and `@WebMvcTest` support — added by the spec that
  first needs them.
- Executor / concurrency configuration — SPEC-003.
- SonarCloud analysis configuration — SPEC-004.
- Actuator, Dockerfile for the application, logging configuration.
- Solution README — SPEC-004.

## Technical decisions

### Spring Boot 4.1.1 (latest stable at time of writing)

- Spring Boot 4.x supports Java 17 as its minimum, so it meets the
  project constraint.
- The Spring Boot 3.x line is older; starting a new project on it gives
  no benefit here.
- Consequence: Boot 4 uses Jackson 3 and *modular starters*. The web
  starter is now `spring-boot-starter-webmvc`; the older
  `spring-boot-starter-web` still exists but is deprecated in its favour.

### Maven coordinates

| Item        | Value                                  |
|-------------|----------------------------------------|
| groupId     | `com.manuelgo`                         |
| artifactId  | `similar-products-api`                 |
| Base package| `com.manuelgo.similarproducts`         |
| Parent      | `spring-boot-starter-parent` 4.1.1     |

The parent POM manages dependency and plugin versions, so individual
Spring dependencies are declared without versions.

### Dependencies

| Scope | Artifact | Why |
|-------|----------|-----|
| compile | `spring-boot-starter-webmvc` | Provides Spring MVC, embedded Tomcat and JSON support required to expose the HTTP API. HTTP client dependencies will be evaluated by the specification that introduces upstream communication. |
| test | `spring-boot-starter-test` | JUnit 5, AssertJ, Mockito and Spring test support. Needed for AC3 and every later spec. |

### Build plugins

| Plugin | Why |
|--------|-----|
| `spring-boot-maven-plugin` (version from parent) | Builds the executable jar and provides `spring-boot:run`. |
| `jacoco-maven-plugin` 0.8.15 | Produces the coverage report that `CLAUDE.md` requires us to review and that SonarCloud reads later. Neither the JDK nor Spring Boot provides coverage measurement. Configured with `prepare-agent` + `report` only; **no coverage threshold**, because coverage is a review indicator, not a target. |

### Maven Wrapper (`mvnw`, `.mvn/wrapper/`)

A small script that downloads and uses a pinned Maven version (3.9.11).
It lets reviewers build without installing a matching Maven. It is not
an application dependency and adds no code to the artifact.
Alternative: require a local Maven install — simpler repository, less
reproducible.

### Configuration

- `src/main/resources/application.yml` containing only `server.port: 5000`.
- YAML is chosen over `.properties` because later specs add nested
  settings (upstream base URL, timeouts, concurrency) that read more
  clearly as a tree. Both formats are equally supported.

### Source layout

- Entry point: `com.manuelgo.similarproducts.SimilarProductsApplication`
  (`@SpringBootApplication` + `main`).
- `SimilarProductsApplicationTests` with a single context-load test that
  also asserts the configured port (AC4).
- Sub-packages (`domain`, `application`, `infrastructure`, `web`) are
  created by SPEC-002 when they receive their first class, not as empty
  placeholders.

## Verification strategy

| AC | How |
|----|-----|
| AC1, AC3, AC5 | Run `./mvnw clean verify`; check the build succeeds, the context test passes and `target/site/jacoco/index.html` exists. |
| AC2 | Inspect `pom.xml` (`java.version`). |
| AC4 | Automated: the context-load test asserts that the resolved `server.port` is `5000`. Manual smoke test (not part of `./mvnw verify`): start with `./mvnw spring-boot:run`; `curl -i http://localhost:5000/product/1/similar` returns a `404` from the application. |
| AC6 | `./mvnw dependency:tree` shows only the two declared starters and their transitive dependencies. |
| AC7, AC8 | `git status` / `git diff main --stat` after implementation. |
