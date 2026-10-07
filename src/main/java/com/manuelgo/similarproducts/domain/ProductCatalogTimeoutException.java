package com.manuelgo.similarproducts.domain;

/**
 * The product catalogue did not respond within the configured time budget.
 */
public class ProductCatalogTimeoutException extends ProductCatalogException {

    public ProductCatalogTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
