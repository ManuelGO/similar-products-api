package com.manuelgo.similarproducts.config;

import com.manuelgo.similarproducts.application.SimilarProductsService;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the Spring-free application layer: the use case and the executor it fetches details on.
 */
@Configuration
@EnableConfigurationProperties(DetailFetchProperties.class)
public class ApplicationConfiguration {

    @Bean
    public ThreadPoolTaskExecutor detailFetchExecutor(DetailFetchProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Fixed size: at most poolSize detail requests run concurrently.
        executor.setCorePoolSize(properties.poolSize());
        executor.setMaxPoolSize(properties.poolSize());
        // Bounded backlog: when the pool and the queue are full, new tasks are rejected
        // immediately instead of waiting, and the service omits those products.
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("detail-fetch-");
        return executor;
    }

    @Bean
    public SimilarProductsService similarProductsService(ProductCatalog productCatalog,
                                                         ThreadPoolTaskExecutor detailFetchExecutor) {
        return new SimilarProductsService(productCatalog, detailFetchExecutor);
    }
}
