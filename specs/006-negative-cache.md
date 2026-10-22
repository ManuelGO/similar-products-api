# SPEC-006 — Negative cache

Status: **Implemented** (2026-10-22). Approved 2026-10-22: spec, ambiguity assumption, stored failures, outcome-cache design and TTL values.

## Goal

Remember **known upstream failures** of a lookup (not found, upstream
error, timeout) for a short time. Requests that need a product known to be
failing are then answered at once, without calling the upstream and
without holding an executor thread for up to the read timeout. Measure the
effect with the challenge's k6 test (run R3).

## Source requirements

- `readme.md`: the evaluation covers **performance** and **resilience**.
  The readme does not require a cache of any kind.
- Project direction (approved 2026-10-21): SPEC-005 caches successful
  lookups, and SPEC-006 caches failures, each with before/after k6 data.
- SPEC-005 measurements (R2):
  - **slow** and **verySlow** did not improve: 0.06 and 0.00 of 3 products
    per response, with about 9,500 executor rejections each.
  - Products `1000` (5 s) and `10000` (50 s) always time out after the 1 s
    read timeout. Each lookup holds a `detail-fetch` thread for that
    second, so the pool fills up, and even product `3`, which is cached,
    is rejected because a cache hit also needs a thread.
- `similarProducts.yaml`: a `200` with a list of products, or `404`. The
  partial-result behaviour (omitting products whose detail fails) was
  decided in SPEC-003 and is unchanged.

## Expected behaviour

1. When a lookup fails with a **known failure**, the failure is stored for
   a configured TTL:

   | Failure | Upstream cause | TTL property |
   |---------|----------------|--------------|
   | `ProductNotFoundException` | `404` | `not-found-ttl` |
   | `ProductCatalogTimeoutException` | connect or read timeout | `failure-ttl` |
   | `ProductCatalogException` | other error status, I/O error, malformed or truncated body | `failure-ttl` |

2. A lookup of a key whose failure is stored, within its TTL, rethrows the
   **same exception** without calling the upstream.
3. **Unexpected exceptions** (any other `RuntimeException`, or an `Error`)
   are never stored, as today. They signal a bug, not an upstream state.
4. The next lookup after the failure TTL calls the upstream again. A
   success then replaces the failure, with the success TTL.
5. This applies to both caches, details and similar ids, each with its own
   two failure TTLs.
6. Everything else from SPEC-005 is unchanged:
   - successful values and their TTLs;
   - coalescing of concurrent loads;
   - no locks held during upstream calls;
   - the size bound, which now counts both kinds of entry.
7. HTTP behaviour seen by a client is unchanged for any single request:
   - a stored failure of a similar product's detail omits that product, as
     a fresh failure would;
   - a stored failure of the similar ids gives the same `404`, `502` or
     `504` as a fresh one;
   - the omission is still logged once per request at `WARN`, with the same
     message (SPEC-003 AC13).

**The one visible change** is *when* the upstream is asked again: a product
that failed is omitted for up to `failure-ttl` (or `not-found-ttl`), even if
the upstream has recovered in the meantime. See *Ambiguity*.

## Ambiguity — needs a decision

The challenge says nothing about retrying failed products. Storing a
failure means a product can be left out of responses for a few seconds
after the upstream has recovered. Examples from the mocks:
- product `100` responds in exactly 1 s, on the timeout boundary. When one
  lookup times out, it is omitted for `failure-ttl` even though the next
  lookup might have succeeded;
- a single transient network error hides a product for `failure-ttl`.

**Proposed assumption:** this is acceptable for a "similar products" section
as long as the window is short (5 s) and configurable. Setting a TTL to `0s`
disables the behaviour for that kind of failure.

## Acceptance criteria

1. **AC1** — After a `ProductNotFoundException`, lookups of the same key
   within `not-found-ttl` rethrow the same exception instance without
   calling the upstream. The same holds for details and similar ids.
2. **AC2** — After a `ProductCatalogTimeoutException` or a
   `ProductCatalogException`, lookups within `failure-ttl` rethrow the same
   exception instance without calling the upstream.
3. **AC3** — After the relevant failure TTL, the next lookup calls the
   upstream again. A success is then stored with the success TTL. This is
   verified with a controllable clock.
