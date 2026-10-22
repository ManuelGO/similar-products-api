# SPEC-002 — Similar products retrieval

Status: **Implemented**

> **Later change:** caching, listed below as out of scope, was added afterwards
> by SPEC-005 (successful lookups) and SPEC-006 (known failures), each with
> its own approval. The behaviour specified here is unchanged.

## Goal

Expose `GET /product/{productId}/similar` as defined in
`similarProducts.yaml`. It retrieves the similar product ids from the
upstream Product API, fetches every product detail **concurrently** on a
bounded executor, and returns the details in similarity order.

This spec delivers the successful path and the "product not found" path.
Timeouts, upstream failure handling and partial results are SPEC-003.

## Source requirements

- `similarProducts.yaml`:
  - `GET /product/{productId}/similar` → `200` array of `ProductDetail`,
    ordered by similarity, `minItems: 0`, `uniqueItems: true`;
  - `404` "Product Not found";
  - `ProductDetail` = `id` (string, minLength 1), `name` (string, minLength 1),
    `price` (number), `availability` (boolean), all required.
- `existingApis.yaml`:
  - `GET /product/{productId}/similarids` → `200` array of unique ids,
    ordered by similarity;
  - `GET /product/{productId}` → `200` `ProductDetail` or `404`.
- `readme.md` / diagram: the application calls the existing APIs at
  `localhost:3001`.
- Approved analysis decisions: ports-and-adapters with `ProductCatalog`
  as the outbound port, `Product` record, `BigDecimal` price, no web DTO,
  RestClient, `CompletableFuture`, bounded and configurable concurrency,
  404 when the requested product cannot be found, smallest error
  representation (no `ProblemDetail`).

## Expected behaviour

1. Call `GET {base-url}/product/{productId}/similarids`.
2. If that call returns `404`, respond `404` with an empty body.
3. Otherwise remove duplicate ids, keeping the first occurrence, so the
   response satisfies `uniqueItems`.
4. Fetch `GET {base-url}/product/{id}` for every remaining id
   concurrently on the dedicated executor.
5. Respond `200` with a JSON array of the products in the same order as
   the ids returned by upstream.
6. An empty id list responds `200 []` without calling the detail
   endpoint.

Failures other than the explicitly supported requested-product 404 path
are outside the behavioural contract of SPEC-002. SPEC-002 does not define
or test their HTTP representation. SPEC-003 introduces the explicit
upstream failure policy.

Likewise, behaviour when more detail requests are pending than the pool
has threads (saturation, queueing, backpressure) is not part of SPEC-002's
contract; SPEC-003 defines it.

## Acceptance criteria

1. **AC1** — `GET /product/1/similar`, with upstream ids `["2","3","4"]`,
   returns `200` and the three products in that order.
2. **AC2** — Each item has exactly the fields `id`, `name`, `price`,
   `availability` with the contract types. `price` keeps its exact decimal
   value (e.g. `9.99` stays `9.99`).
3. **AC3** — When `similarids` returns `404`, the response is `404` with
   an empty body.
4. **AC4** — When `similarids` returns `[]`, the response is `200 []` and
   no detail request is made.
5. **AC5** — Duplicate upstream ids produce one item each, in the
   position of their first occurrence.
6. **AC6** — Numeric upstream ids (`[2,3,4]`, as served by the challenge
   mocks) are accepted the same way as string ids.
7. **AC7** — Product details are fetched concurrently: retrieving N
   details does not wait for one to finish before starting the next.
8. **AC8** — The number of detail requests running concurrently is
   bounded by a configurable pool size (`detail-fetch.pool-size`).
9. **AC9** — The upstream base URL comes from configuration, defaulting
   to `http://localhost:3001`.
10. **AC10** — The `productId` path value is passed to upstream
    URL-encoded (through URI templates), never concatenated into the URL.
11. **AC11** — The domain and application packages do not depend on
    Spring or HTTP types.

## Edge cases

- **Empty similar ids** → `200 []` (AC4).
- **Duplicate ids** → deduplicated, order kept (AC5).
- **Numeric ids** from the mocks → accepted (AC6).
- **Requested id appears in its own similar list** → returned like any
  other id; the contract does not forbid it.
- **Special characters in `productId`** → URL-encoded towards upstream
  (AC10).

## Error / failure cases

| Case | Behaviour in this spec |
|------|------------------------|
| `similarids` → `404` | `404`, empty body |
| Any other upstream failure (detail `404`, `5xx`, connection error, timeout, malformed response) | Outside SPEC-002's behavioural contract; not defined or tested here. Defined by SPEC-003. |
| Executor saturation | Outside SPEC-002's behavioural contract. Defined by SPEC-003. |

