package com.manuelgo.similarproducts.infrastructure.productapi;

import com.manuelgo.similarproducts.domain.Product;
import java.math.BigDecimal;

/**
 * Product detail as returned by the upstream Product API.
 */
record ProductApiResponse(String id, String name, BigDecimal price, Boolean availability) {

    Product toProduct() {
        return new Product(id, name, price, availability);
    }
}
