package com.manuelgo.similarproducts.infrastructure.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param details    cache of product details
 * @param similarIds cache of similar product ids
 */
@ConfigurationProperties("product-cache")
public record ProductCacheProperties(Settings details, Settings similarIds) {

    /**
     * Every TTL is measured from when the answer was obtained; {@code 0s} means that kind of answer is not kept.
     *
     * @param ttl         how long a successful answer is reused
     * @param notFoundTtl how long a "product not found" answer is reused
     * @param failureTtl  how long an upstream error or timeout is reused
     * @param maxSize     maximum number of entries kept, of every kind
     */
    public record Settings(Duration ttl, Duration notFoundTtl, Duration failureTtl, long maxSize) {

        /**
         * Rejects a missing or negative TTL when the configuration is bound, so the application fails at start-up
         * rather than on the first lookup that would store an entry with it.
         */
        public Settings {
            requireValidTtl("ttl", ttl);
            requireValidTtl("not-found-ttl", notFoundTtl);
            requireValidTtl("failure-ttl", failureTtl);
        }

        private static void requireValidTtl(String name, Duration ttl) {
            if (ttl == null || ttl.isNegative()) {
                throw new IllegalArgumentException("Cache " + name + " must be zero or positive, but was " + ttl);
            }
        }
    }
}
