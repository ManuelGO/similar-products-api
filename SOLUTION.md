# Similar Products API — Solution

## 1. Overview

A Spring Boot application (Java 17, Maven) that exposes the agreed
contract (`similarProducts.yaml`) on port 5000:

```
GET /product/{productId}/similar
```

For each request it:

1. calls the existing `GET /product/{productId}/similarids`;
2. fetches `GET /product/{id}` for every similar id **in parallel**, on a
   bounded executor;
3. returns the product details as a JSON array, in the similarity order
   given by the upstream.

No other technologies were added. The only production dependency is
`spring-boot-starter-webmvc`. The HTTP client is Spring's `RestClient`
over the JDK `HttpClient`.

## 2. Build, test and run

**Prerequisites:** JDK 17 or later, and Docker (for the supplied mocks
and performance test). Maven is not needed: the Maven Wrapper is
included.

```bash
# Compile, run the full test suite and produce the coverage report
# (target/site/jacoco/index.html)
./mvnw clean verify

# Start the supplied mocks and performance-test infrastructure
docker compose up -d simulado influxdb grafana   # or: docker-compose ...

# Run the application on port 5000
java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar   # or: ./mvnw spring-boot:run

# Try it
curl -i http://localhost:5000/product/1/similar

# Run the supplied performance test
docker compose run --rm k6 run scripts/test.js
```

> **macOS:** the AirPlay Receiver listens on port 5000 by default, and
> the application then fails to start ("Port 5000 was already in use").
> Disable it in *System Settings → General → AirDrop & Handoff*. The port
> is not changed, because the challenge requires 5000 and the k6 test
> targets it.

### Configuration (`src/main/resources/application.yml`)

| Property | Value | Purpose |
|----------|-------|---------|
| `server.port` | `5000` | Required by the challenge. |
| `product-api.base-url` | `http://localhost:3001` | Upstream Product API (the Simulado mock). |
| `product-api.connect-timeout` | `500ms` | Fail fast when the upstream is unreachable. |
| `product-api.read-timeout` | `1s` | Upper budget for a single upstream lookup. |
| `detail-fetch.pool-size` | `32` | Maximum detail lookups running at once. |
| `detail-fetch.queue-capacity` | `32` | Maximum detail lookups waiting for a thread. |

All values can be overridden with standard Spring Boot mechanisms
(e.g. `--detail-fetch.pool-size=16`).

## 3. Architecture

A deliberately small, hexagonal (ports-and-adapters) structure:

```
SimilarProductsController ─▶ SimilarProductsService ─▶ ProductCatalog (port) ◀─ HttpProductCatalog (adapter)
        web                       application                  domain             infrastructure/productapi
```

| Package | Responsibility |
|---------|----------------|
| `web` | HTTP only. `SimilarProductsController` maps the route. `ApiExceptionHandler` translates domain exceptions into status codes. |
| `application` | `SimilarProductsService`, the use case. It removes duplicate ids, fetches the details concurrently, keeps the order, and decides which failures become omitted products. It has no Spring or HTTP types. |
| `domain` | `Product` (record with a `BigDecimal` price, so prices keep their exact decimal value). `ProductCatalog`, the outbound port. The domain exceptions `ProductNotFoundException`, `ProductCatalogException` and `ProductCatalogTimeoutException`. |
| `infrastructure.productapi` | `HttpProductCatalog`, the `RestClient` adapter. It validates the upstream responses and translates every upstream failure into a domain exception. |
| `config` | Spring wiring for the use case and its bounded executor. |

The port exists for one concrete reason: the use case, which contains the
concurrency and failure policy, can be tested with an in-memory catalogue
and no HTTP. There are no further layers, no web DTOs and no
infrastructure such as a database, cache or message broker; the problem
does not need them.

## 4. Concurrency

- The service starts one `CompletableFuture.supplyAsync(...)` per similar
  id on a dedicated executor, then waits for the results.
- **Order is preserved** because the futures are joined in the original
  id order, whatever order the calls complete in.
