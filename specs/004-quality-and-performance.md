# SPEC-004 — Quality and performance

Status: **Implemented** (AC1–AC9 verified on 2026-10-07; `SOLUTION.md` approved and merged in PR #5)

> **Later change:** caching, listed here as "not a lever" and out of scope,
> was raised as a separate decision and added by SPEC-005 and SPEC-006, with
> new k6 runs (R0–R3). `SOLUTION.md` now describes it.

## Goal

Verify the complete solution against the challenge and the project's own
quality bar, using measurements rather than assumptions:

1. measure performance with the challenge's own k6 test;
2. decide, from evidence, whether timeout, pool or queue values need
   tuning;
3. review static-analysis findings and test coverage;
4. re-verify the implementation against the original API contracts and
   every spec's acceptance criteria;
5. document the solution for reviewers.

This spec introduces no new features.

## Source requirements

- `readme.md`:
  - "exposes the agreed REST API on port 5000";
  - "You can run the same test we will put through your application";
  - evaluation covers code clarity and maintainability, **performance**
    and **resilience**.
- `shared/k6/test.js`, `docker-compose.yaml`: the performance test and its
  infrastructure (Simulado, InfluxDB, Grafana, k6).
- `CLAUDE.md`, *Before considering the complete solution finished*: full
  build and test suite, static analysis, SonarQube review, coverage
  review, challenge performance tests, contract verification, review of
  every spec.
- SPEC-002 and SPEC-003: pool size, queue capacity and timeouts are
  provisional and to be measured and tuned here; an overall request
  deadline may be reconsidered if measurements justify it.

## Prerequisites (human action required)

| # | Prerequisite | Why |
|---|--------------|-----|
| P1 ✅ approved: the user frees the port before the benchmark | Free host port 5000: disable macOS AirPlay Receiver (System Settings → General → AirDrop & Handoff) | `test.js` targets `host.docker.internal:5000` and must not be modified. Currently Control Center holds the port. |
| P2 (must be fixed before Part 3; does not block Part 1) | A valid SonarCloud token for the MCP server (`SONARQUBE_TOKEN`, `SONARQUBE_ORG`) | The MCP integration currently answers "Not authorized". |
| P3 ✅ Option A chosen (local Maven analysis; no CI, no automatic analysis, scanner not added to the build) | Decision on how the project is analysed in SonarCloud (see *Static analysis*) | Analysis uploads the source code to an external service. |

## Part 1 — Performance measurement

### Method

1. Build the jar with `./mvnw clean verify`.
2. Start the infrastructure as instructed by the readme:
   `docker compose up -d simulado influxdb grafana`.
3. Run the application with its **default configuration** on port 5000
   (`java -jar target/similar-products-api-0.0.1-SNAPSHOT.jar`), logging
   to a file.
4. Run the unmodified test: `docker compose run --rm k6 run scripts/test.js`.
5. Collect:
   - **per scenario** (normal, notFound, error, slow, verySlow), queried
     from InfluxDB using k6's `scenario` tag: request count, throughput,
     latency p50 / p95 / p99 / max, and the distribution of HTTP status
     codes;
   - the k6 end-of-test summary;
   - from the application log: the number of omitted products by cause
     (not found, upstream error, timeout, **executor rejection**), and any
     `500`.
   - A Grafana dashboard screenshot is optional; the numbers come from
     InfluxDB queries so they can be reproduced.
6. Record, for every run:
   - git commit SHA (and whether the working tree was clean);
   - application configuration (effective values of all
     `product-api.*` and `detail-fetch.*` properties);
   - date and time;
   - the exact k6 command;
   - whether it is the **baseline** or a **tuned** run (and which change
     it evaluates).
7. Record the results in the solution README (Part 5).

### Useful measurements

| Measurement | Why it matters |
|-------------|----------------|
| Status codes per scenario | The test has no thresholds. For the supplied k6 scenarios, `200` is the expected top-level status: every root product exists, and detail failures are handled as partial results. Any `5xx` **in this benchmark** therefore requires investigation. This is not a general rule that every `5xx` from the API is a defect (e.g. `502`/`504` are correct responses to similar-ids failures). |
| Latency per scenario | normal / notFound / error should stay near the upstream latency; slow / verySlow should stay near the read timeout. |
| Products omitted by **executor rejection** | Distinguishes intended partial results (upstream failed) from products lost to our own capacity limits. |
| Throughput | Compared between runs when a tuning change is evaluated. |

**Not used as targets:** arbitrary latency or throughput numbers. The
test defines none, and inventing one would be optimising for a number
rather than a requirement.

### Expected outcome (hypothesis to verify, not a result)

A back-of-envelope estimate suggests the **slow** and **verySlow**
scenarios will saturate the detail executor at its initial size:

- 200 virtual users, each pausing 0.5 s between requests, with responses
  bounded near the 1 s read timeout: roughly 130 requests/s.
- Each slow-scenario request needs 2 detail calls that hold a thread for
  about 1 s (products `100` and `1000`): roughly 260 thread-seconds of
  work per second.
- The pool has 32 threads and a queue of 32.

This calculation is **strictly a hypothesis**. Nothing is tuned on the
basis of it; only measured evidence can justify a change.

If this holds, most detail tasks will be **rejected**. Responses will be
fast but mostly empty, including products such as `3` that the upstream
serves quickly. That would be capacity loss, not an upstream failure,
and is exactly what the measurement must confirm or refute.

### Environment caveat

App, mocks, InfluxDB and k6 all run on one laptop. On this Apple Silicon
machine the `loadimpact/k6:0.28.0` image may run under emulation. Results
are therefore **indicative and comparative** (before/after on the same
machine), not absolute capacity figures.

## Part 2 — Tuning gate

Tuning happens **only** if Part 1 shows a concrete problem, such as:

- any `5xx` in any scenario;
- products omitted because of executor rejection;
- latency in the fast scenarios (normal / notFound / error) clearly above
  the upstream latency, which would indicate queueing.

For each problem found, a short proposal is presented for approval before
any change. It contains the measured symptom, the lever, the reasoning
and the trade-off. Candidate levers, none of them decided:

| Lever | Trade-off |
|-------|-----------|
| `detail-fetch.pool-size` | More concurrent upstream calls and more threads, versus fewer rejections. Sized with Little's law (threads needed ≈ call rate × call duration) from measured rates, not chosen to make the k6 numbers look good. |
| `detail-fetch.queue-capacity` | More waiting, versus fewer rejections (see SPEC-003). |
| `product-api.read-timeout` | Holding threads for less time, versus omitting more slow-but-valid products. |
| An overall per-request deadline | Explicitly out of scope in SPEC-003; reconsidered only if measurements show unbounded tail latency. |

After any approved change: `./mvnw clean verify`, a new k6 run, and
before/after numbers recorded side by side.

**Not levers:** caching, retries, circuit breaker, reactive rewrite
(rejected in the analysis). If the evidence suggests one of them is
needed, it is raised as a separate decision, not slipped in as tuning.

## Parts 1–2 — Results and decisions (completed)

All runs used the unmodified `docker compose run --rm k6 run scripts/test.js`
on 2026-10-07. The app, Simulado, InfluxDB and an amd64 k6 image (emulated
on Apple Silicon) shared one laptop with 10 cores. Connect timeout 500 ms
and read timeout 1 s in every run. Every run returned only `200`, with no
`5xx` and no unexpected `500`.

| Run | Git / config | k6 window (UTC) | Role |
|-----|--------------|-----------------|------|
| Baseline | `3ffc6bc`, clean; pool 32 / queue 32 | 18:55:01–18:56:05 | baseline |
| Tuned (T1) | `3ffc6bc` + uncommitted pool 64 / queue 64 | 19:01:20–19:02:24 | tuned run evaluating T1 |
| Diagnostic | same as tuned, plus OS/Docker/JVM samplers | 19:08:29–19:09:33 | **diagnostic evidence only** |

### Baseline (32/32) vs tuned (64/64)

| Scenario | req/s | p50 ms | p95 ms | p99 ms | Executor rejection (% of lookups) | Products returned / request |
|----------|-------|--------|--------|--------|-----------------------------------|-----------------------------|
| normal | 324 → 264 | 111 → 267 | 193 → 339 | 258 → 470 | 39.4% → 14.6% | 1.82 → 2.56 (max 3) |
| notFound | 372 → 265 | 39 → 264 | 101 → 320 | 145 → 386 | 8.6% → 20.6% | 1.84 → 1.67 (max 2) |
| error | 339 → 255 | 91 → 290 | 154 → 367 | 227 → 521 | 14.9% → 19.8% | 1.74 → 1.71 (max 2) |
| slow | 315 → 250 | 6 → 6 | 1229 → 1502 | 1528 → 1715 | 90.8% → 81.2% | 0.19 → 0.32 (max 3) |
| verySlow | 339 → 305 | 16 → 10 | 136 → 1999 | 2021 → 2020 | 96.5% → 92.3% | 0.00 → 0.00 (max 3) |

Total executor rejections: 24,706 → 18,856. Total requests:
16,890 → 13,394.

### Diagnostic evidence (64/64)

The sampling itself slowed the system (for example, normal p50 403 ms
versus 267 ms in the tuned run). The diagnostic run's absolute numbers are
therefore **not** compared with the baseline or tuned runs; they are used
only as evidence about where the time goes.

- **Fast scenarios (normal, notFound, error):**
  - Simulado was pinned at about one full core (100.5–101.5% in
    `docker stats`).
  - Its own latency for endpoints with no artificial delay, measured
    directly, rose from p50 12.5 ms idle to 254–383 ms under load. Connect
    time stayed around 1 ms.
  - All 64 executor workers were busy, blocked waiting on the upstream, in
    every sample.
  - The Java process used at most about 1.5 cores, and the host always
    had at least 24% CPU idle.
  - Conclusion: upstream (mock) saturation is the root cause; application
    queueing and rejection follow from it.
- **Slow scenarios (slow, verySlow):**
  - Simulado answered no-delay requests in 5–7 ms using about half a core,
    so it was not saturated.
  - The executor workers were held by the deliberately delayed calls until
    the read timeout.
  - Conclusion: the bounded executor's capacity is the limiting factor.

### Decisions

- **Final configuration: pool 32 / queue 32, connect timeout 500 ms, read
  timeout 1 s** (64/64 reverted).
  - This does not show that 32/32 is optimal for production. It shows that
    64/64 does not improve the supplied benchmark overall.
  - In the fast scenarios, more client concurrency puts more pressure on
    an upstream limited to one core and adds queueing, without adding
    upstream capacity.
  - In the slow scenarios, a bigger pool accepts more slow work but
    produces almost no extra useful results (none in verySlow), while
    increasing waiting and timeout work.
- **T2, resolved:** executor saturation under the deliberately slow
  benchmark scenarios is an **accepted and documented limitation** of the
  blocking, bounded design.
  - The executor is not sized to hundreds of platform threads to
    eliminate rejections.
  - This is a deliberate overload-protection trade-off, not a claim that
    dropping otherwise valid detail lookups is desirable.
- **Future production considerations** (documented, not implemented):
  - size concurrency from the production workload and upstream capacity;
  - monitor executor saturation, queue depth, upstream latency and the
    partial-result rate;
  - reconsider the concurrency model if a sustained workload needs many
    more simultaneous slow I/O operations;
  - in that context, Java 21 virtual threads or non-blocking I/O could be
    evaluated; they are intentionally outside this exercise.

AC1, AC2 and AC3 are satisfied: the baseline is recorded; T1 was evaluated
with before/after data and rejected; the remaining slow-scenario
saturation is documented as an accepted limitation; and no value changed
in the final state.

## Part 3 — Static analysis and coverage

### Static analysis (SonarCloud)

The MCP integration reads analysis results; it does not produce them. A
project analysis must first be submitted to SonarCloud. Options:

| Option | Coverage in Sonar | Changes to the repo | Notes |
|--------|-------------------|---------------------|-------|
| **A. Local scanner run** (recommended) | Yes (JaCoCo XML, already generated by `verify`) | None, or `sonar.projectKey` / `sonar.organization` as `pom.xml` properties | `./mvnw verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar` with the token from the environment. The plugin is invoked by its full coordinates, not added as a build dependency. Uploads the source and results to SonarCloud; the repository is public anyway. |
| B. SonarCloud automatic analysis (GitHub app) | No | None | Runs on SonarCloud's side for every push; coverage cannot be imported. |
| C. CI workflow (GitHub Actions) | Yes | New workflow file and repository secret | Repeatable, but CI is not a challenge requirement. Proposed only if you want it. |

Review rules (from `CLAUDE.md`):
- explain each meaningful finding;
- fix justified findings, prioritising correctness, reliability,
  security and maintainability;
- never suppress a finding or change correct code just to satisfy a
  metric;
- any finding left open is listed with its technical reason.

### Coverage

- Review the JaCoCo report for **behaviour** without a test, not for a
  percentage. The current state is 98.4% of instructions and 100% of
  branches; the only uncovered code is the `main` method.
- No coverage threshold is added to the build.

## Part 3 — Results (completed)

Analysis run on 2026-10-07 (SonarCloud task `AaEX2EnozfbXOMBUAWIG`, analysed at
19:29:52 UTC) with option A:

```
./mvnw verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.organization=manuelgo -Dsonar.projectKey=ManuelGO_similar-products-api
```

- Scanner: `sonar-maven-plugin` **5.8.0.7211**, resolved at run time. It is
  deliberately not pinned in the command or added to `pom.xml`.
- Token supplied from the environment only; SonarCloud Automatic Analysis
  disabled.
- Code analysed: commit `3ffc6bc`, recorded as SonarCloud branch `main`.
- Quality Gate **passed**: all 6 conditions OK.
- 0 bugs, 0 vulnerabilities, 0 security hotspots, 0 code smells (0 min
  technical debt).
- Reliability, security, security review and maintainability ratings all A.
- Coverage 98.7%: lines 98.2% (2 of 111 uncovered), conditions 100%.
- Duplications 0.0%.
- One earlier Automatic Analysis issue on the challenge file
  `shared/k6/test.js` (`javascript:S6861`) was closed automatically,
  because the Maven scan analyses only `src/`. It was not suppressed.
- Uncovered code: only the body of `SimilarProductsApplication.main()`.
  Decision: no test is added solely to raise coverage.
- No findings required changes; nothing was suppressed (AC4, AC5).

## Part 4 — Final verification

1. `./mvnw clean verify` on a clean checkout: all tests pass, no build
   warnings.
2. **Contract check** against `similarProducts.yaml`, item by item:
   - path, method and port;
   - response media type;
   - array of `ProductDetail` with exactly `id`, `name`, `price`,
     `availability`, their types and required-ness, and `minLength` for
     `id` and `name`;
   - `uniqueItems`, similarity order, `minItems: 0`;
   - `404` for an unknown product.
   This also covers the upstream interpretation of `existingApis.yaml`,
   including numeric ids.
3. **Spec review:** re-check every acceptance criterion of SPEC-001 to
   SPEC-004 against the final code and tests, including the port 5000
   smoke test left pending since SPEC-001.
4. Static analysis and coverage review completed (Part 3).

## Part 4 — Results (completed)

- `./mvnw clean verify`: 56 tests, 0 failures, 0 errors, 0 skipped; no
  Maven, compiler or JVM warnings.
- The contract check against `similarProducts.yaml` passed on every item;
  the interpretation of `existingApis.yaml` (numeric ids, `similarids`
  `404`) is covered by tests.
- Every acceptance criterion of SPEC-001 to SPEC-004 was re-verified. The
  port-5000 smoke test is satisfied by the Part 1 runs on the final
  commit.
- Final configuration: 32 / 32, `500ms` / `1s`. No 64/64 change remains.
- Challenge files are unchanged since the original challenge commit.
- Documentation corrections D1 (SPEC-002 class name) and D2 (SPEC-001
  smoke-test status) applied.

## Part 5 — Solution README

`readme.md` is a challenge file and must not be modified. A new
**`SOLUTION.md`** at the repository root documents the solution for
reviewers. Because `specs/` is not tracked in git, this is the reviewers'
only written record of the design decisions.

Contents, kept concise:
- requirements: JDK 17, Docker; how to build, test and run (including
  the port 5000 / AirPlay note);
- configuration properties and their initial values with a one-line
  rationale each;
- architecture overview (ports and adapters, request flow);
- behaviour decisions and documented assumptions:
  - partial results;
  - `502` / `504`;
  - empty error bodies;
  - Spring's own request errors (`404` / `405`) kept;
  - numeric upstream ids;
- observed timeout semantics;
- performance results (Part 1 and any tuning in Part 2);
- **final configuration first.** If tuning occurred, also the baseline
  values and the measured evidence that justified each change;
- known limitations and deliberately excluded features (no cache, no
  retries, no circuit breaker, no overall deadline), with the reasons.

## Acceptance criteria

1. **AC1** — The unmodified k6 test has been run against the application
   on port 5000 with its default configuration, and per-scenario results
   (status codes, latency percentiles, throughput, omission causes) are
   recorded.
2. **AC2** — Every problem found by AC1 is either resolved by an approved
   change with before/after measurements, or documented as an accepted
   limitation with its reason.
3. **AC3** — No configuration value is changed without measured evidence
   and approval.
4. **AC4** — SonarCloud analysis has been run, and every reported issue is
   fixed or listed with a technical justification. Nothing is suppressed
   to obtain a clean report.
5. **AC5** — The coverage report has been reviewed for untested behaviour,
   and the findings are reported.
6. **AC6** — The contract check (Part 4.2) passes on every item.
7. **AC7** — Every acceptance criterion of SPEC-001 to SPEC-004 has been
   re-verified, including the port 5000 smoke test.
8. **AC8** — `SOLUTION.md` exists with the contents listed in Part 5, and
   the challenge files are unchanged.
9. **AC9** — `./mvnw clean verify` passes on the final state.

## Out of scope

- New features or endpoints.
- Caching, retries, circuit breaker, reactive rewrite.
- Containerising the application.
- CI pipeline, unless option C is explicitly chosen.
- Modifying `readme.md`, the OpenAPI files, `docker-compose.yaml` or
  `shared/`.

## Technical decisions

- **Results recorded in `SOLUTION.md`**, because specs are not tracked
  in git.
- **InfluxDB queries for per-scenario numbers:** the k6 0.28 end-of-test
  summary aggregates all scenarios together; the `scenario` tag in
  InfluxDB separates them.
- **No new production dependencies.** The Sonar scanner, if option A is
  chosen, is invoked by its full coordinates and not added to the build.

## Execution order and approval gates

1. 🔶 Approve this spec; resolve P1–P3.
2. Part 1: baseline k6 run, then **stop and report the results**.
3. 🔶 Part 2: approve or reject each tuning proposal, if any; re-measure.
4. Part 3: run Sonar, review findings and coverage, then **stop and
   report**; 🔶 approve the fixes.
5. Part 4: final verification.
6. Part 5: write `SOLUTION.md`; 🔶 review.
7. Mark Implemented; commit, PR.
