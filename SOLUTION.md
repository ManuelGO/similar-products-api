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

Successful upstream answers are kept in a short-lived in-process cache
(§5), so repeated lookups do not reach the upstream.

The production dependencies are `spring-boot-starter-webmvc` and
Caffeine (the in-process cache). The HTTP client is Spring's `RestClient`
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
| `product-cache.details.ttl` | `30s` | How long a product detail is reused. |
| `product-cache.details.max-size` | `10000` | Maximum cached product details. |
| `product-cache.similar-ids.ttl` | `5m` | How long a similar-ids list is reused. |
| `product-cache.similar-ids.max-size` | `10000` | Maximum cached similar-ids lists. |

All values can be overridden with standard Spring Boot mechanisms
(e.g. `--detail-fetch.pool-size=16`).

## 3. Architecture

A deliberately small, hexagonal (ports-and-adapters) structure:

```
SimilarProductsController ─▶ SimilarProductsService ─▶ ProductCatalog (port)
        web                       application                  domain
                                                                 ▲
                                          CachingProductCatalog ─┘ ──delegates──▶ HttpProductCatalog
                                          infrastructure/cache                    infrastructure/productapi
```

| Package | Responsibility |
|---------|----------------|
| `web` | HTTP only. `SimilarProductsController` maps the route. `ApiExceptionHandler` translates domain exceptions into status codes. |
| `application` | `SimilarProductsService`, the use case. It removes duplicate ids, fetches the details concurrently, keeps the order, and decides which failures become omitted products. It has no Spring or HTTP types. |
| `domain` | `Product` (record with a `BigDecimal` price, so prices keep their exact decimal value). `ProductCatalog`, the outbound port. The domain exceptions `ProductNotFoundException`, `ProductCatalogException` and `ProductCatalogTimeoutException`. |
| `infrastructure.productapi` | `HttpProductCatalog`, the `RestClient` adapter. It validates the upstream responses and translates every upstream failure into a domain exception. |
| `infrastructure.cache` | `CachingProductCatalog`, a decorator of the port that reuses successful answers of the HTTP adapter (§5). |
| `config` | Spring wiring: the use case, its bounded executor, and the single `ProductCatalog` bean (the cache wrapping the HTTP adapter). |

The port has two concrete uses:
- the use case, which contains the concurrency and failure policy, can be
  tested with an in-memory catalogue and no HTTP;
- the cache wraps the HTTP adapter without the use case knowing about it.

There are no further layers, no web DTOs and no external infrastructure
such as a database, distributed cache or message broker; the problem does
not need them.

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
  be optimal for production** (see §10).

## 5. Caching

`CachingProductCatalog` keeps **successful** upstream answers in memory
for a bounded time.

| | Product details | Similar ids |
|---|---|---|
| TTL (from when the answer was obtained) | 30 s | 5 min |
| Maximum entries | 10,000 | 10,000 |

- **Why these TTLs.** Stock (`availability`) is the most volatile field.
  The API feeds a "similar products" section, not checkout, so up to 30 s
  of staleness is acceptable. Similarity lists change far less often. The
  values were chosen for the domain, not for the benchmark.
- **Failures are never cached** (not found, upstream error, timeout,
  malformed response). They propagate unchanged, and the next lookup asks
  the upstream again.
- **Concurrent misses are coalesced.** While a lookup for a key is in
  flight, other requests for the same key wait for that one result
  instead of each calling the upstream. The upstream call runs outside any
  lock, so a slow product never blocks lookups of other products.
- **Cached lists are immutable**, because one value is shared by every
  request until it expires.
- **Explicit, not `@Cacheable`.** The cache is a plain decorator of the
  port, wired in one `@Bean`. Spring's annotation-driven caching was not
  used:
  - it works through proxies, so calls from inside the same class
    silently bypass the cache;
  - its coalescing mode (`sync = true`) runs the upstream call inside
    Caffeine's compute lock, which is the blocking this design avoids.
- **Caffeine** provides expiry and the size bound. Its version is managed
  by the Spring Boot parent. Caffeine's `AsyncCache` was not used,
  because it logs a stack trace for every failed load, and in this API
  failed loads are a normal outcome. A small map of in-flight loads
  provides the coalescing instead.

## 6. Resilience and error handling

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

## 7. Timeouts

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
  on concurrent traffic. A deadline was deliberately not added; see §11.

## 8. Contract assumptions

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

## 9. Testing and quality

76 automated tests, run by `./mvnw clean verify`:

| Test | Scope |
|------|-------|
| `SimilarProductsServiceTest` | Use-case rules with an in-memory catalogue: order, duplicates, empty list, each omission type, error propagation, `WARN` logging. |
| `SimilarProductsServiceConcurrencyTest` | Calls really run in parallel; concurrency never exceeds the pool size; saturation rejects without blocking. Uses latches rather than timing assumptions. |
| `HttpProductCatalogTest` | Adapter against a real HTTP server (WireMock): string and numeric ids, exact prices, URL encoding, `404`/`4xx`/`5xx`, timeout vs. truncated body, connection refused/reset, malformed bodies, exactly one request per lookup. |
| `SimilarProductsControllerTest` | HTTP mapping: exact JSON shape, `404`/`502`/`504`/`500` with empty bodies, Spring `404`/`405` preserved. |
| `CachingProductCatalogTest` | Cache rules with a controllable clock: reuse within the TTL, reload after it, failures never cached and propagated unchanged, one upstream call for concurrent lookups of a key (its failure, including an `Error`, reaching every waiter unchanged), no blocking across keys, immutable shared lists. |
| `SimilarProductsEndToEndTest` | The whole application over HTTP against WireMock replaying the challenge mock scenarios (with the cache disabled, so each test reaches the upstream). |
| `ProductCacheEndToEndTest` | The whole application with the configured cache: repeated requests call each upstream URL once; failed lookups are not cached. |

