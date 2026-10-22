# SPEC-003 — Upstream resilience

Status: **Implemented**

> **Later change:** caching, listed below as out of scope, was added afterwards
> by SPEC-005 and SPEC-006. AC14 still holds (no retries: each upstream
> lookup is requested once), but repeated lookups within a cache TTL no longer
> reach the upstream at all.

## Goal

Make every upstream failure explicit, and stop slow upstream calls from
holding resources without limit:

- configurable connect and read timeouts on every upstream call;
- a classification of upstream failures into domain-level outcomes;
- partial results when individual similar-product lookups fail;
- `502` / `504` when the similar-ids lookup itself fails;
- an explicit executor queue bound and saturation policy, so the backlog
  of waiting detail tasks cannot grow without limit.

## Source requirements

- `readme.md`: evaluation covers **resilience** and **performance**.
- `similarProducts.yaml`: only `200` and `404` are documented.
- `existingApis.yaml`: `GET /product/{id}` documents `200` and `404`;
  `similarids` documents only `200`.
- `shared/simulado/mocks.json` and `shared/k6/test.js`: the supplied test
  environment includes detail lookups that return `404`, `500` and slow
  responses. They show which failure modes exist; they are **not** used to
  choose timeout values.
- Approved analysis decisions:
  - partial results for failures of individual similar-product details;
  - all details fail → `200 []`;
  - `502` when the similar-ids request fails, `504` when it times out;
  - `404` when the requested product cannot be found;
  - smallest error representation compatible with the contract (no
    `ProblemDetail`);
  - no retries, no circuit breaker, no Resilience4j;
  - timeouts and concurrency configurable, initial values justified
    independently of the benchmark.

## Documented assumptions

- **A1 — Partial results.** The supplied OpenAPI contract does not define
  behaviour when an individual similar-product lookup fails. This spec
  assumes that a similar product whose detail cannot be obtained (not
  found, upstream error, timeout, malformed response, executor saturation)
  is **omitted** from the response, and the remaining products are
  returned in similarity order with `200`. If every lookup fails, the
  response is `200 []`.
- **A2 — Similar-ids failures.** The contract does not define responses
  other than `200` and `404`. When the similar-ids lookup fails, the
  response is `502 Bad Gateway`, or `504 Gateway Timeout` when it timed
  out, with an empty body.

## Expected behaviour

### Failure classification (adapter)

| Upstream outcome | Similar-ids lookup | Detail lookup |
|------------------|--------------------|---------------|
| `404` | `ProductNotFoundException` | `ProductNotFoundException` |
| Other `4xx`, any `5xx` | `ProductCatalogException` | `ProductCatalogException` |
| Connection refused / reset | `ProductCatalogException` | `ProductCatalogException` |
| Timeout surfacing as `java.net.http.HttpTimeoutException` (connect timeout, or read timeout before the response headers arrive) | `ProductCatalogTimeoutException` | `ProductCatalogTimeoutException` |
| Headers arrive in time, body terminated by the read timeout (surfaces as `RestClientException` → `IOException("closed")`) | `ProductCatalogException` | `ProductCatalogException` |
| Malformed response (see below) | `ProductCatalogException` | `ProductCatalogException` |

`ProductCatalogTimeoutException` extends `ProductCatalogException`, so
callers that do not care about the difference handle both the same way.

Timeouts are classified **only** by the exception type observed
(`HttpTimeoutException` in the cause chain). They are never inferred from
elapsed time or exception messages. As a result, for the similar-ids
operation a timeout before the headers maps to `504`, while a response
whose body is cut off by the read timeout maps to `502`: the upstream did
start responding, but the response was incomplete. For detail lookups
both cases omit the product.

**Malformed response:**
- similar ids: missing or non-array body, or any `null` / blank id;
- product detail: missing or unparseable body, `id` or `name` missing or
  blank, `price` or `availability` missing (the contract marks all four
  as required, with `minLength: 1` for `id` and `name`).

### Use case (`SimilarProductsService`)

