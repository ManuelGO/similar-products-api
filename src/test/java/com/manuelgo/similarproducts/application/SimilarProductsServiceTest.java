package com.manuelgo.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import com.manuelgo.similarproducts.domain.ProductCatalogException;
import com.manuelgo.similarproducts.domain.ProductCatalogTimeoutException;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void propagatesSimilarIdsFailure() {
        catalog.failSimilarIds("1", new ProductCatalogException("upstream error"));

        assertThatThrownBy(() -> service.findSimilarProducts("1"))
                .isExactlyInstanceOf(ProductCatalogException.class);
    }

    @Test
    void propagatesSimilarIdsTimeout() {
        catalog.failSimilarIds("1", new ProductCatalogTimeoutException("timed out", null));

        assertThatThrownBy(() -> service.findSimilarProducts("1"))
                .isInstanceOf(ProductCatalogTimeoutException.class);
    }

    @Test
    void omitsProductsWhoseDetailFailsAndKeepsTheOrderOfTheRest(CapturedOutput output) {
        catalog.similarIds("1", "2", "3", "4", "5", "6");
        catalog.products("3", "5");
        catalog.failDetail("2", new ProductNotFoundException("2"));
        catalog.failDetail("4", new ProductCatalogException("Failed to get detail of product 4: 500"));
        catalog.failDetail("6", new ProductCatalogTimeoutException("Failed to get detail of product 6: timed out", null));

        List<Product> result = service.findSimilarProducts("1");

        assertThat(result).extracting(Product::id).containsExactly("3", "5");
        assertThat(output.getOut())
                .contains("WARN", "Omitting similar product 2 of product 1: Product not found: 2")
                .contains("Omitting similar product 4 of product 1: Failed to get detail of product 4: 500")
                .contains("Omitting similar product 6 of product 1: Failed to get detail of product 6: timed out")
                .doesNotContain("\tat ");
    }

    @Test
    void omitsAFailingDuplicateIdOnlyOnce(CapturedOutput output) {
        catalog.similarIds("1", "2", "2");
        catalog.failDetail("2", new ProductNotFoundException("2"));

        assertThat(service.findSimilarProducts("1")).isEmpty();
        assertThat(output.getOut().split("Omitting similar product 2 ", -1)).hasSize(2);
    }

    @Test
    void returnsEmptyListWhenEveryDetailFails() {
        catalog.similarIds("1", "2", "3");
        catalog.failDetail("2", new ProductNotFoundException("2"));
        catalog.failDetail("3", new ProductCatalogException("upstream error"));

        assertThat(service.findSimilarProducts("1")).isEmpty();
    }

    @Test
    void propagatesUnexpectedDetailFailuresInsteadOfOmittingTheProduct() {
        catalog.similarIds("1", "2", "3");
        catalog.products("3");
        IllegalStateException bug = new IllegalStateException("bug");
        catalog.failDetail("2", bug);

        assertThatThrownBy(() -> service.findSimilarProducts("1"))
                .isInstanceOf(CompletionException.class)
                .hasCause(bug);
    }

    private static final class InMemoryCatalog implements ProductCatalog {

        private final Map<String, List<String>> similarIds = new HashMap<>();
        private final Map<String, RuntimeException> similarIdsFailures = new HashMap<>();
        private final Map<String, Product> products = new HashMap<>();
        private final Map<String, RuntimeException> detailFailures = new HashMap<>();
        private final List<String> detailRequests = new ArrayList<>();

        void similarIds(String productId, String... ids) {
            similarIds.put(productId, List.of(ids));
        }

        void failSimilarIds(String productId, RuntimeException failure) {
            similarIdsFailures.put(productId, failure);
        }

        void products(String... ids) {
            for (String id : ids) {
                products.put(id, new Product(id, "Product " + id, new BigDecimal("9.99"), true));
            }
        }

        void failDetail(String id, RuntimeException failure) {
            detailFailures.put(id, failure);
        }

        @Override
        public List<String> findSimilarIds(String productId) {
            if (similarIdsFailures.containsKey(productId)) {
                throw similarIdsFailures.get(productId);
            }
            List<String> ids = similarIds.get(productId);
            if (ids == null) {
                throw new ProductNotFoundException(productId);
            }
            return ids;
        }

        @Override
        public Product findById(String productId) {
            detailRequests.add(productId);
            if (detailFailures.containsKey(productId)) {
                throw detailFailures.get(productId);
            }
            return products.get(productId);
        }
    }
}
