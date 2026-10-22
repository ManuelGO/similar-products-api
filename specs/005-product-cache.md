# SPEC-005 — Product cache

Status: **Implemented** (2026-10-22). Approved 2026-10-21: spec, Caffeine dependency and initial TTL/size values.

> **Later change:** SPEC-006 replaced rule 3 of *Expected behaviour*
> ("failures are never cached"). Known failures (not found, upstream error,
> timeout) are now kept for a short TTL; unexpected exceptions are still
> never kept. The single success cache became one cache of outcomes with a
> per-entry TTL.

## Goal

Reduce repeated upstream calls by caching **successful** upstream answers
(similar ids and product details) for a bounded time, and measure the
effect with the challenge's k6 test.

Caching **failures** (negative caching), so that requests stop retrying
lookups known to fail, is SPEC-006.

## Source requirements

- `readme.md`: the evaluation covers **performance** and **resilience**.
  The readme does not explicitly require a cache.
- Project direction (approved 2026-10-21): the solution should include a
  cache and measure the k6 results before and after adding it.
- SPEC-004 measurements (Parts 1–2):
  - **Fast scenarios:** the Simulado upstream saturates about one CPU core.
    Its own latency rises from ~12 ms idle to hundreds of ms. The
    application's executor then queues and rejects detail lookups while
    waiting on it.
  - SPEC-004 listed caching under "not levers", so it is raised here as a
    separate decision, as that spec required.
- `existingApis.yaml`: both upstream operations are idempotent `GET`
  lookups by id, so caching their successful answers is safe with respect
  to side effects.

## Expected behaviour

1. `findSimilarIds(productId)` and `findById(productId)` return a cached
   value when a successful answer for that id was obtained less than the
   configured TTL ago. The upstream is not called.
2. On a miss, the upstream is called once. A successful answer is stored
   and returned.
3. **Failures are never cached:** not found, upstream error, timeout,
   malformed response or any other exception. The exception propagates
   unchanged, exactly as without the cache, and the next lookup calls the
   upstream again.
4. **Concurrent misses for the same key are coalesced.** While a load for
   a key is in flight, other callers asking for that key wait for the same
   result instead of each calling the upstream. If the load fails, every
   waiting caller receives the same failure.
