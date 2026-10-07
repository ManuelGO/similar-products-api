package com.manuelgo.similarproducts.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manuelgo.similarproducts.application.SimilarProductsService;
import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SimilarProductsController.class)
class SimilarProductsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SimilarProductsService similarProductsService;

    @Test
    void returnsSimilarProductsWithExactlyTheContractFields() throws Exception {
        given(similarProductsService.findSimilarProducts("1")).willReturn(List.of(
                new Product("2", "Dress", new BigDecimal("19.99"), true),
                new Product("3", "Blazer", new BigDecimal("29.99"), false)));

        mockMvc.perform(get("/product/1/similar"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [
                          {"id":"2","name":"Dress","price":19.99,"availability":true},
                          {"id":"3","name":"Blazer","price":29.99,"availability":false}
                        ]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsEmptyArrayWhenThereAreNoSimilarProducts() throws Exception {
        given(similarProductsService.findSimilarProducts("1")).willReturn(List.of());

        mockMvc.perform(get("/product/1/similar"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", JsonCompareMode.STRICT));
    }

    @Test
    void returnsNotFoundWithEmptyBodyWhenTheProductDoesNotExist() throws Exception {
        given(similarProductsService.findSimilarProducts("unknown"))
                .willThrow(new ProductNotFoundException("unknown"));

        mockMvc.perform(get("/product/unknown/similar"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }
}