4. **AC4** — An unexpected `RuntimeException` or `Error` is never stored:
   the next lookup calls the upstream again.
5. **AC5** — A coalesced load that fails with a known failure gives every
   waiting caller that failure, as in SPEC-005, and stores it once.
6. **AC6** — A failure TTL of `0s` disables storing that kind of failure.
7. **AC7** — The four new TTLs are configurable:
   `product-cache.{details,similar-ids}.{not-found-ttl,failure-ttl}`.
8. **AC8** — End to end, two requests within the TTL whose similar product
   returns `404`, `500` or times out produce one upstream call for that
   product, and both responses omit it (WireMock request counts).
9. **AC9** — `SimilarProductsService`, the controller and the domain are
   unchanged.
10. **AC10** — k6 runs R2 and R3 are recorded and `SOLUTION.md` is updated
    (see *Measurement* and *Documentation*).
11. **AC11** — `./mvnw clean verify` passes.

**Tests from SPEC-005 that change on purpose,** because they assert "known
failures are not cached":
- in `CachingProductCatalogTest`, the parameterized "not cached" tests and
  `cachesASuccessfulAnswerThatFollowsAFailure`. They become "stored for the
  failure TTL" for known failures and keep asserting "not stored" for
  unexpected exceptions;
- `ProductCacheEndToEndTest.failedLookupsAreNotCached`, which becomes AC8.

## Edge cases

- **The same exception instance is rethrown to many requests.** SPEC-005
  coalescing already does this for concurrent waiters. The service only
  reads its type and message. The stack trace points at the original
  failed call, which is accurate.
- **A failure and a success race for the same key.** The in-flight map
  allows one load per key, so they cannot overlap. A new load only starts
  after the stored failure has expired.
- **Product `100` (1 s, on the boundary).** Each lookup stores either a
  success (30 s) or a timeout (5 s), whichever happens. Over a run it is
  included more often than without the negative cache, because a success
  is kept for 30 s.
- **Unknown product ids in client requests** (`/product/xyz/similar`).
  Each distinct unknown id stores one small entry for `not-found-ttl`.
  Entries are bounded by the cache's existing `max-size`, so a flood of
  random ids cannot grow memory without limit. It can push out useful
  entries, which is the same exposure the success cache already has.

## Error / failure cases

| Case | Behaviour |
|------|-----------|
| Known failure, not stored yet | Propagates unchanged and is stored for its TTL |
| Known failure, stored | Rethrown at once; no upstream call; no executor wait |
| Unexpected exception | Propagates unchanged; not stored |
| Executor saturation (`RejectedExecutionException`) | Unchanged; raised before the catalogue is reached, so it is never stored |

## Out of scope

- Circuit breakers, which act on the whole upstream rather than on one
  key, and retries. They need a library (e.g. Resilience4j) or more
  hand-written state. The failures in this challenge are per product, so a
  per-key memory of failures is the smaller tool.
- Checking the cache **before** submitting to the executor, so that hits
  never need a thread. That would move cache knowledge into the service,
  or require a new port method. With failures cached, a hit takes
  microseconds, so it is not expected to matter. R3 will show whether it
  does.
- Changing the executor size, timeouts or log messages.
- Stale-while-revalidate (serving an old success while the upstream fails).

## Technical decisions

### One cache of outcomes with a per-entry TTL (changes SPEC-005's design)

SPEC-005 keeps only successful values, in a Caffeine `Cache` with a single
`expireAfterWrite`. SPEC-006 needs a different TTL per kind of entry.

**Proposal:** each `CachedLookup` stores an *outcome*, either the value or
the failure, in one Caffeine cache. The TTL of each entry comes from
Caffeine's `Expiry.writing((key, outcome) -> ttl)`. This is available in the
Caffeine version already on the classpath (3.2.4), so no new dependency is
needed. Every entry is timed from when it was written, as in SPEC-005.

```java
sealed interface Outcome<V> {
    record Success<V>(V value) implements Outcome<V> {}
    record Failure<V>(RuntimeException failure) implements Outcome<V> {}
}
```

- A hit on `Success` returns the value. A hit on `Failure` rethrows the
  exception.