| Failure | Outcome |
|---------|---------|
| Similar-ids lookup throws `ProductNotFoundException` | Propagates → `404` |
| Similar-ids lookup throws `ProductCatalogTimeoutException` | Propagates → `504` |
| Similar-ids lookup throws `ProductCatalogException` | Propagates → `502` |
| A detail lookup throws `ProductNotFoundException` or `ProductCatalogException` (incl. timeout) | That product is omitted, logged at `WARN` (A1) |
| A detail task is rejected by the saturated executor | That product is omitted, logged at `WARN` (A1) |
| Any other exception (a bug, not an upstream failure) | **Not** swallowed: propagates → `500` |

Only known upstream failure types are converted into omitted products.
Catching every exception would silently hide programming errors as
"missing products".

### HTTP mapping (`ApiExceptionHandler`)

| Exception | Response |
|-----------|----------|
| `ProductNotFoundException` | `404`, empty body (unchanged) |
| `ProductCatalogTimeoutException` | `504`, empty body |
| `ProductCatalogException` | `502`, empty body |
| Spring MVC request-processing exception implementing `ErrorResponse` (e.g. unknown route, unsupported method) | The HTTP status provided by Spring (e.g. `404`, `405`), empty body. Not classified or logged as an unexpected application error. |
| Any other exception | `500`, empty body, logged at `ERROR` with stack trace |

The explicit `500` handler replaces Spring Boot's default error body, so
every response the application produces is defined by this project.

The `ErrorResponse` rule was added after observing that a plain
`Exception` handler also catches Spring MVC's own request errors, which
would otherwise turn `404`/`405` into `500`. It relies on the
`ErrorResponse` abstraction and its status code, not on a list of
specific Spring exception classes.

## Timeouts

Configured on the JDK `HttpClient` used by the RestClient:

| Property | Initial value | Applies to |
|----------|---------------|------------|
| `product-api.connect-timeout` | `500ms` | establishing the TCP connection |
| `product-api.read-timeout` | `1s` | waiting for the upstream response |

Both are bound as `java.time.Duration` (accepting `500ms`, `1s`, …) and
apply to both upstream endpoints.

### Rationale (independent of the challenge mock delays)

- **Connect timeout, 500 ms.** Opening a TCP connection to a service on
  the same host or network normally takes around a millisecond. 500 ms is
  orders of magnitude above that, so it only trips when the upstream is
  unreachable or not accepting connections, which is when waiting longer
  gives nothing.
- **Read timeout, 1 s.**
  - Both upstream calls are single-key lookups. A healthy service answers
    them in milliseconds; a response slower than a second indicates a
    degraded upstream.
  - The API feeds a "similar products" section of a product page: useful
    but secondary content.
  - Partial results (A1) are acceptable, so a slow product can be omitted
    instead of delaying every other product in the response.
  - Every slow call occupies one of a bounded number of worker threads
    for as long as the timeout allows. A shorter budget frees those
    threads sooner for other requests.
  - The value is configurable, and SPEC-004 measures and tunes it.
- **Budgets, not guarantees.** These are configured timeout budgets. The
  total time a single call can take is determined by how the JDK
  `HttpClient` and Spring apply them (see *What the read timeout covers*).
  This spec does not assume that connect + read timeout is a hard upper
  bound on call duration.
- **Observed consequence, not a reason:** in the supplied mocks, product
  `100` has a delay of exactly 1000 ms. With a 1 s read timeout it sits on
  the boundary and may be included or omitted from run to run. Products
  `1000` (5 s) and `10000` (50 s) are always omitted. This is reported so
  the trade-off is visible; the value was not chosen to include or exclude
  any of them.

### What the read timeout covers

`JdkClientHttpRequestFactory.setReadTimeout` sets the JDK request timeout.
Its exact semantics are not assumed; the adapter tests establish which
phases it covers in practice:
- delay before the response headers (the case in the supplied mocks);
- a response whose body arrives slowly after the headers.

**Observed behaviour** (experiment with Spring Framework 7.0.9 and JDK 17,
read timeout 300 ms, against WireMock):

- The configured read timeout bounds the overall request/response
  operation, **including body consumption**. A body streamed slowly after
  headers that arrived promptly was cut off at the read timeout. With a
  5 s timeout the same response completed normally.
- The exception shape depends on whether the response headers had
  already arrived:
  - before headers: `ResourceAccessException` →
    `java.net.http.HttpTimeoutException`;
  - after headers, during the body: `RestClientException` →
    `java.io.IOException: closed`, with nothing marking it as a timeout.
