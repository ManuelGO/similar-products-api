package com.manuelgo.similarproducts.web;

import com.manuelgo.similarproducts.domain.ProductCatalogException;
import com.manuelgo.similarproducts.domain.ProductCatalogTimeoutException;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures to HTTP responses with empty bodies, the smallest representation compatible with the contract.
 * Spring picks the handler for the most specific matching exception type.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Void> handleProductNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ProductCatalogTimeoutException.class)
    public ResponseEntity<Void> handleCatalogTimeout(ProductCatalogTimeoutException e) {
        log.warn("Responding 504: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
    }

    @ExceptionHandler(ProductCatalogException.class)
    public ResponseEntity<Void> handleCatalogFailure(ProductCatalogException e) {
        log.warn("Responding 502: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleUnexpected(Exception e) {
        // Spring MVC's own request errors (unknown route, unsupported method, ...) carry the status
        // Spring intends to return; they are client errors, not unexpected application failures.
        if (e instanceof ErrorResponse errorResponse) {
            return ResponseEntity.status(errorResponse.getStatusCode()).build();
        }
        log.error("Responding 500: unexpected error", e);
        return ResponseEntity.internalServerError().build();
    }
}