- The executor is explicit and **bounded**: a fixed pool of 32 threads
  and a FIFO queue of 32 tasks. When both are full, a new detail task is
  **rejected immediately**. That product is omitted, and the request
  thread never blocks waiting for capacity or runs the call itself.
- The bound protects both the application and the upstream: each inbound
  request fans out into several upstream calls, and without a cap the
  application would amplify its own load against a single service.
- **32 / 32 are bounded defaults for this exercise, not values claimed to
  be optimal for production** (see §9).

## 5. Resilience and error handling

| Situation | Response |
|-----------|----------|
| `similarids` returns `404` (root product unknown) | `404` |
| `similarids` times out before responding | `504` |
| Any other `similarids` failure (`5xx`, other `4xx`, connection error, malformed body, body cut off by the read timeout) | `502` |
| A detail lookup fails (`404`, `5xx`, timeout, malformed body, executor saturation) | That product is **omitted**; the rest return with `200` in order |
| Every detail lookup fails | `200 []` |
| Spring's own request errors (unknown route, unsupported method) | Spring's status (`404`, `405`) |
| Anything unexpected (a bug) | `500`, logged at `ERROR` with stack trace |

- **Error responses have empty bodies.** The contract defines no error
  schema, so the smallest compatible representation is used.
- **Partial results.** The contract does not say what happens when one
  similar product cannot be retrieved. Omitting it keeps the response
  useful for a secondary page section. Each omission is logged at `WARN`
  with the id and reason.
- **Only known upstream failure types are converted into omissions.** A
  programming error still produces a `500` instead of silently
  disappearing as a "missing product".
- **No retries and no circuit breaker.** Retries would multiply load on
  an upstream that is already failing or slow, and would extend latency
  beyond the timeout budget. A circuit breaker would need a library and
  tuning that the requirements do not justify. Each upstream lookup is
  made exactly once (verified by tests).

## 6. Timeouts

- **Connect timeout 500 ms.** This provides a short bound for connection
  establishment so an unreachable upstream fails fast instead of holding
  request resources unnecessarily.
- **Read timeout 1 s.** It is applied to every upstream call through
  `JdkClientHttpRequestFactory`. The values are justified by the nature
  of the lookups (single-key reads that a healthy service answers in
  milliseconds), not by the mock delays.
- **Classification relies only on the exception type.** A
  `java.net.http.HttpTimeoutException` in the cause chain means timeout
  (`504` for `similarids`). Timeouts are never inferred from elapsed
  time or messages. Tests established that if the headers arrive in time
  but the body is cut off by the read timeout, the JDK reports a generic
  I/O error, so that case is classified as a failed response (`502`).
- **No end-to-end request deadline.** Per-call timeouts and a bounded
  queue limit how long work is held, but they are not a strict
  per-request deadline: time spent waiting in the executor queue depends
  on concurrent traffic. A deadline was deliberately not added; see §10.

## 7. Contract assumptions

- **Numeric ids are accepted.** `existingApis.yaml` declares the similar
  ids as strings, but the supplied mock returns numbers (`[2,3,4]`). Both
  forms are read as string ids.
- **`similarids` `404` means the root product does not exist**, and the
  API responds `404`. The existing API does not document this status
  explicitly.
- **Similarity order is preserved exactly** as returned by `similarids`.
- **Duplicate similar ids are removed, keeping the first occurrence.**
  This guarantees the contract's `uniqueItems` and avoids duplicate
  upstream calls.
- **Required fields are validated.** An upstream product with a missing
  `id`, `name`, `price` or `availability` field, or a blank `id` or
  `name`, is treated as malformed and omitted. The response never
  contains an item that violates the contract schema.

## 8. Testing and quality

56 automated tests, run by `./mvnw clean verify`:

