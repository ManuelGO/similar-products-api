package com.manuelgo.similarproducts.infrastructure.productapi;

import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalogException;
import java.math.BigDecimal;

/**
 * Product detail as returned by the upstream Product API.
 */
record ProductApiResponse(String id, String name, BigDecimal price, Boolean availability) {

    /**
     * @throws ProductCatalogException if a field the contract marks as required is missing or blank
     */
    Product toProduct() {
        if (isBlank(id) || isBlank(name) || price == null || availability == null) {
            throw new ProductCatalogException("Malformed product detail: " + this);
        }
        return new Product(id, name, price, availability);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
