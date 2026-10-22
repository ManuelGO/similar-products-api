package com.manuelgo.similarproducts.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import com.manuelgo.similarproducts.domain.ProductCatalogException;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@link ProductCatalog} that reuses the answers of another catalogue for a bounded time.
 *
 * <p>Successful answers and known upstream failures ({@link ProductNotFoundException} and
 * {@link ProductCatalogException}, including timeouts) are kept, each kind for its own TTL; a kept failure is
 * rethrown as the same exception. Any other exception signals a bug, so it propagates and is never kept.
 * Concurrent lookups of the same key that is not cached share a single call to the delegate.
 */
public class CachingProductCatalog implements ProductCatalog {

    private final ProductCatalog delegate;
    private final CachedLookup<List<String>> similarIds;
    private final CachedLookup<Product> details;

    public CachingProductCatalog(ProductCatalog delegate, ProductCacheProperties properties, Ticker ticker) {
        this.delegate = delegate;
        this.similarIds = new CachedLookup<>(properties.similarIds(), ticker);
        this.details = new CachedLookup<>(properties.details(), ticker);
    }

    @Override
    public List<String> findSimilarIds(String productId) {
        // The cached list is shared by every request until it expires, so it must not be mutable.
        return similarIds.get(productId, id -> List.copyOf(delegate.findSimilarIds(id)));
    }

    @Override
    public Product findById(String productId) {
        return details.get(productId, delegate::findById);
    }

    /**
     * The answer to one lookup: the value, or the known failure to rethrow.
     */
    private sealed interface Outcome<V> {

        V get();
    }

    private record Success<V>(V value) implements Outcome<V> {

        @Override
        public V get() {
            return value;
        }
    }

    private record Failure<V>(RuntimeException failure) implements Outcome<V> {

        @Override
        public V get() {
            throw failure;
        }
    }

    /**
     * Outcomes in a Caffeine cache (per-entry expiry and size bound), plus the loads currently in flight so
     * that concurrent callers of the same key wait for one load instead of each calling the delegate.
     *
     * <p>The delegate is always called outside any lock, so a slow load for one key never blocks other keys.
     */
    private static final class CachedLookup<V> {

        private final ProductCacheProperties.Settings settings;
        private final Cache<String, Outcome<V>> outcomes;
        private final Map<String, CompletableFuture<Outcome<V>>> inFlight = new ConcurrentHashMap<>();

        CachedLookup(ProductCacheProperties.Settings settings, Ticker ticker) {
            this.settings = settings;
            this.outcomes = Caffeine.newBuilder()
                    .expireAfter(Expiry.<String, Outcome<V>>writing((key, outcome) -> ttlOf(outcome)))
                    .maximumSize(settings.maxSize())
                    .ticker(ticker)
                    .build();
        }

        V get(String key, Function<String, V> loader) {
            Outcome<V> cached = outcomes.getIfPresent(key);
            if (cached != null) {
                return cached.get();
            }

            CompletableFuture<Outcome<V>> load = new CompletableFuture<>();
            CompletableFuture<Outcome<V>> existing = inFlight.putIfAbsent(key, load);
            Outcome<V> outcome = existing != null ? join(existing) : lead(key, loader, load);
            return outcome.get();
        }

        /**
         * Runs the load this caller claimed, stores its outcome and hands it to the callers waiting on it.
         */
        private Outcome<V> lead(String key, Function<String, V> loader, CompletableFuture<Outcome<V>> load) {
            try {
                // Another caller may have stored an outcome between the cache check and claiming the load.
                Outcome<V> outcome = outcomes.getIfPresent(key);
                if (outcome == null) {
                    outcome = load(key, loader);
                    // An outcome whose TTL is zero expires at once, so it is never returned from the cache.
                    outcomes.put(key, outcome);
                }
                load.complete(outcome);
                return outcome;
            } catch (RuntimeException | Error e) {
                // Unexpected: fail the waiting callers with the same exception; nothing is stored.
                load.completeExceptionally(e);
                throw e;
            } finally {
                // Removed only after the outcome is stored, so a new caller always finds one or the other.
                inFlight.remove(key, load);
            }
        }

        private static <V> Outcome<V> load(String key, Function<String, V> loader) {
            try {
                return new Success<>(loader.apply(key));
            } catch (ProductNotFoundException | ProductCatalogException e) {
                return new Failure<>(e);
            }
        }

        private Duration ttlOf(Outcome<V> outcome) {
            if (outcome instanceof Failure<V> failure) {
                return failure.failure() instanceof ProductNotFoundException
                        ? settings.notFoundTtl()
                        : settings.failureTtl();
            }
            return settings.ttl();
        }

        /**
         * Waits for a load started by another caller, rethrowing an unexpected failure as the original exception.
         */
        private static <V> Outcome<V> join(CompletableFuture<Outcome<V>> load) {
            try {
                return load.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                if (e.getCause() instanceof Error cause) {
                    throw cause;
                }
                throw e;
            }
        }
    }
}
