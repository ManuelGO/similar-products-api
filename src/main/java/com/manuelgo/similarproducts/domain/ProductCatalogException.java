package com.manuelgo.similarproducts.domain;

/**
 * The product catalogue could not provide a valid answer: it failed, was unreachable,
 * or returned a malformed response.
 */
public class ProductCatalogException extends RuntimeException {

    public ProductCatalogException(String message) {
        super(message);
    }

    public ProductCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
