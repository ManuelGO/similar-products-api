package com.manuelgo.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.manuelgo.similarproducts.config.ApplicationConfiguration;
import com.manuelgo.similarproducts.config.DetailFetchProperties;
import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs the service on the executor built by the production configuration.
 */
@ExtendWith(OutputCaptureExtension.class)
class SimilarProductsServiceConcurrencyTest {

    private final AtomicInteger submissions = new AtomicInteger();
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutDownExecutor() {
        executor.shutdown();
    }

    @Test
    void fetchesProductDetailsConcurrently() {
        List<String> ids = List.of("2", "3", "4");
        executor = executor(ids.size(), 0);
        CountDownLatch allStarted = new CountDownLatch(ids.size());

        // Every detail call waits until all of them have started. If the calls ran one
        // after another, the first one would never see the others start and would fail.
        ProductCatalog catalog = new StubCatalog(ids, id -> {
            allStarted.countDown();
            awaitOrFail(allStarted);
        });

        List<Product> result = new SimilarProductsService(catalog, executor).findSimilarProducts("1");

        assertThat(result).extracting(Product::id).containsExactlyElementsOf(ids);
    }

    @Test
    void neverRunsMoreDetailRequestsAtOnceThanThePoolSize() {
        int poolSize = 2;
        List<String> ids = List.of("2", "3", "4", "5", "6", "7");
        executor = executor(poolSize, ids.size());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        ProductCatalog catalog = new StubCatalog(ids, id -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            sleep(20); // keep the call in flight long enough for others to overlap
            inFlight.decrementAndGet();
        });

        List<Product> result = new SimilarProductsService(catalog, executor).findSimilarProducts("1");

        assertThat(result).extracting(Product::id).containsExactlyElementsOf(ids);
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(poolSize);
    }

    @Test
    void omitsProductsRejectedBySaturatedExecutorWithoutRunningThemOnTheRequestThread(CapturedOutput output) {
        // One thread and one queue slot: "2" runs, "3" waits in the queue, "4" is rejected.
        List<String> ids = List.of("2", "3", "4");
        executor = executor(1, 1);
        CountDownLatch allSubmitted = new CountDownLatch(1);
        Set<String> callingThreads = ConcurrentHashMap.newKeySet();
        Thread requestThread = Thread.currentThread();

        // The running call holds the only thread until every task has been submitted,
        // so the queue is still full when the third task arrives.
        ProductCatalog catalog = new StubCatalog(ids, id -> {
            callingThreads.add(Thread.currentThread().getName());
            awaitOrFail(allSubmitted);
        });
        Executor releaseAfterLastSubmission = task -> {
            try {
                executor.execute(task);
            } finally {
                if (submissions.incrementAndGet() == ids.size()) {
                    allSubmitted.countDown();
                }
            }
        };

        List<Product> result = new SimilarProductsService(catalog, releaseAfterLastSubmission)
                .findSimilarProducts("1");

        assertThat(result).extracting(Product::id).containsExactly("2", "3");
        assertThat(callingThreads).doesNotContain(requestThread.getName()).allMatch(name -> name.startsWith("detail-fetch-"));
        assertThat(output.getOut()).contains("WARN", "Omitting similar product 4 of product 1");
    }

    private static ThreadPoolTaskExecutor executor(int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ApplicationConfiguration()
                .detailFetchExecutor(new DetailFetchProperties(poolSize, queueCapacity));
        executor.initialize();
        return executor;
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record StubCatalog(List<String> similarIds, Consumer<String> onDetailRequest) implements ProductCatalog {

        @Override
        public List<String> findSimilarIds(String productId) {
            return similarIds;
        }

        @Override
        public Product findById(String productId) {
            onDetailRequest.accept(productId);
            return new Product(productId, "Product " + productId, BigDecimal.ONE, true);
        }
    }
}
