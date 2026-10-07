package com.manuelgo.similarproducts;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the whole application over HTTP against an upstream that replays the challenge mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimilarProductsEndToEndTest {

    @RegisterExtension
    static final WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void pointProductApiAtUpstream(DynamicPropertyRegistry registry) {
        registry.add("product-api.base-url", upstream::baseUrl);
    }

    @Test
    void returnsSimilarProductDetailsInSimilarityOrder() throws Exception {
        // Same data as shared/simulado/mocks.json for product 1.
        upstream.stubFor(get("/product/1/similarids").willReturn(okJson("[2,3,4]")));
        upstream.stubFor(get("/product/2").willReturn(
                okJson("{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}")));
        upstream.stubFor(get("/product/3").willReturn(
                okJson("{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":false}")));
        upstream.stubFor(get("/product/4").willReturn(
                okJson("{\"id\":\"4\",\"name\":\"Boots\",\"price\":39.99,\"availability\":true}")));

        HttpResponse<String> response = getSimilar("1");

        assertThat(response.statusCode()).isEqualTo(200);
        JSONAssert.assertEquals("""
                [
                  {"id":"2","name":"Dress","price":19.99,"availability":true},
                  {"id":"3","name":"Blazer","price":29.99,"availability":false},
                  {"id":"4","name":"Boots","price":39.99,"availability":true}
                ]
                """, response.body(), JSONCompareMode.STRICT);
    }

    @Test
    void returnsNotFoundWithEmptyBodyWhenTheProductDoesNotExist() throws Exception {
        upstream.stubFor(get("/product/unknown/similarids").willReturn(notFound()));

        HttpResponse<String> response = getSimilar("unknown");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
    }

    private HttpResponse<String> getSimilar(String productId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/product/" + productId + "/similar")).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