- Not measured: how the connect timeout and the read timeout combine on a
  new connection.

No additional timeout mechanism is added.

## Executor saturation policy

| Property | Initial value |
|----------|---------------|
| `detail-fetch.pool-size` | `32` (unchanged) |
| `detail-fetch.queue-capacity` | `32` |

**Policy:** detail tasks wait in a bounded FIFO queue. When both the pool
and the queue are full, the task is **rejected immediately**, and the
product is omitted (A1). The request thread never blocks on a full queue
and never runs the call itself.

### What the bounded queue guarantees

- At most `pool-size` detail calls run at once, and at most
  `queue-capacity` tasks wait. Executor memory and backlog cannot grow
  with load.
- A task never waits for a queue slot: it is either accepted
  immediately or rejected immediately.
- An accepted task waits behind at most `queue-capacity` other tasks
  before it starts.

It does **not** bound how long that wait lasts in absolute time. The wait
depends on how long the calls ahead of it take. The executor is also
shared by all concurrent requests, so one request's tasks can queue
behind another request's slow calls.

### Why the queue capacity equals the pool size

With `queue-capacity = pool-size`, an accepted task waits behind at most
one pool's worth of tasks, so roughly one round of calls must complete
before it starts. A larger queue would allow several rounds of waiting
before the call even starts, which is the hidden latency SPEC-002
deferred. A smaller queue would reject more often during ordinary bursts.

This is a relative rule (queued work ≤ one round of in-flight work), not
an absolute time bound. It does not depend on the test load. Both values
remain configurable and are tuned with measurements in SPEC-004.

### Alternatives considered

| Policy | Why not |
|--------|---------|
| Unbounded queue (current default) | Unbounded waiting before the timeout starts; memory grows with load. |
| No queue (`SynchronousQueue`) | Rejects as soon as all threads are busy, so ordinary short bursts lose products. |
| Caller-runs (request thread performs the call) | Avoids losing products, but under overload the pool no longer bounds upstream concurrency (up to 200 Tomcat threads could call upstream). It also makes that request's lookups sequential. |

## Acceptance criteria

1. **AC1** — Detail `404` → product omitted; remaining products returned
   in order with `200`.
2. **AC2** — Detail `5xx` or other non-404 `4xx` → product omitted.
3. **AC3** — Detail whose response headers arrive later than the read
   timeout → product omitted, without waiting for the upstream to finish
   responding.
4. **AC4** — Malformed detail (unparseable body, empty body, missing or
   blank required field) → product omitted.
5. **AC5** — Every detail lookup fails → `200 []`.
6. **AC6** — Similar-ids `5xx`, non-404 `4xx`, malformed body or
   connection failure → `502`, empty body.
7. **AC7** — Similar-ids whose response headers arrive later than the
   read timeout → `504`, empty body. Similar-ids whose headers arrive in
   time but whose body is cut off by the read timeout → `502`, empty body.
8. **AC8** — Connection refused or reset → classified as
   `ProductCatalogException` (not timeout).
9. **AC9** — Connect and read timeouts are configurable
   (`product-api.connect-timeout`, `product-api.read-timeout`).
10. **AC10** — When the pool and queue are full, further detail tasks are
    rejected without blocking and those products are omitted.
11. **AC11** — Queue capacity is configurable
    (`detail-fetch.queue-capacity`).
12. **AC12** — An unexpected exception (not an upstream failure type)
    produces `500` with an empty body and is not converted into an
    omitted product.
13. **AC13** — Every omitted product is logged once at `WARN` with its id
    and failure reason (no stack trace); `502`/`504` are logged at `WARN`.
14. **AC14** — No retries are performed: each upstream lookup is
    requested exactly once.
15. **AC15** — The domain and application packages still do not depend
    on Spring or HTTP types (the SLF4J logging API is allowed; it is not
    Spring).

## Edge cases

- Duplicate ids where the single lookup fails → omitted once.
- Detail returns a product whose `id` differs from the requested id → not
  validated; the contract does not define this, so the product is
  returned as received.
- A failing detail at position 1 of 3 → products 2 and 3 keep their
  relative order.
