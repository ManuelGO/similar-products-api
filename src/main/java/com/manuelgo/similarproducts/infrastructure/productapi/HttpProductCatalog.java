package com.manuelgo.similarproducts.infrastructure.productapi;

import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import com.manuelgo.similarproducts.domain.ProductCatalogException;
import com.manuelgo.similarproducts.domain.ProductCatalogTimeoutException;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link ProductCatalog} backed by the upstream Product API described in {@code existingApis.yaml}.
 *
 * <p>Every upstream failure is translated into the domain exceptions declared by the port.
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
        List<String> ids = call(productId, "similar ids", () -> restClient.get()
                .uri("/product/{productId}/similarids", productId)
                .retrieve()
                .body(ID_LIST));

        if (ids == null || ids.stream().anyMatch(HttpProductCatalog::isBlank)) {
            throw new ProductCatalogException("Malformed similar ids of product " + productId + ": " + ids);
        }
        return ids;
    }

    @Override
    public Product findById(String productId) {
        ProductApiResponse response = call(productId, "detail", () -> restClient.get()
                .uri("/product/{productId}", productId)
                .retrieve()
                .body(ProductApiResponse.class));

        if (response == null) {
            throw new ProductCatalogException("Empty detail of product " + productId);
        }
        return response.toProduct();
    }

    /**
     * Runs an upstream request and translates its failures, based only on the observed exception types:
     * <ul>
     *   <li>{@code 404} → {@link ProductNotFoundException};</li>
     *   <li>{@link HttpTimeoutException} in the cause chain (connect timeout, or read timeout before the
     *       response headers arrived) → {@link ProductCatalogTimeoutException};</li>
     *   <li>anything else RestClient reports (other error statuses, I/O errors, unreadable or truncated
     *       bodies, including a body cut off by the read timeout) → {@link ProductCatalogException}.</li>
     * </ul>
     */
    private static <T> T call(String productId, String resource, Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductNotFoundException(productId);
        } catch (RestClientException e) {
            if (hasCause(e, HttpTimeoutException.class)) {
                throw new ProductCatalogTimeoutException(
                        "Timed out getting " + resource + " of product " + productId, e);
            }
            throw new ProductCatalogException(
                    "Failed to get " + resource + " of product " + productId + ": " + e.getMessage(), e);
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
