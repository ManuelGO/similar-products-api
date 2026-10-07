package com.manuelgo.similarproducts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param poolSize      maximum number of product detail requests running at the same time
 * @param queueCapacity maximum number of detail requests waiting for a free thread; beyond it they are rejected
 */
@ConfigurationProperties("detail-fetch")
public record DetailFetchProperties(int poolSize, int queueCapacity) {
}