| Test | Scope |
|------|-------|
| `SimilarProductsServiceTest` | Use-case rules with an in-memory catalogue: order, duplicates, empty list, each omission type, error propagation, `WARN` logging. |
| `SimilarProductsServiceConcurrencyTest` | Calls really run in parallel; concurrency never exceeds the pool size; saturation rejects without blocking. Uses latches rather than timing assumptions. |
| `HttpProductCatalogTest` | Adapter against a real HTTP server (WireMock): string and numeric ids, exact prices, URL encoding, `404`/`4xx`/`5xx`, timeout vs. truncated body, connection refused/reset, malformed bodies, exactly one request per lookup. |
| `SimilarProductsControllerTest` | HTTP mapping: exact JSON shape, `404`/`502`/`504`/`500` with empty bodies, Spring `404`/`405` preserved. |
| `SimilarProductsEndToEndTest` | The whole application over HTTP against WireMock replaying the challenge mock scenarios. |

**SonarCloud** (local Maven analysis with `sonar-maven-plugin`
5.8.0.7211, project `ManuelGO_similar-products-api`):

- Quality Gate **passed**;
- 0 bugs, 0 vulnerabilities, 0 security hotspots, 0 code smells, 0.0%
  duplication; all ratings A;
- coverage 98.7% overall, 100% of branches.

The only uncovered lines are the body of the `main` method. No test was
added only to raise the percentage.

## 9. Performance evaluation

The unmodified challenge k6 test was run against the application on port
5000. Every top-level response in the supplied k6 scenarios was `200`,
with no unexpected `5xx`.

**Baseline, 32 / 32.** The fast scenarios (`normal`, `notFound`,
`error`) were served quickly, but a significant share of detail lookups
were rejected by the saturated executor. In the slow scenarios (`slow`,
`verySlow`) most lookups were rejected, because threads were held by the
deliberately delayed products until the read timeout.

**Experiment, 64 / 64.** Doubling the pool and the queue returned more
products per request in the `normal` scenario (2.56 vs 1.82 of 3).
However, total throughput fell (13,394 vs 16,890 requests) and latency
rose in every fast scenario (`normal` p50 267 ms vs 111 ms). It produced
only a small completeness improvement in `slow` and no useful
improvement in `verySlow`, at the cost of additional waiting and timeout
work.

**Diagnosis.** A diagnostic run sampled CPU, Docker, and JVM thread
states, and showed two different bottlenecks:

- **Fast scenarios:** the supplied Simulado mock was pinned at about one
  full CPU core. Its own response time rose from ~12 ms idle to
  hundreds of milliseconds, while the JVM and the host had spare CPU.
  More application concurrency only adds pressure on an upstream that
  cannot go faster.
- **Slow scenarios:** the mock was not saturated. The limit was the
  application's bounded, blocking executor, whose threads waited on
  deliberately delayed products.

**Decision.** 32 / 32 was retained as a conservative bounded
configuration. The evidence shows that a larger pool does not improve
this benchmark overall; it does not show that 32 / 32 is optimal.

Absolute figures are indicative only. The application, mocks, InfluxDB
and k6 ran on one Apple Silicon laptop, and the supplied amd64 k6 image
ran under emulation. The comparison between runs on the same machine is
what supports the decision.

## 10. Known limitations and production considerations

- **Partial results under heavy slow-upstream load.** When many upstream
  calls are slow, the bounded executor saturates and rejects lookups, so
  responses omit products even when the upstream would eventually answer.
  This is a deliberate overload-protection trade-off, not a desirable
  outcome.
- **No overall request deadline** (§6). It could be added if production
  latency objectives require one.
- **Sizing.** Production pool, queue and timeout values should be derived
  from the real workload and the upstream's capacity and latency, not from
  this benchmark.
- **Monitoring.** In production, track upstream latency and error rate,
  executor saturation (active threads, queue depth, rejections), and the
  partial-result rate.
- **Concurrency model.** If a sustained workload needs many more
  simultaneous slow I/O operations, Java 21 virtual threads or
  non-blocking I/O could be evaluated. Both are intentionally outside this
  Java 17 exercise.
- **Not implemented, by design:** caching, retries, circuit breaker and
  metrics endpoints. None is required by the challenge, and each adds
  behaviour that would need its own justification and tuning.
