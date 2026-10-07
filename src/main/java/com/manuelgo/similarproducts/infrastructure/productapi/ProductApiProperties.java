package com.manuelgo.similarproducts.infrastructure.productapi;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl        base URL of the upstream Product API
 * @param connectTimeout budget for establishing a connection to the upstream
 * @param readTimeout    budget for receiving the upstream response
 */
@ConfigurationProperties("product-api")
public record ProductApiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