- Connect timeout: classified as a timeout (`504` for similar-ids),
  because it is still the upstream failing to respond in time.

## Out of scope

- Retries, backoff, circuit breaker, bulkhead libraries (rejected in the
  analysis).
- Caching.
- An overall per-request deadline. Per-call timeouts plus bounded
  executor capacity do **not** constitute a strict end-to-end deadline:
  the actual call duration depends on the timeout semantics established
  by the adapter tests, and queueing time depends on other requests
  sharing the executor. A strict deadline (e.g. a time limit on joining
  the futures) is deliberately not added in this spec. SPEC-004 can
  reconsider it if measurements show requests exceeding acceptable
  latency.
- Tuning the values against the k6 run → SPEC-004.
- Metrics or health endpoints.

## Technical decisions

- **New domain exceptions:** `ProductCatalogException` and
  `ProductCatalogTimeoutException`. Both are plain `RuntimeException`s in
  `domain`, as part of the port's contract (documented on
  `ProductCatalog`).
- **Adapter:**
  - `onStatus` handlers map `404` and other error statuses.
  - A single `try/catch` around each call translates
    `ResourceAccessException` (I/O errors, which RestClient wraps) into
    timeout vs. other failures by checking the cause for
    `java.net.http.HttpTimeoutException`. It also translates
    `RestClientException` (e.g. unreadable body) into
    `ProductCatalogException`.
  - Required-field validation lives in `ProductApiResponse.toProduct()`.
- **Service:**
  - Each detail future is created with an explicit `try/catch` for
    `RejectedExecutionException`, because `supplyAsync` throws it
    synchronously when the executor refuses the task.
  - Futures are joined in id order. Known upstream failures are unwrapped
    from `CompletionException`, logged, and dropped; anything else is
    rethrown.
- **Logging:** SLF4J (already on the classpath through Spring Boot's
  logging starter). It is a logging API, not Spring, so the application
  layer remains framework-free. Omitted products are logged without
  stack traces to keep logs readable under load.
- **No new dependencies.**

## Verification strategy

| Test | Covers |
|------|--------|
| `HttpProductCatalogTest` (WireMock, short timeouts) | `500`, `400` → `ProductCatalogException`; delayed headers → `ProductCatalogTimeoutException`; slowly streamed body cut off by the read timeout → `ProductCatalogException`, not timeout; connection refused (closed port) and `CONNECTION_RESET_BY_PEER` fault → not timeout (AC8); malformed JSON, empty body, missing / blank fields, `null` id in list → `ProductCatalogException`; each stub requested exactly once (AC14) |
| `SimilarProductsServiceTest` | Omission for each detail failure type with order preserved (AC1–AC4); all fail → `[]` (AC5); similar-ids failures propagate; unexpected exception propagates (AC12) |
| `SimilarProductsServiceConcurrencyTest` | Saturation with pool 1 and queue 1: blocked calls fill both, the third task is rejected without blocking and its product omitted (AC10, AC11); latch-based, no timing assumptions |
| `SimilarProductsControllerTest` | `ProductCatalogException` → `502`, timeout → `504`, unexpected → `500`, `GET /unknown/path` → `404`, `POST /product/1/similar` → `405`, all with empty bodies |
| `SimilarProductsEndToEndTest` (WireMock, short timeouts) | Replays the supplied failure modes: detail `404`, detail `500`, detail slower than the timeout → omitted; similar-ids `500` → `502`; similar-ids headers delayed → `504`; similar-ids body cut off by the read timeout → `502` |
| Log assertions | AC13 checked with Spring Boot's `OutputCaptureExtension` (part of `spring-boot-starter-test`) in the service tests |

**Connect timeout:** no deterministic automated test. Reproducing it needs
an address that silently drops packets, which depends on the network. It
is covered by configuration review and by the shared classification path
(`HttpConnectTimeoutException` is a subclass of `HttpTimeoutException`).

**Manual check:** against the supplied Simulado mocks, with the port
override, call `/product/4/similar` (detail `404`), `/product/5/similar`
(detail `500`), `/product/2/similar` and `/product/3/similar` (slow
details), and an unknown id. Confirm the omissions, observe the response
times against the configured timeouts, and check the `WARN` logs.
