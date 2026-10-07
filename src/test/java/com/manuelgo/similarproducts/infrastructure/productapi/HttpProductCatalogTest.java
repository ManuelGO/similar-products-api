package com.manuelgo.similarproducts.infrastructure.productapi;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.manuelgo.similarproducts.domain.Product;
import com.manuelgo.similarproducts.domain.ProductCatalogException;
import com.manuelgo.similarproducts.domain.ProductCatalogTimeoutException;
import com.manuelgo.similarproducts.domain.ProductNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the adapter with the production RestClient configuration against a real HTTP server.
 */
class HttpProductCatalogTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    // Well beyond the read timeout, so timing jitter cannot change the outcome.
    private static final int SLOW_MILLIS = 1500;

    @RegisterExtension
    static final WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private HttpProductCatalog catalog;

    @BeforeEach
    void createCatalog() {
        catalog = catalogFor(upstream.baseUrl());
    }

    private static HttpProductCatalog catalogFor(String baseUrl) {
        ProductApiProperties properties = new ProductApiProperties(baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT);
        return new HttpProductCatalog(new ProductApiConfiguration().productApiRestClient(properties));
    }

    @Nested
    class SimilarIds {

        @Test
        void readsIdsServedAsStrings() {
            stubSimilarIds(okJson("[\"2\",\"3\",\"4\"]"));

            assertThat(catalog.findSimilarIds("1")).containsExactly("2", "3", "4");
        }

        @Test
        void readsIdsServedAsNumbersLikeTheChallengeMocks() {
            stubSimilarIds(okJson("[2,3,4]"));

            assertThat(catalog.findSimilarIds("1")).containsExactly("2", "3", "4");
        }

        @Test
        void reportsNotFoundOn404() {
            stubSimilarIds(notFound());

            assertThatThrownBy(() -> catalog.findSimilarIds("1")).isInstanceOf(ProductNotFoundException.class);
        }

        @Test
        void reportsFailureOnServerError() {
            stubSimilarIds(serverError());

            assertThatThrownBy(() -> catalog.findSimilarIds("1")).isExactlyInstanceOf(ProductCatalogException.class);
            upstream.verify(1, getRequestedFor(urlEqualTo("/product/1/similarids")));
        }

        @Test
        void reportsTimeoutWhenHeadersArriveAfterTheReadTimeout() {
            stubSimilarIds(okJson("[\"2\"]").withFixedDelay(SLOW_MILLIS));

            assertThatThrownBy(() -> catalog.findSimilarIds("1"))
                    .isInstanceOf(ProductCatalogTimeoutException.class);
            upstream.verify(1, getRequestedFor(urlEqualTo("/product/1/similarids")));
        }

        @Test
        void reportsFailureNotTimeoutWhenTheBodyIsCutOffByTheReadTimeout() {
            // Headers arrive at once; the body is streamed over longer than the read timeout.
            stubSimilarIds(okJson("[\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]").withChunkedDribbleDelay(10, SLOW_MILLIS));

            assertThatThrownBy(() -> catalog.findSimilarIds("1")).isExactlyInstanceOf(ProductCatalogException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{not json", "{\"ids\":[\"2\"]}", "[null,\"2\"]", "[\"\",\"2\"]"})
        void reportsFailureOnMalformedBody(String body) {
            stubSimilarIds(okJson(body));

            assertThatThrownBy(() -> catalog.findSimilarIds("1")).isExactlyInstanceOf(ProductCatalogException.class);
        }

        @Test
        void reportsFailureOnEmptyBody() {
            stubSimilarIds(ok().withHeader("Content-Type", "application/json"));

            assertThatThrownBy(() -> catalog.findSimilarIds("1")).isExactlyInstanceOf(ProductCatalogException.class);
        }

        @Test
        void urlEncodesTheProductIdInsteadOfConcatenatingIt() {
            upstream.stubFor(get(urlEqualTo("/product/a%20b%3Fc/similarids")).willReturn(okJson("[]")));

            assertThat(catalog.findSimilarIds("a b?c")).isEmpty();
            upstream.verify(getRequestedFor(urlEqualTo("/product/a%20b%3Fc/similarids")));
        }

        private void stubSimilarIds(ResponseDefinitionBuilder response) {
            upstream.stubFor(get("/product/1/similarids").willReturn(response));
        }
    }

    @Nested
    class Detail {

        @Test
        void mapsProductDetailKeepingTheExactPrice() {
            stubDetail(okJson("{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}"));

            assertThat(catalog.findById("2")).isEqualTo(new Product("2", "Dress", new BigDecimal("19.99"), true));
        }

        @Test
        void reportsNotFoundOn404() {
            stubDetail(notFound());

            assertThatThrownBy(() -> catalog.findById("2")).isInstanceOf(ProductNotFoundException.class);
        }

        @Test
        void reportsFailureOnServerError() {
            stubDetail(serverError());

            assertThatThrownBy(() -> catalog.findById("2")).isExactlyInstanceOf(ProductCatalogException.class);
            upstream.verify(1, getRequestedFor(urlEqualTo("/product/2")));
        }

        @Test
        void reportsFailureOnClientErrorOtherThanNotFound() {
            stubDetail(badRequest());

            assertThatThrownBy(() -> catalog.findById("2")).isExactlyInstanceOf(ProductCatalogException.class);
        }

        @Test
        void reportsTimeoutWhenHeadersArriveAfterTheReadTimeout() {
            stubDetail(okJson("{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}")
                    .withFixedDelay(SLOW_MILLIS));

            assertThatThrownBy(() -> catalog.findById("2")).isInstanceOf(ProductCatalogTimeoutException.class);
            upstream.verify(1, getRequestedFor(urlEqualTo("/product/2")));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{not json",
                "{\"name\":\"Dress\",\"price\":19.99,\"availability\":true}",
                "{\"id\":\"2\",\"name\":\" \",\"price\":19.99,\"availability\":true}",
                "{\"id\":\"2\",\"name\":\"Dress\",\"availability\":true}",
                "{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99}"})
        void reportsFailureOnMalformedOrIncompleteBody(String body) {
            stubDetail(okJson(body));

            assertThatThrownBy(() -> catalog.findById("2")).isExactlyInstanceOf(ProductCatalogException.class);
        }

        @Test
        void reportsFailureOnEmptyBody() {
            stubDetail(ok().withHeader("Content-Type", "application/json"));

            assertThatThrownBy(() -> catalog.findById("2")).isExactlyInstanceOf(ProductCatalogException.class);
        }

        private void stubDetail(ResponseDefinitionBuilder response) {
            upstream.stubFor(get("/product/2").willReturn(response));
        }
    }

    @Nested
    class ConnectionFailures {

        @Test
        void connectionResetIsAFailureNotATimeout() {
            upstream.stubFor(get("/product/2").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThatThrownBy(() -> catalog.findById("2")).isExactlyInstanceOf(ProductCatalogException.class);
        }

        @Test
        void connectionRefusedIsAFailureNotATimeout() throws IOException {
            HttpProductCatalog unreachable = catalogFor("http://localhost:" + unusedPort());

            assertThatThrownBy(() -> unreachable.findById("2")).isExactlyInstanceOf(ProductCatalogException.class);
        }

        private static int unusedPort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        }
    }
}
