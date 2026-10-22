package com.manuelgo.similarproducts;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the whole application over HTTP with the configured product cache.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCacheEndToEndTest {

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
    void repeatedRequestsWithinTheTtlCallEachUpstreamUrlOnce() throws Exception {
        upstream.stubFor(get("/product/1/similarids").willReturn(okJson("[2,3]")));
        upstream.stubFor(get("/product/2").willReturn(
                okJson("{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}")));
        upstream.stubFor(get("/product/3").willReturn(
                okJson("{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":false}")));

        HttpResponse<String> first = getSimilar("1");
        HttpResponse<String> second = getSimilar("1");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        upstream.verify(1, getRequestedFor(urlEqualTo("/product/1/similarids")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/product/2")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/product/3")));
    }

    @Test
    void failedLookupsAreNotCached() throws Exception {
        upstream.stubFor(get("/product/7/similarids").willReturn(okJson("[8]")));
        upstream.stubFor(get("/product/8").willReturn(notFound()));

        HttpResponse<String> first = getSimilar("7");
        HttpResponse<String> second = getSimilar("7");

        assertThat(first.body()).isEqualTo("[]");
        assertThat(second.body()).isEqualTo("[]");
        upstream.verify(1, getRequestedFor(urlEqualTo("/product/7/similarids")));
        upstream.verify(2, getRequestedFor(urlEqualTo("/product/8")));
    }

    private HttpResponse<String> getSimilar(String productId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/product/" + productId + "/similar")).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
