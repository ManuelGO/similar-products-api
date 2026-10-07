package com.manuelgo.similarproducts.application;

import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import com.manuelgo.similarproducts.domain.ProductCatalogException;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimilarProductsService {

    private static final Logger log = LoggerFactory.getLogger(SimilarProductsService.class);

    private final ProductCatalog catalog;
    private final Executor detailFetchExecutor;

    public SimilarProductsService(ProductCatalog catalog, Executor detailFetchExecutor) {
        this.catalog = catalog;
        this.detailFetchExecutor = detailFetchExecutor;
    }

    /**
     * Returns the details of the products similar to the given one, in similarity order.
     *
     * <p>A similar product whose detail cannot be obtained because of a known upstream failure, or because
     * the executor is saturated, is omitted from the result. Any other failure propagates.
     *
     * @throws ProductNotFoundException if the given product does not exist
     * @throws ProductCatalogException  if the similar ids cannot be obtained
     */
    public List<Product> findSimilarProducts(String productId) {
        // distinct() on an ordered stream keeps the first occurrence of each id.
        List<String> similarIds = catalog.findSimilarIds(productId).stream()
                .distinct()
                .toList();

        // Start every detail request before waiting on any of them.
        List<CompletableFuture<Product>> pendingDetails = similarIds.stream()
                .map(this::fetchDetail)
                .toList();

        // Waiting in id order preserves similarity order, whatever order the calls complete in.
        List<Product> products = new ArrayList<>();
        for (int i = 0; i < similarIds.size(); i++) {
            awaitDetail(productId, similarIds.get(i), pendingDetails.get(i)).ifPresent(products::add);
        }
        return products;
    }

    private CompletableFuture<Product> fetchDetail(String id) {
        try {
            return CompletableFuture.supplyAsync(() -> catalog.findById(id), detailFetchExecutor);
        } catch (RejectedExecutionException e) {
            // Thrown synchronously when the executor is saturated; the work is never run on this thread.
            return CompletableFuture.failedFuture(e);
        }
    }

    private Optional<Product> awaitDetail(String productId, String similarId, CompletableFuture<Product> detail) {
        try {
            return Optional.of(detail.join());
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (!isKnownDetailFailure(cause)) {
                throw e;
            }
            log.warn("Omitting similar product {} of product {}: {}", similarId, productId, cause.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isKnownDetailFailure(Throwable failure) {
        return failure instanceof ProductNotFoundException
                || failure instanceof ProductCatalogException
                || failure instanceof RejectedExecutionException;
    }
}
