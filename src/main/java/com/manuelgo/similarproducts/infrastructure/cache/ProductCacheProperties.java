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
     * @param ttl     how long a successful answer is reused, measured from when it was obtained
     * @param maxSize maximum number of entries kept
     */
    public record Settings(Duration ttl, long maxSize) {
    }
}
