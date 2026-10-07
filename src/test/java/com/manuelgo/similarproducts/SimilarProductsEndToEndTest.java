package com.manuelgo.similarproducts;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the whole application over HTTP against an upstream that replays the challenge mocks
 * ({@code shared/simulado/mocks.json}), with delays scaled to a short read timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimilarProductsEndToEndTest {

    // Well beyond the read timeout configured below, so timing jitter cannot change the outcome.
    private static final int SLOW_MILLIS = 1500;

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
        registry.add("product-api.read-timeout", () -> "300ms");
    }

    @BeforeEach
    void stubChallengeProducts() {
        stubProduct("1", "{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}");
        stubProduct("2", "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}");
        stubProduct("3", "{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":false}");
        stubProduct("4", "{\"id\":\"4\",\"name\":\"Boots\",\"price\":39.99,\"availability\":true}");
        upstream.stubFor(get("/product/5").willReturn(notFound()));
        upstream.stubFor(get("/product/6").willReturn(serverError()));
        upstream.stubFor(get("/product/100").willReturn(
                okJson("{\"id\":\"100\",\"name\":\"Trousers\",\"price\":49.99,\"availability\":false}")
                        .withFixedDelay(SLOW_MILLIS)));
    }

    @Test
    void returnsSimilarProductDetailsInSimilarityOrder() throws Exception {
        stubSimilarIds("1", "[2,3,4]");

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
    void omitsSimilarProductThatIsNotFound() throws Exception {
        stubSimilarIds("4", "[1,2,5]");

        HttpResponse<String> response = getSimilar("4");

        assertThat(response.statusCode()).isEqualTo(200);
        assertIds(response, "1", "2");
    }

    @Test
    void omitsSimilarProductWhoseDetailFails() throws Exception {
        stubSimilarIds("5", "[1,2,6]");

        HttpResponse<String> response = getSimilar("5");

        assertThat(response.statusCode()).isEqualTo(200);
        assertIds(response, "1", "2");
    }

    @Test
    void omitsSimilarProductSlowerThanTheReadTimeout() throws Exception {
        stubSimilarIds("2", "[3,100]");

        long start = System.nanoTime();
        HttpResponse<String> response = getSimilar("2");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(200);
        assertIds(response, "3");
        assertThat(elapsedMillis).isLessThan(SLOW_MILLIS);
    }

    @Test
    void returnsNotFoundWithEmptyBodyWhenTheProductDoesNotExist() throws Exception {
        upstream.stubFor(get("/product/unknown/similarids").willReturn(notFound()));

        HttpResponse<String> response = getSimilar("unknown");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void returnsBadGatewayWhenSimilarIdsFail() throws Exception {
        upstream.stubFor(get("/product/1/similarids").willReturn(serverError()));

        HttpResponse<String> response = getSimilar("1");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void returnsGatewayTimeoutWhenSimilarIdsHeadersArriveAfterTheReadTimeout() throws Exception {
        upstream.stubFor(get("/product/1/similarids").willReturn(okJson("[2,3,4]").withFixedDelay(SLOW_MILLIS)));

        HttpResponse<String> response = getSimilar("1");

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void returnsBadGatewayWhenSimilarIdsBodyIsCutOffByTheReadTimeout() throws Exception {
        upstream.stubFor(get("/product/1/similarids").willReturn(
                okJson("[\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]").withChunkedDribbleDelay(10, SLOW_MILLIS)));

        HttpResponse<String> response = getSimilar("1");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void returnsNotFoundWithEmptyBodyForUnknownRoutes() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/unknown/path")).build());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
    }

    private void stubSimilarIds(String productId, String ids) {
        upstream.stubFor(get("/product/" + productId + "/similarids").willReturn(okJson(ids)));
    }

    private void stubProduct(String productId, String body) {
        upstream.stubFor(get("/product/" + productId).willReturn(okJson(body)));
    }

    private static void assertIds(HttpResponse<String> response, String... expectedIds) {
        List<String> ids = JsonPath.read(response.body(), "$[*].id");
        assertThat(ids).containsExactly(expectedIds);
    }

    private HttpResponse<String> getSimilar(String productId) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri("/product/" + productId + "/similar")).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