## Out of scope

- Timeouts, failure classification, `502`/`504`, partial results → SPEC-003.
- Executor queue capacity, saturation and backpressure policy → SPEC-003.
- Performance measurement and tuning → SPEC-004.
- Validating required fields of upstream products → SPEC-003 (malformed
  responses are a failure case).
- Caching, retries, circuit breaker (rejected in the analysis).

## Technical decisions

### Package structure

```
com.manuelgo.similarproducts
├── domain
│   ├── Product                     record(id, name, price: BigDecimal, availability)
│   ├── ProductCatalog              outbound port
│   └── ProductNotFoundException
├── application
│   └── SimilarProductsService      use case: ids → dedupe → concurrent fetch → ordered list
├── infrastructure/productapi
│   ├── HttpProductCatalog          RestClient adapter implementing ProductCatalog
│   ├── ProductApiResponse          upstream JSON shape, mapped to Product
│   ├── ProductApiProperties        base URL configuration
│   └── ProductApiConfiguration     RestClient bean
├── web
│   ├── SimilarProductsController   HTTP mapping only
│   └── ApiExceptionHandler         ProductNotFoundException → 404, empty body
└── config
    └── ApplicationConfiguration    bounded executor for detail retrieval + use-case wiring
```

### Port

```java
public interface ProductCatalog {
    List<String> findSimilarIds(String productId); // throws ProductNotFoundException
    Product findById(String productId);            // throws ProductNotFoundException
}
```

Both methods are blocking and synchronous. The use case owns the
concurrency, so the port stays simple and the adapter knows nothing about
threads.

### Upstream DTO (`ProductApiResponse`)

A record in the adapter package that mirrors the upstream JSON and is
mapped to `Product`. This keeps the domain record independent of the
upstream format, and gives SPEC-003 one place to validate malformed
responses. Cost: one small record and a mapping method. The web layer
still returns `Product` directly (approved: no web DTO).

### HTTP client: RestClient without `spring-boot-starter-restclient`

- `RestClient` is part of `spring-web`, which `spring-boot-starter-webmvc`
  already brings in.
- `spring-boot-starter-restclient` adds Boot auto-configuration: a shared
  `RestClient.Builder` bean and `spring.http.clients.*` properties for
  timeouts and client selection. We need neither: the adapter builds one
  RestClient explicitly with its own base URL, and SPEC-003 configures
  timeouts explicitly on that client.
- The request factory is set explicitly to `JdkClientHttpRequestFactory`,
  which uses the JDK's `java.net.http.HttpClient`. It pools connections
  and supports connect and read timeouts, so no extra HTTP library is
  needed.
- The JDK `HttpClient` is pinned to **HTTP/1.1**. The challenge upstream
  (Simulado) only speaks HTTP/1.1, while WireMock accepts the JDK client's
  default HTTP/2 upgrade. Both work with the default (verified
  experimentally); pinning keeps integration tests and the real challenge
  environment on comparable connection semantics.
- **No new production dependency.**
- Trade-off: the RestClient uses Spring Framework's default message
  converters rather than the JSON mapper configured by Spring Boot. Their
  behaviour is **not assumed to be equivalent**. The adapter's actual JSON
  conversion behaviour is verified by `HttpProductCatalogTest`, especially
  the challenge-specific case where similar ids are served as numbers even
  though the OpenAPI contract declares strings (AC6), and exact decimal
  prices (AC2).

### Configuration properties

```yaml
product-api:
  base-url: http://localhost:3001
detail-fetch:
  pool-size: 32
```

Bound to typed records with `@ConfigurationProperties`. This is a Spring
Boot feature that maps a YAML subtree to a Java record and fails at
startup if a value has the wrong type. The simpler alternative is
`@Value("${...}")` on each field; it works, but scatters keys across
classes and gives no single place to see the settings.

### Concurrency

- `SimilarProductsService` receives a `java.util.concurrent.Executor`
  through its constructor. It starts one
  `CompletableFuture.supplyAsync(() -> catalog.findById(id), executor)`
  per id, then `join()`s the futures **in the original id order**. That
  `join` order is what preserves similarity order, whatever order the
  calls finish in.
- The request thread waits while the pool threads make the HTTP calls.
  Each request thread handles one inbound request, so total wait time is
  roughly the slowest single call instead of the sum of all calls.
