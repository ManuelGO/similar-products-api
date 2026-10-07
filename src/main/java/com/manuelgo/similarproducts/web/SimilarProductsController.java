package com.manuelgo.similarproducts.web;

import com.manuelgo.similarproducts.application.SimilarProductsService;
import com.manuelgo.similarproducts.domain.Product;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SimilarProductsController {

    private final SimilarProductsService similarProductsService;

    public SimilarProductsController(SimilarProductsService similarProductsService) {
        this.similarProductsService = similarProductsService;
    }

    @GetMapping("/product/{productId}/similar")
    public List<Product> getSimilarProducts(@PathVariable String productId) {
        return similarProductsService.findSimilarProducts(productId);
    }
}
