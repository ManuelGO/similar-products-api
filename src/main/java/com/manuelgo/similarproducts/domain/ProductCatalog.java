package com.manuelgo.similarproducts.domain;

import java.util.List;

/**
 * Outbound port to the catalogue that knows products and their similarity.
 */
public interface ProductCatalog {

    /**
     * Returns the ids of the products similar to the given one, ordered by similarity.
     *
     * @throws ProductNotFoundException if the given product does not exist
     */
    List<String> findSimilarIds(String productId);

    /**
     * Returns the detail of the given product.
     *
     * @throws ProductNotFoundException if the given product does not exist
     */
    Product findById(String productId);
}