- The executor is Spring's `ThreadPoolTaskExecutor`. It is a thin wrapper
  over the JDK `ThreadPoolExecutor` that takes `corePoolSize`,
  `maxPoolSize`, `queueCapacity` and a thread name prefix as plain
  setters, and is shut down by Spring when the application stops. The
  service only sees the JDK `Executor` interface, so the application
  layer stays free of Spring. Alternative: build a JDK
  `ThreadPoolExecutor` directly with a `LinkedBlockingQueue(capacity)` and
  a custom `ThreadFactory`; same behaviour, slightly more code.
- **Fixed pool**: core = max = `pool-size`. A fixed size is easier to
  reason about than a pool that grows and shrinks. It is also what bounds
  concurrency: at most `pool-size` detail requests run at the same time,
  whatever the queue does.

### Queue capacity: no configuration in SPEC-002

- **What SPEC-002 needs:** only a bound on how many detail requests run
  at once. The pool size provides that bound by itself. The queue does not
  limit concurrency; it only decides what happens to requests *waiting*
  for a free thread.
- **What the queue controls:** how long work can wait before it starts,
  and when work is refused. A large queue hides queueing latency, because
  a detail request can wait in the queue before its HTTP timeout even
  starts. A small or zero queue turns load spikes into rejections. Both
  are saturation and backpressure decisions, which belong to SPEC-003.
- **Minimum configuration for SPEC-002:** none. SPEC-002 adds no
  `queue-capacity` property and leaves `ThreadPoolTaskExecutor`'s queue at
  its default, which is effectively unbounded. That is a stated
  **non-decision**, not a policy: SPEC-002 neither relies on it nor tests
  it, and SPEC-003 replaces it with an explicit queue bound and rejection
  behaviour.

### Initial pool size: 32 threads

Rationale, independent of the k6 scenarios:

- **The work is I/O-bound.** Threads spend almost all their time waiting
  for the upstream response, so the pool should be sized well above the
  CPU core count; CPU-bound sizing (≈ cores) would leave throughput
  unused.
- **The bound protects the upstream.** Every inbound request fans out
  into several detail calls. Without a cap, the application would
  multiply its own inbound load against a single upstream service. 32 is
  a moderate number of simultaneous connections to one service, and an
  order of magnitude below Tomcat's own default of 200 request threads,
  so detail calls queue up before they overwhelm the upstream.
- **The cost is small.** Idle platform threads cost little; 32 is far
  from any memory concern.
- The value is configurable and **explicitly provisional**: SPEC-004
  measures and tunes it.

### Error mapping

`ApiExceptionHandler` (`@RestControllerAdvice`, a Spring class that
centralises exception → response mapping for all controllers) maps
`ProductNotFoundException` to `404` with an empty body, the smallest
representation compatible with the contract. Controllers stay free of
try/catch.

## Verification strategy

### New test dependencies (test scope only)

| Artifact | Why |
|----------|-----|
| `org.wiremock:wiremock-standalone` 3.13.2 | Real HTTP server for adapter and end-to-end tests (approved in the analysis). The *standalone* artifact shades its own Jetty and Jackson 2, so they cannot clash with the application's Tomcat and Jackson 3 classpath. Latest stable; the 4.x line is still in beta. |
| `spring-boot-starter-webmvc-test` | Boot 4 moved `@WebMvcTest` and MockMvc auto-configuration into this per-technology test starter; `spring-boot-starter-test` no longer includes them. |

### Tests

| Test | Type | Covers |
|------|------|--------|
| `SimilarProductsServiceTest` | Unit, in-memory fake `ProductCatalog`, direct executor | AC1 order, AC4 empty, AC5 dedupe, AC3 not-found propagation |
| `SimilarProductsServiceConcurrencyTest` | Unit, real pool | AC7: the fake catalog blocks every call on a `CountDownLatch` until all N calls have started. It can only complete if the calls run in parallel, so no timing assumptions are needed. AC8: with a pool smaller than the number of ids, the fake catalog records the maximum number of calls in flight at once, which must not exceed the pool size. |
| `HttpProductCatalogTest` | WireMock | similarids parsing for string and numeric ids (AC6), `404` → `ProductNotFoundException` (AC3), product mapping incl. exact `BigDecimal` price (AC2), URL encoding (AC10) |
| `SimilarProductsControllerTest` | `@WebMvcTest`, mocked service | Path mapping, exact JSON shape (AC2), `404` with empty body (AC3) |
| `SimilarProductsEndToEndTest` | `@SpringBootTest` + WireMock replaying the challenge mock data for product 1 | Full flow AC1–AC2, base URL configuration wiring (AC9) |

AC11 is checked by review: no `org.springframework` or `java.net.http`
import in `domain` or `application`.

### Manual check

With `docker-compose up -d simulado`, run the application (on another port
if 5000 is still taken) and call `/product/1/similar`: expect products 2,
3, 4 in order.