**SonarCloud** (local Maven analysis with `sonar-maven-plugin`
5.8.0.7211, project `ManuelGO_similar-products-api`, including the cache):

- Quality Gate **passed** (94.8% coverage on new code);
- 0 bugs, 0 vulnerabilities, 0 security hotspots, 0 code smells, 0.0%
  duplication; all ratings A;
- coverage 97.5% overall (98.0% of lines, 95.7% of branches).

The uncovered code is the body of the `main` method and two paths in
`CachingProductCatalog`: finding the value already cached in the narrow
window between the first cache check and claiming the load, which cannot be
exercised deterministically without test hooks in production code; and the
final `throw` for a checked cause in `join`, which the compiler requires but
cannot be reached because loads only fail with a `RuntimeException` or an
`Error`. No test was added only to raise the percentage.

## 10. Performance evaluation

The unmodified challenge k6 test was run against the application on port
5000. Absolute figures are indicative only: the application, mocks,
InfluxDB and k6 ran on one Apple Silicon laptop, and the supplied amd64 k6
image ran under emulation. What supports the decisions is the comparison
between runs on the same machine.

### Resilience and cache, before and after

Three runs in the same session:
- **R0:** the first working version, without timeouts, failure handling
  or a bounded queue;
- **R1:** with the resilience described in §6–§7;
- **R2:** R1 plus the cache.

| Scenario | R0 (no resilience) | R1 (resilience) | R2 (resilience + cache) |
|----------|--------------------|-----------------|-------------------------|
| `normal` | p50 150 ms | p50 142 ms, 2.07 of 3 products | **p50 56 ms, 3 of 3** |
| `notFound` | **`404` for the whole response** | p50 132 ms, 1.48 of 2 | **p50 6 ms, 2 of 2** |
| `error` | **`500` for the whole response** | p50 148 ms, 1.36 of 2 | **p50 25 ms, 2 of 2** |
| `slow` | **9 req/s, p50 11 s** | 309 req/s, 0.16 of 3 | 338 req/s, 0.06 of 3 |
| `verySlow` | **every request aborted by k6** | 354 req/s, 0 of 3 | 332 req/s, 0 of 3 |

"x of y products" is the average number of products returned per
response. y is the most the mocks allow, because the remaining products
always fail.

- **Resilience (R0 → R1).** Without timeouts, slow products held
  threads for up to 50 s, and one failing similar product turned the
  whole response into a `404` or `500`. With timeouts and partial
  results, every request returned `200` at more than 300 req/s in every
  scenario.
- **Cache (R1 → R2), fast scenarios.** These scenarios were limited by
  the mock, which saturated about one CPU core (see below). The cache
  removes almost all upstream calls:
  - executor rejections fell from about 9,600 to 0;
  - every response is complete;
  - median latency fell 2.5–20×.
- **Cache (R1 → R2), slow scenarios.** No improvement, as expected.
  Products that always time out are never cached, and every lookup still
  holds an executor thread for up to the read timeout. A cache hit also
  needs a free executor slot, so even cached products are rejected while
  the pool is full of slow calls.
- **Hit-rate caveat.** The k6 test touches only about 13 distinct
  upstream URLs, so the cache hit rate is close to 100%. The improvement
  is real for this workload; it is not a prediction for production.

### Executor sizing

An earlier comparison of pool and queue sizes (without the cache) tested
doubling them from 32 / 32 to 64 / 64:
- it returned more products in `normal` (2.56 vs 1.82 of 3);
- but it lowered total throughput (13,394 vs 16,890 requests) and raised
  latency in every fast scenario (`normal` p50 267 ms vs 111 ms);
- it gave only a small completeness gain in `slow` and none in
  `verySlow`, at the cost of additional waiting and timeout work.

A diagnostic run showed two different bottlenecks:
- **Fast scenarios:** the Simulado mock was pinned at about one CPU core,
  and its own latency rose from ~12 ms to hundreds of ms. More
  application concurrency only added pressure on it.
- **Slow scenarios:** the application's bounded, blocking executor was
  the limit, with its threads waiting on deliberately delayed products.

32 / 32 was therefore kept as a conservative bounded configuration, not as
a claimed optimum.

## 11. Known limitations and production considerations

- **Partial results under heavy slow-upstream load.** When many upstream
  calls are slow, the bounded executor saturates and rejects lookups, so
  responses omit products even when the upstream would eventually answer.
  This is a deliberate overload-protection trade-off, not a desirable
  outcome.
- **No overall request deadline** (§7). It could be added if production
  latency objectives require one.
- **Sizing.** Production pool, queue and timeout values should be derived
  from the real workload and the upstream's capacity and latency, not from
  this benchmark.
- **Monitoring.** In production, track upstream latency and error rate,
  executor saturation (active threads, queue depth, rejections), the
  partial-result rate and the cache hit rate.
- **Concurrency model.** If a sustained workload needs many more
  simultaneous slow I/O operations, Java 21 virtual threads or
  non-blocking I/O could be evaluated. Both are intentionally outside this
  Java 17 exercise.
- **Cache.**
  - It is per instance and in memory: several instances would each keep
    their own copy.
  - Prices and stock can be up to 30 s stale.
  - Failed lookups are not cached, so a product that keeps failing is
    requested again every time and holds an executor thread while it does.
  - Its effectiveness in production depends on the real hit rate, which
    should be monitored.
- **Not implemented, by design:** retries, circuit breaker and metrics
  endpoints. None is required by the challenge, and each adds behaviour
  that would need its own justification and tuning.