- The in-flight map and the "no lock during the upstream call" rule do not
  change.
- The TTL is chosen in one place, from the outcome:
  - `Success` → `ttl`;
  - `ProductNotFoundException` → `not-found-ttl`;
  - `ProductCatalogException`, including its timeout subclass →
    `failure-ttl`.
  - A `0s` TTL means the outcome is not stored (AC6).

**Simpler alternative considered:** a second Caffeine cache per lookup for
failures only. It needs no per-entry expiry, but each lookup has to check
two caches, and the two size bounds have to be reasoned about separately.
One cache of outcomes keeps a single lookup path and a single size bound.

Approving this spec includes approving this change to SPEC-005's design.

### TTL values (provisional)

| Property | Details | Similar ids | Reason |
|----------|---------|-------------|--------|
| `not-found-ttl` | `30s` | `30s` | A `404` is a definite answer. Products are created rarely, so 30 s is the same staleness window already accepted for details. |
| `failure-ttl` | `5s` | `5s` | Errors and timeouts are often transient. 5 s bounds how long a recovered product stays hidden, while still turning hundreds of identical failing calls per second into one every 5 s. |

These values are **not** tuned to k6. Any value above zero helps the
benchmark, whose scenarios last 10 s each.

### Testing approach

- `CachingProductCatalogTest`: the same controllable clock and latches.
  - New cases for AC1–AC6, parameterized over details and similar ids where
    that keeps the tests short.
- `ProductCacheEndToEndTest`: AC8, with WireMock stubs for `404`, `500` and
  a delay beyond the read timeout.
- `SimilarProductsEndToEndTest` also sets the new TTLs to `0s`, so each test
  still reaches WireMock.

## Measurement

Same procedure as SPEC-005, in one session:

| Run | Code | Purpose |
|-----|------|---------|
| R2 | `main` at the branch point (`0b3f481`) | "Before", re-measured in the same session as R3 |
| R3 | this branch | "After" |

**Expected effect (hypothesis, not a result):**
- **slow and verySlow:** products `1000` and `10000` are asked about once
  per 5 s rather than on every request, so executor rejections should fall
  sharply. Products `3` and `100` should then appear in most responses, and
  p95/p99 should drop well below 1 s.
- **notFound and error:** products `5` (`404`) and `6` (`500`) stop
  reaching the upstream. There is little latency to gain, because R2 is
  already fast there.
- **normal:** no change.

**Caveat:** the benchmark has a handful of keys, all failing in the same
way on every request. That is the best case for a negative cache.

## Measurement results (2026-10-22)

Same procedure and laptop as SPEC-005. The two runs were back to back.
Readiness was checked with a `404` probe, so no cache was warmed.

| Run | Code | k6 window (UTC) |
|-----|------|-----------------|
| R2 | `0b3f481` (`main`) | 16:48:23–16:49:28 |
| R3 | `0b3f481` + uncommitted SPEC-006 changes | 16:49:48–16:50:51 |

| Scenario | Run | req/s | p50 / p95 / p99 / max ms | Products / request | Executor rejections |
|----------|-----|-------|--------------------------|--------------------|---------------------|
| normal | R2 | 362 | 26 / 83 / 116 / 229 | 2.97 of 3 | 124 |
| | R3 | 364 | 22 / 89 / 125 / 251 | 2.96 of 3 | 133 |
| notFound | R2 | 359 | 32 / 85 / 103 / 129 | 1.96 of 2 | 23 |
| | R3 | 383 | 10 / 56 / 104 / 200 | **2.00 of 2** | **0** |
| error | R2 | 377 | 16 / 59 / 75 / 113 | 2.00 of 2 | 0 |
| | R3 | 361 | 21 / 84 / 114 / 162 | 1.97 of 2 | 0 |
| slow | R2 | 328 | 7 / 993 / 1,986 / 2,000 | 0.07 of 3 | 9,206 |
| | R3 | 348 | 42 / **114 / 980 / 1,236** | **0.80 of 3** | **2,072** |
| verySlow | R2 | 329 | 32 / 97 / 1,996 / 2,049 | 0.00 of 3 | 9,428 |
| | R3 | 378 | 12 / **40 / 1,001 / 1,038** | 0.00 of 3 | **2,122** |

