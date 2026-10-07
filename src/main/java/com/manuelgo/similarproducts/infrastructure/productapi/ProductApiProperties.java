package com.manuelgo.similarproducts.infrastructure.productapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("product-api")
public record ProductApiProperties(String baseUrl) {
}
