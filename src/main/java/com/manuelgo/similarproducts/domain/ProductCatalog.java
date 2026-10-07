package com.manuelgo.similarproducts.domain;

import java.util.List;

/**
 * Outbound port to the catalogue that knows products and their similarity.
 *
 * <p>Implementations report failures only through the domain exceptions declared below.
 */
public interface ProductCatalog {

    /**
     * Returns the ids of the products similar to the given one, ordered by similarity.
     *
     * @throws ProductNotFoundException       if the given product does not exist
     * @throws ProductCatalogTimeoutException if the catalogue did not respond in time
     * @throws ProductCatalogException        if the catalogue failed or returned a malformed response
     */
    List<String> findSimilarIds(String productId);

    /**
     * Returns the detail of the given product.
     *
     * @throws ProductNotFoundException       if the given product does not exist
     * @throws ProductCatalogTimeoutException if the catalogue did not respond in time
     * @throws ProductCatalogException        if the catalogue failed or returned a malformed response
     */
    Product findById(String productId);
}
