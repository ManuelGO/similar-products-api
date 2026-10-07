package com.manuelgo.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.manuelgo.similarproducts.config.ApplicationConfiguration;
import com.manuelgo.similarproducts.config.DetailFetchProperties;
import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs the service on the executor built by the production configuration.
 */
class SimilarProductsServiceConcurrencyTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutDownExecutor() {
        executor.shutdown();
    }

    @Test
    void fetchesProductDetailsConcurrently() {
        List<String> ids = List.of("2", "3", "4");
        executor = executorWithPoolSize(ids.size());
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
        executor = executorWithPoolSize(poolSize);
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

    private static ThreadPoolTaskExecutor executorWithPoolSize(int poolSize) {
        ThreadPoolTaskExecutor executor = new ApplicationConfiguration()
                .detailFetchExecutor(new DetailFetchProperties(poolSize));
        executor.initialize();
        return executor;
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Detail requests did not run concurrently");
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
