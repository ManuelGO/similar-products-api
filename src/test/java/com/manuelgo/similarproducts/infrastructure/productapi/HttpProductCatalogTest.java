package com.manuelgo.similarproducts.infrastructure.productapi;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Exercises the adapter with the production RestClient configuration against a real HTTP server.
 */
class HttpProductCatalogTest {

    @RegisterExtension
    static final WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private HttpProductCatalog catalog;

    @BeforeEach
    void createCatalog() {
        ProductApiProperties properties = new ProductApiProperties(upstream.baseUrl());
        catalog = new HttpProductCatalog(new ProductApiConfiguration().productApiRestClient(properties));
    }

    @Test
    void readsSimilarIdsServedAsStrings() {
        upstream.stubFor(get("/product/1/similarids").willReturn(okJson("[\"2\",\"3\",\"4\"]")));

        assertThat(catalog.findSimilarIds("1")).containsExactly("2", "3", "4");
    }

    @Test
    void readsSimilarIdsServedAsNumbersLikeTheChallengeMocks() {
        upstream.stubFor(get("/product/1/similarids").willReturn(okJson("[2,3,4]")));

        assertThat(catalog.findSimilarIds("1")).containsExactly("2", "3", "4");
    }

    @Test
    void reportsNotFoundWhenTheRequestedProductHasNoSimilarIds() {
        upstream.stubFor(get("/product/unknown/similarids").willReturn(notFound()));

        assertThatThrownBy(() -> catalog.findSimilarIds("unknown"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void mapsProductDetailKeepingTheExactPrice() {
        upstream.stubFor(get("/product/1").willReturn(
                okJson("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}")));

        Product product = catalog.findById("1");

        assertThat(product).isEqualTo(new Product("1", "Shirt", new BigDecimal("9.99"), true));
    }

    @Test
    void reportsNotFoundWhenTheProductDetailDoesNotExist() {
        upstream.stubFor(get("/product/5").willReturn(notFound()));

        assertThatThrownBy(() -> catalog.findById("5"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void urlEncodesTheProductIdInsteadOfConcatenatingIt() {
        upstream.stubFor(get(urlEqualTo("/product/a%20b%3Fc/similarids")).willReturn(okJson("[]")));

        assertThat(catalog.findSimilarIds("a b?c")).isEmpty();
        upstream.verify(getRequestedFor(urlEqualTo("/product/a%20b%3Fc/similarids")));
    }
}
