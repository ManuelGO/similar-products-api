package com.manuelgo.similarproducts.infrastructure.productapi;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ProductApiProperties.class)
public class ProductApiConfiguration {

    @Bean
    public RestClient productApiRestClient(ProductApiProperties properties) {
        // The challenge upstream only speaks HTTP/1.1. Pinning the version keeps the
        // WireMock-based tests (which would otherwise accept an HTTP/2 upgrade) on the
        // same connection semantics as the real challenge environment.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }
}
