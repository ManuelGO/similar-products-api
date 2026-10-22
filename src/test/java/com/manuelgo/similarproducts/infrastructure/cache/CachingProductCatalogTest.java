package com.manuelgo.similarproducts.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import com.manuelgo.similarproducts.domain.ProductCatalogException;
import com.manuelgo.similarproducts.domain.ProductCatalogTimeoutException;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CachingProductCatalogTest {

    private static final Duration DETAILS_TTL = Duration.ofSeconds(30);
    private static final Duration SIMILAR_IDS_TTL = Duration.ofMinutes(5);
    private static final Duration NOT_FOUND_TTL = Duration.ofSeconds(20);
    private static final Duration FAILURE_TTL = Duration.ofSeconds(5);
    private static final Duration TEST_GUARD = Duration.ofSeconds(5);

    private final AtomicLong nanos = new AtomicLong();
    private final CountingCatalog upstream = new CountingCatalog();
    private final CachingProductCatalog catalog = catalogWithFailureTtls(NOT_FOUND_TTL, FAILURE_TTL);

    @Test
    void reusesAProductDetailWithinTheTtl() {
        Product first = catalog.findById("2");
        advance(DETAILS_TTL.minusNanos(1));
        Product second = catalog.findById("2");

        assertThat(second).isEqualTo(first);
        assertThat(upstream.detailCalls("2")).isEqualTo(1);
    }

    @Test
    void reusesSimilarIdsWithinTheTtl() {
        List<String> first = catalog.findSimilarIds("1");
        advance(SIMILAR_IDS_TTL.minusNanos(1));
        List<String> second = catalog.findSimilarIds("1");

        assertThat(second).isEqualTo(first).containsExactly("2", "3", "4");
        assertThat(upstream.similarIdsCalls("1")).isEqualTo(1);
    }

    @Test
    void sharesSimilarIdsAcrossCallersOnlyAsAnImmutableList() {
        List<String> ids = catalog.findSimilarIds("1");

        assertThatThrownBy(() -> ids.add("5")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(catalog.findSimilarIds("1")).containsExactly("2", "3", "4");
    }

    @Test
    void asksTheUpstreamAgainOnceTheTtlHasElapsed() {
        catalog.findById("2");
        catalog.findSimilarIds("1");
        advance(DETAILS_TTL);

        catalog.findById("2");
        catalog.findSimilarIds("1");
        assertThat(upstream.detailCalls("2")).isEqualTo(2);
        assertThat(upstream.similarIdsCalls("1")).isEqualTo(1);

        advance(SIMILAR_IDS_TTL);
        catalog.findSimilarIds("1");
        assertThat(upstream.similarIdsCalls("1")).isEqualTo(2);
    }

    static Stream<Arguments> knownFailures() {
        return Stream.of(
                Arguments.of(new ProductNotFoundException("2"), NOT_FOUND_TTL),
                Arguments.of(new ProductCatalogException("upstream error"), FAILURE_TTL),
                Arguments.of(new ProductCatalogTimeoutException("timed out", null), FAILURE_TTL));
    }

    @ParameterizedTest
    @MethodSource("knownFailures")
    void reusesAKnownDetailFailureForItsTtlAndThenStoresTheNextSuccess(RuntimeException failure, Duration ttl) {
        upstream.failDetail("2", failure);

        assertThatThrownBy(() -> catalog.findById("2")).isSameAs(failure);
        advance(ttl.minusNanos(1));
        assertThatThrownBy(() -> catalog.findById("2")).isSameAs(failure);
        assertThat(upstream.detailCalls("2")).isEqualTo(1);

        upstream.recoverDetail("2");
        advance(Duration.ofNanos(1));
        catalog.findById("2");
        advance(DETAILS_TTL.minusNanos(1));
        catalog.findById("2");
        assertThat(upstream.detailCalls("2")).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("knownFailures")
    void reusesAKnownSimilarIdsFailureForItsTtlAndThenStoresTheNextSuccess(RuntimeException failure, Duration ttl) {
        upstream.failSimilarIds("1", failure);

        assertThatThrownBy(() -> catalog.findSimilarIds("1")).isSameAs(failure);
        advance(ttl.minusNanos(1));
        assertThatThrownBy(() -> catalog.findSimilarIds("1")).isSameAs(failure);
        assertThat(upstream.similarIdsCalls("1")).isEqualTo(1);

        upstream.recoverSimilarIds("1");
        advance(Duration.ofNanos(1));
        catalog.findSimilarIds("1");
        advance(SIMILAR_IDS_TTL.minusNanos(1));
        catalog.findSimilarIds("1");
        assertThat(upstream.similarIdsCalls("1")).isEqualTo(2);
    }

    @Test
    void neverStoresAnUnexpectedFailure() {
        IllegalStateException bug = new IllegalStateException("bug");
        upstream.failDetail("2", bug);
        upstream.failSimilarIds("1", bug);

        assertThatThrownBy(() -> catalog.findById("2")).isSameAs(bug);
        assertThatThrownBy(() -> catalog.findById("2")).isSameAs(bug);
        assertThatThrownBy(() -> catalog.findSimilarIds("1")).isSameAs(bug);
        assertThatThrownBy(() -> catalog.findSimilarIds("1")).isSameAs(bug);
        assertThat(upstream.detailCalls("2")).isEqualTo(2);
        assertThat(upstream.similarIdsCalls("1")).isEqualTo(2);
    }

    static Stream<RuntimeException> knownFailureInstances() {
        return knownFailures().map(arguments -> (RuntimeException) arguments.get()[0]);
    }

    @ParameterizedTest
    @MethodSource("knownFailureInstances")
    void aZeroFailureTtlDisablesStoringThatFailure(RuntimeException failure) {
        CachingProductCatalog withoutFailureCache = catalogWithFailureTtls(Duration.ZERO, Duration.ZERO);
        upstream.failDetail("2", failure);
        upstream.failSimilarIds("1", failure);

        assertThatThrownBy(() -> withoutFailureCache.findById("2")).isSameAs(failure);
        assertThatThrownBy(() -> withoutFailureCache.findById("2")).isSameAs(failure);
        assertThatThrownBy(() -> withoutFailureCache.findSimilarIds("1")).isSameAs(failure);
        assertThatThrownBy(() -> withoutFailureCache.findSimilarIds("1")).isSameAs(failure);
        assertThat(upstream.detailCalls("2")).isEqualTo(2);
        assertThat(upstream.similarIdsCalls("1")).isEqualTo(2);
    }

    @Test
    void keepsTheDetailAndSimilarIdsCachesIndependent() {
        catalog.findSimilarIds("2");
        catalog.findById("2");
        catalog.findSimilarIds("2");
        catalog.findById("2");

        assertThat(upstream.similarIdsCalls("2")).isEqualTo(1);
        assertThat(upstream.detailCalls("2")).isEqualTo(1);
    }

    @Test
    void concurrentLookupsOfTheSameKeyShareOneUpstreamCall() {
        assertTimeoutPreemptively(TEST_GUARD, () -> {
            BlockedLoad load = blockDetail("2");
            List<Product> results = new CopyOnWriteArrayList<>();

            List<Thread> callers = startCallers(5, () -> results.add(catalog.findById("2")));
            load.awaitStarted();
            awaitAllWaiting(callers.subList(1, callers.size()));
            load.release();
            joinAll(callers);

            assertThat(results).hasSize(5).allMatch(product -> product.equals(results.get(0)));
            assertThat(upstream.detailCalls("2")).isEqualTo(1);
        });
    }

    @Test
    void everyCallerWaitingOnALoadThatFailsWithAKnownFailureReceivesItAndItIsStored() {
        ProductCatalogTimeoutException failure = new ProductCatalogTimeoutException("timed out", null);

        assertThat(failuresOfConcurrentCallers(failure)).hasSize(5).allMatch(e -> e == failure);
        assertThat(upstream.detailCalls("2")).isEqualTo(1);

        upstream.recoverDetail("2");
        assertThatThrownBy(() -> catalog.findById("2")).isSameAs(failure);
        assertThat(upstream.detailCalls("2")).isEqualTo(1);
    }

    static Stream<Throwable> unexpectedFailures() {
        return Stream.of(new IllegalStateException("bug"), new Error("simulated error"));
    }

    @ParameterizedTest
    @MethodSource("unexpectedFailures")
    void everyCallerWaitingOnALoadThatFailsUnexpectedlyReceivesItsFailureAndNothingIsStored(Throwable failure) {

        assertThat(failuresOfConcurrentCallers(failure)).hasSize(5).allMatch(e -> e == failure);
        assertThat(upstream.detailCalls("2")).isEqualTo(1);

        upstream.recoverDetail("2");
        catalog.findById("2");
        assertThat(upstream.detailCalls("2")).isEqualTo(2);
    }

    @Test
    void aSlowLoadDoesNotBlockLookupsOfOtherKeys() {
        assertTimeoutPreemptively(TEST_GUARD, () -> {
            BlockedLoad slow = blockDetail("2");
            List<Thread> slowCaller = startCallers(1, () -> catalog.findById("2"));
            slow.awaitStarted();

            // Would hang until the guard fires if the slow load held a lock other keys need.
            assertThat(catalog.findById("3").id()).isEqualTo("3");
            assertThat(catalog.findSimilarIds("1")).containsExactly("2", "3", "4");

            slow.release();
            joinAll(slowCaller);
        });
    }

    /**
     * Five concurrent lookups of product 2, sharing one upstream call that fails with the given failure.
     */
    private List<Throwable> failuresOfConcurrentCallers(Throwable failure) {
        return assertTimeoutPreemptively(TEST_GUARD, () -> {
            BlockedLoad load = blockDetail("2", failure);
            List<Throwable> failures = new CopyOnWriteArrayList<>();

            List<Thread> callers = startCallers(5, () -> {
                try {
                    catalog.findById("2");
                } catch (RuntimeException | Error e) {
                    failures.add(e);
                }
            });
            load.awaitStarted();
            awaitAllWaiting(callers.subList(1, callers.size()));
            load.release();
            joinAll(callers);
            return failures;
        });
    }

    private CachingProductCatalog catalogWithFailureTtls(Duration notFoundTtl, Duration failureTtl) {
        return new CachingProductCatalog(upstream, new ProductCacheProperties(
                new ProductCacheProperties.Settings(DETAILS_TTL, notFoundTtl, failureTtl, 100),
                new ProductCacheProperties.Settings(SIMILAR_IDS_TTL, notFoundTtl, failureTtl, 100)), nanos::get);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private BlockedLoad blockDetail(String id) {
        return blockDetail(id, null);
    }

    /**
     * @param failureAfterRelease an unchecked failure ({@link RuntimeException} or {@link Error}) to throw once
     *     released, or {@code null} to return the product
     */
    private BlockedLoad blockDetail(String id, Throwable failureAfterRelease) {
        BlockedLoad load = new BlockedLoad();
        upstream.onDetail(id, requestedId -> {
            load.started.countDown();
            await(load.released);
            if (failureAfterRelease instanceof Error error) {
                throw error;
            }
            if (failureAfterRelease != null) {
                throw (RuntimeException) failureAfterRelease;
            }
        });
        return load;
    }

    /**
     * Starts the first caller, waits until it is inside the upstream call, then starts the rest, so that
     * every other caller finds the load already in flight.
     */
    private List<Thread> startCallers(int count, Runnable call) {
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread caller = new Thread(call, "caller-" + i);
            callers.add(caller);
            caller.start();
            if (i == 0 && count > 1) {
                await(upstream.firstDetailCallStarted);
            }
        }
        return callers;
    }

    /**
     * Waits until every thread is parked: the only place a caller parks is while joining the in-flight load.
     */
    private static void awaitAllWaiting(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            while (thread.getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
                if (!thread.isAlive()) {
                    throw new AssertionError(thread.getName() + " finished without waiting for the load");
                }
            }
        }
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class BlockedLoad {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void awaitStarted() {
            await(started);
        }

        void release() {
            released.countDown();
        }
    }

    private static final class CountingCatalog implements ProductCatalog {

        private final Map<String, AtomicInteger> similarIdsCalls = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> detailCalls = new ConcurrentHashMap<>();
        private final Map<String, RuntimeException> similarIdsFailures = new ConcurrentHashMap<>();
        private final Map<String, RuntimeException> detailFailures = new ConcurrentHashMap<>();
        private final Map<String, Consumer<String>> detailHooks = new ConcurrentHashMap<>();
        private final CountDownLatch firstDetailCallStarted = new CountDownLatch(1);

        void failSimilarIds(String productId, RuntimeException failure) {
            similarIdsFailures.put(productId, failure);
        }

        void recoverSimilarIds(String productId) {
            similarIdsFailures.remove(productId);
        }

        void failDetail(String id, RuntimeException failure) {
            detailFailures.put(id, failure);
        }

        void recoverDetail(String id) {
            detailFailures.remove(id);
            detailHooks.remove(id);
        }

        void onDetail(String id, Consumer<String> hook) {
            detailHooks.put(id, hook);
        }

        int similarIdsCalls(String productId) {
            return similarIdsCalls.getOrDefault(productId, new AtomicInteger()).get();
        }

        int detailCalls(String id) {
            return detailCalls.getOrDefault(id, new AtomicInteger()).get();
        }

        @Override
        public List<String> findSimilarIds(String productId) {
            similarIdsCalls.computeIfAbsent(productId, key -> new AtomicInteger()).incrementAndGet();
            RuntimeException failure = similarIdsFailures.get(productId);
            if (failure != null) {
                throw failure;
            }
            // Mutable, like the list the JSON adapter returns.
            return new ArrayList<>(List.of("2", "3", "4"));
        }

        @Override
        public Product findById(String id) {
            detailCalls.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            firstDetailCallStarted.countDown();
            Consumer<String> hook = detailHooks.get(id);
            if (hook != null) {
                hook.accept(id);
            }
            RuntimeException failure = detailFailures.get(id);
            if (failure != null) {
                throw failure;
            }
            return new Product(id, "Product " + id, new BigDecimal("9.99"), true);
        }
    }
}
