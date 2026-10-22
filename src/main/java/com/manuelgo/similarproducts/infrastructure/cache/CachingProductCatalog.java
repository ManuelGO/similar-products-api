package com.manuelgo.similarproducts.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@link ProductCatalog} that reuses successful answers of another catalogue for a bounded time.
 *
 * <p>Failures are never cached: they propagate unchanged and the next lookup asks the delegate again.
 * Concurrent lookups of the same uncached key share a single call to the delegate.
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
     * Successful values in a Caffeine cache (expiry and size bound), plus the loads currently in flight so
     * that concurrent callers of the same key wait for one load instead of each calling the delegate.
     *
     * <p>The delegate is always called outside any lock, so a slow load for one key never blocks other keys.
     */
    private static final class CachedLookup<V> {

        private final Cache<String, V> values;
        private final Map<String, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

        CachedLookup(ProductCacheProperties.Settings settings, Ticker ticker) {
            this.values = Caffeine.newBuilder()
                    .expireAfterWrite(settings.ttl())
                    .maximumSize(settings.maxSize())
                    .ticker(ticker)
                    .build();
        }

        V get(String key, Function<String, V> loader) {
            V cached = values.getIfPresent(key);
            if (cached != null) {
                return cached;
            }

            CompletableFuture<V> load = new CompletableFuture<>();
            CompletableFuture<V> existing = inFlight.putIfAbsent(key, load);
            if (existing != null) {
                return join(existing);
            }
            try {
                // Another caller may have stored the value between the cache check and claiming the load.
                V value = values.getIfPresent(key);
                if (value == null) {
                    value = loader.apply(key);
                    values.put(key, value);
                }
                load.complete(value);
                return value;
            } catch (RuntimeException | Error e) {
                // Fail the waiting callers with the same exception; nothing is stored.
                load.completeExceptionally(e);
                throw e;
            } finally {
                // Removed only after the value is stored, so a new caller always finds one or the other.
                inFlight.remove(key, load);
            }
        }

        /**
         * Waits for a load started by another caller, rethrowing its failure as the original exception.
         */
        private static <V> V join(CompletableFuture<V> load) {
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
