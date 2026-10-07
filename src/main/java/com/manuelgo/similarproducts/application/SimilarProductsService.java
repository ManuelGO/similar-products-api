package com.manuelgo.similarproducts.application;

import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalog;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class SimilarProductsService {

    private final ProductCatalog catalog;
    private final Executor detailFetchExecutor;

    public SimilarProductsService(ProductCatalog catalog, Executor detailFetchExecutor) {
        this.catalog = catalog;
        this.detailFetchExecutor = detailFetchExecutor;
    }

    /**
     * Returns the details of the products similar to the given one, in similarity order.
     *
     * @throws com.manuelgo.similarproducts.domain.ProductNotFoundException if the given product does not exist
     */
    public List<Product> findSimilarProducts(String productId) {
        // distinct() on an ordered stream keeps the first occurrence of each id.
        List<String> similarIds = catalog.findSimilarIds(productId).stream()
                .distinct()
                .toList();

        // Start every detail request before waiting on any of them.
        List<CompletableFuture<Product>> pendingDetails = similarIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> catalog.findById(id), detailFetchExecutor))
                .toList();

        // Joining in id order preserves similarity order, whatever order the calls complete in.
        return pendingDetails.stream()
                .map(CompletableFuture::join)
                .toList();
    }
}