5. Each cache holds at most a configured number of entries. Beyond that,
   the least valuable entries are evicted (Caffeine's size-based policy).
6. The HTTP contract, error mapping, partial-result behaviour and response
   order are unchanged.

## Acceptance criteria

1. **AC1** — A second `findById` for the same id within the TTL returns the
   same product without calling the upstream again. The same holds for
   `findSimilarIds`.
2. **AC2** — After the TTL has elapsed, the next lookup calls the upstream
   again. This is verified with a controllable clock, not with sleeps.
3. **AC3** — When a lookup fails (`ProductNotFoundException`,
   `ProductCatalogException`, `ProductCatalogTimeoutException`, or an
   unexpected `RuntimeException`), the same exception type propagates to
   the caller and nothing is stored. The next lookup calls the upstream
   again.
4. **AC4** — N concurrent lookups of the same uncached key produce exactly
   one upstream call, and all callers receive its result. This is verified
   with latches, not timing.
5. **AC5** — A slow load for one key does not block a lookup or load of a
   different key.
6. **AC6** — The details and similar-ids caches are independent. A product
   id cached in one does not satisfy a lookup in the other.
7. **AC7** — TTLs and maximum sizes are configurable, separately for
   details and similar ids.
8. **AC8** — End to end, two identical requests to
   `/product/{id}/similar` within the TTL produce exactly one upstream call
   per distinct URL (WireMock request counts).
9. **AC9** — `SimilarProductsService`, the controller and the domain are
   unchanged. The cache is an adapter around the `ProductCatalog` port.
10. **AC10** — The k6 measurements described under *Measurement* are
    recorded, and `SOLUTION.md` is updated (see *Documentation*).
11. **AC11** — `./mvnw clean verify` passes. All existing tests still pass
    without modification, except for wiring changes needed to build the
    catalogue.

## Edge cases

- **The same id is requested as a root product and as a similar product.**
  The two caches are separate, so there is no interference (AC6).
- **A slow product that eventually times out** (e.g. `1000` and `10000` in
  the mocks) is never cached. Concurrent requests share one in-flight
  call (AC4), but every new request after the failure calls the upstream
  again. SPEC-006 addresses this.
- **Product `100` (exactly 1 s, on the timeout boundary)** is cached
  whenever one lookup succeeds, so later requests within the TTL include
  it.
- **Stale data:** a product's `price` or `availability` changes upstream.
  The API may return the old value for up to the details TTL. This is the
  accepted trade-off (see *TTL rationale*).
- **Duplicate ids within one request** are already deduplicated by the
  service. The cache does not change this.

## Error / failure cases

| Case | Behaviour |
|------|-----------|
| Upstream failure on a miss | Propagates unchanged; not cached (AC3) |
| Failure of a coalesced load | Every waiting caller receives that failure; not cached |
| Executor saturation | Unchanged. It happens before the catalogue is called, so the cache is never reached |

## Out of scope

- Caching failures, including `404`s (SPEC-006).
- Circuit breakers and retries.
- Cache metrics, actuator endpoints, manual invalidation.
- Distributed or shared caches (Redis, etc.).
- HTTP caching headers on the API's own responses.
- Caching the assembled `/similar` response. Caching at the catalogue
  level reuses details shared by several root products (e.g. products `1`
  and `2` appear in several similar lists in the mocks). It also keeps
  the partial-result logic in one place.
- Refresh-ahead or stale-while-revalidate.

## Technical decisions

### Where the cache lives: a decorator of the `ProductCatalog` port

```
SimilarProductsService ─▶ ProductCatalog
                              ▲
                  CachingProductCatalog ──delegates──▶ HttpProductCatalog
```

- `CachingProductCatalog` implements `ProductCatalog` and wraps another
  `ProductCatalog`. The service, the domain and the controller do not
  change (AC9).
- The port already exists for testability, and this is its second
  concrete use. No new abstraction is introduced.
- Package: `infrastructure.cache`. It is infrastructure (a technology
  choice), not domain logic.

### Explicit wiring instead of `@Cacheable`

Spring's cache abstraction (`@EnableCaching` with `@Cacheable`) was
considered and rejected:

- It works through proxies. That is hidden behaviour: calls from inside
  the same class bypass the cache. The project prefers explicit
  behaviour.
- Its coalescing mode (`sync = true`) runs the loader inside Caffeine's
  compute lock, which is the blocking AC5 rules out. (Correction,
  2026-10-21: an earlier version of this bullet said `@Cacheable` would
  need extra configuration to avoid caching failures. That was wrong:
  `@Cacheable` never caches exceptions.)
- It would add `spring-boot-starter-cache` on top of Caffeine.

Wiring change:
- `HttpProductCatalog` stops being a `@Component`.
- `ApplicationConfiguration` exposes a single `ProductCatalog` bean:
  `new CachingProductCatalog(new HttpProductCatalog(restClient), properties,
  Ticker.systemTicker())`. (Corrected 2026-10-22: an earlier version said
  `ProductApiConfiguration` would build the adapter.)
- There is exactly one `ProductCatalog` bean, so there is no `@Primary`
  or qualifier ambiguity.

### Library: Caffeine (new production dependency, needs approval)

| Option | Bounded size | TTL | Coalesces concurrent misses | Cost |
|--------|--------------|-----|-----------------------------|------|
| `ConcurrentHashMap` | No (manual) | No (manual) | Only via `computeIfAbsent`, which holds a lock during the 1 s upstream call | Hand-written eviction and expiry: subtle concurrent code to own and test |
| **Caffeine** (`com.github.ben-manes.caffeine:caffeine`) | Yes | Yes | Yes, but its loaders run inside a compute or log failed async loads, so coalescing is done with a small in-flight map instead (see *Revised decision*) | One dependency. Its version (3.2.4) is managed by the Spring Boot parent, so no version is pinned in `pom.xml` |

- Caffeine is the standard JVM in-process cache and Spring Boot's
  preferred cache provider.
- It has no transitive runtime dependencies beyond annotation-only
  artifacts.

**Avoiding the lock pitfall (AC5).** A plain Caffeine `Cache.get(key,
loader)` runs the loader inside the map's compute operation. Caffeine
documents that a long computation there can block other writes to the
same hash bin. Upstream loads can take up to the 1 s read timeout, so
the delegate is never called inside a Caffeine compute.

**Revised decision (approved 2026-10-21, option A).** The approved draft
used Caffeine's `AsyncCache`. Implementation showed that `AsyncCache` logs
`WARNING: Exception thrown during asynchronous load`, with a full stack
trace, for every load future that fails. Every 404, 500 or timeout of a
detail would therefore log a stack trace, thousands per second in the k6
failure scenarios. That contradicts SPEC-003 AC13 (each omission logged
once, at `WARN`, without a stack trace) and would distort the
measurements. The alternatives were:
- silencing that Caffeine logger, rejected because it hides signals to
  get clean output;
- cancelling the failed future instead of failing it, rejected because
  waiting callers would lose the original exception (AC3).

Final design:

- A Caffeine **`Cache`** (synchronous) holds only **successful** values.
  It is filled with `put` after a load succeeds and provides the TTL
  (AC2) and the size bound (AC7). The TTL therefore starts when the answer
  is obtained, not when the load starts.
- A small `ConcurrentHashMap<String, CompletableFuture<V>>` holds only
  the loads **in flight**, which coalesces concurrent misses (AC4):
  - the caller whose future is inserted with `putIfAbsent` calls the
    delegate on its own thread, outside any lock (AC5);
  - other callers join that future;
  - on success, the value is stored in the cache **before** the
    in-flight entry is removed, so a new caller always finds one or the
    other;
  - on failure, the future fails with the original exception for every
    waiting caller, and nothing is stored (AC3).

The calling thread is already a `detail-fetch-*` executor thread, or the
request thread for similar ids, so no extra thread pool is introduced.

**Exception transparency.** A joined future wraps failures in
`CompletionException`. The decorator unwraps it and rethrows the original
`RuntimeException`, so the service sees exactly the exceptions the port
declares (AC3).

### TTL rationale (independent of the benchmark)

| Property | Initial value | Reason |
|----------|---------------|--------|
| `product-cache.details.ttl` | `30s` | `availability` (stock) is the most volatile field. The API feeds a "similar products" section, not checkout, so a short staleness window is acceptable while still absorbing bursts of identical lookups. Short enough that a corrected price or stock state appears within half a minute. |
| `product-cache.details.max-size` | `10000` | A product detail is a few hundred bytes, so 10,000 entries is a few MB: bounded memory with room for a realistic hot set. |
| `product-cache.similar-ids.ttl` | `5m` | Similarity lists are computed relationships that change far less often than stock. A longer window is acceptable. |
| `product-cache.similar-ids.max-size` | `10000` | Same memory reasoning as details. |

- TTL is measured from when the entry was written (expire-after-write).
  It is not measured from the last access, so a hot entry still refreshes
  at least once per TTL.
- All values are configurable and provisional. They are **not** chosen to
  optimise the k6 result, which has about 13 distinct URLs and would
  benefit from any TTL longer than the test's 90 s.

### Testing approach

- `CachingProductCatalogTest` (unit, no Spring):
  - a fake delegate counts calls;
  - Caffeine's `Ticker` is injected to control time (AC2);
  - latches drive the coalescing test (AC4) and the cross-key blocking
    test (AC5).
- `SimilarProductsEndToEndTest`: one new test asserts WireMock request
  counts across two identical requests (AC8).
- Test isolation: existing E2E tests reuse the same product ids with
  different stubs per test. The cache must not leak between tests. The
  E2E test context will disable caching for those tests by setting the
  TTL to zero, or will clear the caches before each test. The exact
  mechanism is decided during implementation, and no production-only
  switch is added just for tests.

## Measurement (before/after)

Same procedure, machine and session as SPEC-004 Part 1:
- unmodified `docker compose run --rm k6 run scripts/test.js`;
- per-scenario numbers from InfluxDB;
- omission causes from the application log;
- git SHA and effective configuration recorded for each run.

| Run | Code | Purpose |
|-----|------|---------|
| R0 | `4335532` (SPEC-002: no timeouts, unbounded queue) | "Before" for resilience |
| R1 | `main` at branch point (`b5a8c82`) | "After" for resilience; "before" for the cache. Re-measured, because the SPEC-004 runs are from another session and not directly comparable |
| R2 | this branch | "After" for the cache |

R3 (negative caching) belongs to SPEC-006.

**Expected effect (hypothesis, not a result):**
- normal, notFound and error: upstream calls drop to almost zero after
  warm-up, so latency and executor rejections should fall sharply;
- slow and verySlow: little change, because the failing products are
  never cached.

**Caveat to document:** with about 13 distinct URLs, the k6 hit rate will
be close to 100%. The improvement is real for this workload but is not a
prediction of production hit rates.

**R0 note:** without timeouts, `verySlow` requests wait up to the mock's
50 s delay. The k6 `gracefulStop` (30 s) will cut them off. This is
recorded as observed, not worked around.

## Measurement results (2026-10-21)

All runs used the unmodified `docker compose run --rm k6 run scripts/test.js`
in one session. The app, Simulado, InfluxDB and the amd64 k6 image (emulated)
ran on the same 10-core Apple Silicon laptop.
- Readiness was checked with an unknown product (`404`), so the cache was not
  warmed before k6.
- req/s = requests / 10 s scenario window.
- products/request = 3 minus omissions per `200` response. The maximum is 3
  for normal, slow and verySlow, and 2 for notFound and error.

| Run | Code | Config | k6 window (UTC) |
|-----|------|--------|-----------------|
| R0 | `4335532` | pool 32, unbounded queue, no timeouts | 17:47:30–17:49:03 |
| R1 | `b5a8c82` (`main`), clean | pool 32 / queue 32, connect 500 ms, read 1 s | 17:50:24–17:51:28 |
| R2 | `b5a8c82` + uncommitted SPEC-005 changes | R1 + cache (details 30 s / 10k, similar ids 5 m / 10k) | 17:51:37–17:52:42 |

| Scenario | Run | req/s | p50 / p95 / p99 ms | Statuses | Products / request | Executor rejections |
|----------|-----|-------|--------------------|----------|--------------------|---------------------|
| normal | R0 | 296 | 150 / 258 / 466 | 200 | n/a | n/a |
| | R1 | 315 | 142 / 210 / 286 | 200 | 2.07 of 3 | 2,943 |
| | R2 | 338 | **56 / 121 / 158** | 200 | **3.00 of 3** | **0** |
| notFound | R0 | 368 | 35 / 116 / 277 | **404 (whole response)** | n/a | n/a |
| | R1 | 318 | 132 / 199 / 248 | 200 | 1.48 of 2 | 3,174 |
| | R2 | 392 | **6 / 29 / 43** | 200 | **2.00 of 2** | **0** |
| error | R0 | 258 | 84 / 283 / 452 | **500 (2,535), aborted (43)** | n/a | n/a |
| | R1 | 314 | 148 / 231 / 276 | 200 | 1.36 of 2 | 3,459 |
| | R2 | 355 | **25 / 138 / 190** | 200 | **2.00 of 2** | **0** |
| slow | R0 | **9** | **11,061 / 18,334 / 19,325** | 200 (88), aborted (5) | n/a | n/a |
| | R1 | 309 | 7 / 1,228 / 1,667 | 200 | 0.16 of 3 | 8,476 |
| | R2 | 338 | 4 / 994 / 1,986 | 200 | 0.06 of 3 | 9,516 |
| verySlow | R0 | **4** | — | **aborted (38 of 38)** | n/a | n/a |
| | R1 | 354 | 8 / 92 / 2,006 | 200 | 0.00 of 3 | 10,271 |
| | R2 | 332 | 34 / 127 / 1,996 | 200 | 0.00 of 3 | 9,523 |

Totals:

| Run | Requests | Executor rejections | Application `5xx` |
|-----|----------|---------------------|-------------------|
| R0 | 9,349 | — | 2,535 `500`s, plus aborted requests |
| R1 | 16,105 | 28,323 | 0 |
| R2 | 17,546 | 19,039 | 0 |

### Findings

- **Resilience, R0 → R1:**
  - Without timeouts, `slow` collapsed to 9 req/s, with a p50 of 11 s.
  - No `verySlow` request completed before k6 aborted it.
  - Failures of individual details turned whole responses into `404`
    (`notFound`) or `500` (`error`).
  - R1 answers every request with `200` and partial results, at more than
    300 req/s in every scenario.
- **Cache, R1 → R2, fast scenarios:**
  - Executor rejections fell from about 9,600 to 0.
  - Every response is complete: 3 of 3, 2 of 2 and 2 of 2.
  - p50 fell from 132–148 ms to 6–56 ms.
  - Hypothesis confirmed.
- **Cache, R1 → R2, slow scenarios:** no improvement (0.16 → 0.06 and
  0.00 → 0.00 products per request). Hypothesis confirmed. Products `1000`
  and `10000` always time out, so they are never cached, and every lookup
  still holds an executor thread for up to the read timeout. Two
  consequences that matter for SPEC-006:
  - **A cache hit still needs an executor slot**, because the service
    submits every detail lookup, including hits, to the bounded executor.
    The fast product `3` is therefore still rejected while the pool is
    full of slow calls.
  - Coalescing shares one upstream call, but each waiting caller still
    occupies its own executor thread while it waits.
- Product `100` (exactly 1 s) sometimes has its body cut off at the read
  timeout. This was logged 39 times in R2 as an upstream error, the case
  classified in SPEC-003.
- Caveat: about 13 distinct URLs give this benchmark an almost 100% hit
  rate. The fast-scenario improvement is real for this workload, not a
  prediction for production.

## Documentation

Update `SOLUTION.md`:
- a cache section with what is cached, what is not, the TTL rationale and
  the staleness trade-off;
- the configuration table;
- the architecture diagram, adding the decorator;
- the performance section with R0 / R1 / R2;
- known limitations: in-process only, staleness, benchmark hit-rate
  caveat;
- the §10 line that says caching is "not implemented, by design".

## Verification strategy

| AC | How | Result (2026-10-22) |
|----|-----|---------------------|
| AC1–AC7 | `CachingProductCatalogTest` | ✅ 18 tests pass |
| AC8 | `ProductCacheEndToEndTest` WireMock request counts | ✅ |
| AC9 | Review: `git diff main` shows no change to `application`, `domain` or `web` | ✅ empty diff |
| AC10 | k6 runs R0–R2 recorded in this spec and summarised in `SOLUTION.md` | ✅ |
| AC11 | `./mvnw clean verify` | ✅ 76 tests. The only change to an existing test is configuration: `SimilarProductsEndToEndTest` sets both TTLs to `0s` so each test reaches WireMock. |

SonarCloud after implementation: Quality Gate passed, 0 issues, 94.8% coverage on new code.

## Approval gates

1. 🔶 Approve this spec, including the **new production dependency
   (Caffeine)** and the initial TTL values.
2. Implementation plan, then implementation and tests.
3. Measurements R0–R2, then **stop and report**.
4. `SOLUTION.md` update, then 🔶 review.
5. Mark Implemented; commit, PR.