Totals: 17,553 → 18,345 requests; 18,781 → 4,327 executor rejections; no
`5xx` and no `ERROR` log lines in either run.

### Findings

- **slow and verySlow improved, but less than the hypothesis.**
  - Rejections fell by 77%. p99 halved and the maximum fell from about
    2 s to about 1 s, because no request waits on two 1 s timeouts in a row.
  - In `slow`, product `3` now appears in 80% of responses, against 7% in
    R2.
  - **Product `100` never appeared, in either run.** Under load, its 1 s
    mock delay always exceeds the 1 s read timeout, so it is a timeout
    like `1000` and `10000`. The 3-of-3 maximum is therefore 1 for `slow`
    and 0 for `verySlow`. The hypothesis that `100` would appear was wrong.
- **The remaining rejections arrive in bursts.** Grouped by second, almost
  all of them fall in four 1–2 s bursts, one each time a stored timeout
  expires (about every 5–6 s), plus 133 at start-up:
  - the first request after expiry reloads the product, which takes the
    full 1 s timeout;
  - every concurrent request for that product joins the same load, as
    SPEC-005 intends, **but each one still holds its own executor thread
    while it waits**;
  - at about 350 req/s, the 32 threads and 32 queue slots fill within
    milliseconds, and everything else is rejected for that second;
  - between bursts there are no rejections.
- **notFound and error:** `404` and `500` were already fast, so the only
  visible change is that the few rejections in `notFound` disappeared.
  Differences of a few ms in p50/p95 between runs are noise at this
  scale.
- **normal:** unchanged, as expected.

**Confirmation on merged `main` (`0886646`, 17:39:20–17:40:23 UTC):** 18,335
requests, all `200`; 4,122 executor rejections; `slow` 0.83 of 3 with p99
1,001 ms; `verySlow` p99 944 ms; `notFound` and `error` complete. This is
consistent with R3.

### What would remove the bursts (not part of this spec)

The cause is that a caller waiting on a shared load holds a thread. Two
options:
- serve the expired failure for one more round while a single reload runs
  in the background (Caffeine's `refreshAfterWrite`). Waiting callers would
  then return at once;
- check the cache, and any load in flight, before submitting to the
  executor. That changes the service or the port.

Either is a new decision and needs its own spec.

## Documentation

Update `SOLUTION.md`:
- §5 Caching, now covering failures, their TTLs and the staleness
  trade-off for failures;
- the configuration table;
- §10, adding R3;
- §11, adding limitations: a recovered product stays hidden for up to
  `failure-ttl`, and logs do not say whether a failure came from the
  cache.

## Verification strategy

| AC | How | Result (2026-10-22) |
|----|-----|---------------------|
| AC1–AC7 | `CachingProductCatalogTest` | ✅ 20 tests pass |
| AC8 | `ProductCacheEndToEndTest` | ✅ `404`, `500` and timeout each reach the upstream once across two requests |
| AC9 | `git diff main` shows no change to `application`, `domain` or `web` | ✅ empty diff |
| AC10 | R2/R3 recorded here and in `SOLUTION.md` | ✅ |
| AC11 | `./mvnw clean verify`; SonarCloud analysis | ✅ 85 tests; Quality Gate passed, 0 issues, 96.5% coverage on new code |

Code review (2026-10-22) led to two changes:
- `ProductCacheProperties.Settings` rejects a missing or negative TTL when
  the configuration is bound. Before this, the application started and
  answered `500` to every request, while `main` failed at start-up. It is
  covered by `ProductCachePropertiesTest`.
- The path where a caller performs the load itself was extracted from
  `CachedLookup.get` into `lead`, with no change in behaviour.

## Approval gates

1. 🔶 Approve this spec, including:
   - the ambiguity assumption (a failed product is omitted for up to the
     failure TTL);
   - which failures are stored;
   - the change to SPEC-005's design (one cache of outcomes, per-entry
     TTL);
   - the TTL values.
2. Implementation plan, implementation and tests.
3. Measurements R2/R3, then **stop and report**.
4. `SOLUTION.md` update, then 🔶 review.
5. Mark Implemented; commit, PR.
