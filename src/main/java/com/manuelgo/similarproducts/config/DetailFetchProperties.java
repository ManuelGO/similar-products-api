package com.manuelgo.similarproducts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param poolSize maximum number of product detail requests running at the same time
 */
@ConfigurationProperties("detail-fetch")
public record DetailFetchProperties(int poolSize) {
}
