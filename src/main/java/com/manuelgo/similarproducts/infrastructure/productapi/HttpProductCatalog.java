package com.manuelgo.similarproducts.infrastructure.productapi;

import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link ProductCatalog} backed by the upstream Product API described in {@code existingApis.yaml}.
 */
@Component
public class HttpProductCatalog implements ProductCatalog {

    private static final ParameterizedTypeReference<List<String>> ID_LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public HttpProductCatalog(RestClient productApiRestClient) {
        this.restClient = productApiRestClient;
    }

    @Override
    public List<String> findSimilarIds(String productId) {
        return restClient.get()
                .uri("/product/{productId}/similarids", productId)
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (request, response) -> {
                    throw new ProductNotFoundException(productId);
                })
                .body(ID_LIST);
    }

    @Override
    public Product findById(String productId) {
        return restClient.get()
                .uri("/product/{productId}", productId)
                .retrieve()
                .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (request, response) -> {
                    throw new ProductNotFoundException(productId);
                })
                .body(ProductApiResponse.class)
                .toProduct();
    }
}
