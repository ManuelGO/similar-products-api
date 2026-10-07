package com.manuelgo.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimilarProductsServiceTest {

    private final InMemoryCatalog catalog = new InMemoryCatalog();
    // Runs each task on the calling thread, so these tests are deterministic.
    private final SimilarProductsService service = new SimilarProductsService(catalog, Runnable::run);

    @Test
    void returnsSimilarProductsInSimilarityOrder() {
        catalog.similarIds("1", "3", "2", "4");
        catalog.products("2", "3", "4");

        List<Product> result = service.findSimilarProducts("1");

        assertThat(result).extracting(Product::id).containsExactly("3", "2", "4");
    }

    @Test
    void returnsEmptyListWithoutFetchingDetailsWhenThereAreNoSimilarIds() {
        catalog.similarIds("1");

        List<Product> result = service.findSimilarProducts("1");

        assertThat(result).isEmpty();
        assertThat(catalog.detailRequests).isEmpty();
    }

    @Test
    void returnsEachProductOnceInThePositionOfItsFirstOccurrence() {
        catalog.similarIds("1", "2", "3", "2", "4", "3");
        catalog.products("2", "3", "4");

        List<Product> result = service.findSimilarProducts("1");

        assertThat(result).extracting(Product::id).containsExactly("2", "3", "4");
        assertThat(catalog.detailRequests).containsExactly("2", "3", "4");
    }

    @Test
    void propagatesNotFoundWhenTheRequestedProductDoesNotExist() {
        assertThatThrownBy(() -> service.findSimilarProducts("unknown"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    private static final class InMemoryCatalog implements ProductCatalog {

        private final Map<String, List<String>> similarIds = new HashMap<>();
        private final Map<String, Product> products = new HashMap<>();
        private final List<String> detailRequests = new ArrayList<>();

        void similarIds(String productId, String... ids) {
            similarIds.put(productId, List.of(ids));
        }

        void products(String... ids) {
            for (String id : ids) {
                products.put(id, new Product(id, "Product " + id, new BigDecimal("9.99"), true));
            }
        }

        @Override
        public List<String> findSimilarIds(String productId) {
            List<String> ids = similarIds.get(productId);
            if (ids == null) {
                throw new ProductNotFoundException(productId);
            }
            return ids;
        }

        @Override
        public Product findById(String productId) {
            detailRequests.add(productId);
            return products.get(productId);
        }
    }
}
