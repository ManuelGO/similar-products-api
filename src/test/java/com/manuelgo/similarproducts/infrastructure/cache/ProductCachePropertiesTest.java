package com.manuelgo.similarproducts.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProductCachePropertiesTest {

    private static final Duration VALID = Duration.ofSeconds(5);

    static Stream<Arguments> invalidTtls() {
        return Stream.of("ttl", "not-found-ttl", "failure-ttl")
                .flatMap(name -> Stream.of(Arguments.of(name, null), Arguments.of(name, Duration.ofSeconds(-1))));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidTtls")
    void rejectsAMissingOrNegativeTtl(String name, Duration ttl) {
        assertThatThrownBy(() -> settingsWith(name, ttl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name);
    }

    @Test
    void acceptsAZeroTtl() {
        assertThatCode(() -> new ProductCacheProperties.Settings(Duration.ZERO, Duration.ZERO, Duration.ZERO, 10))
                .doesNotThrowAnyException();
    }

    private static ProductCacheProperties.Settings settingsWith(String name, Duration ttl) {
        return switch (name) {
            case "ttl" -> new ProductCacheProperties.Settings(ttl, VALID, VALID, 10);
            case "not-found-ttl" -> new ProductCacheProperties.Settings(VALID, ttl, VALID, 10);
            case "failure-ttl" -> new ProductCacheProperties.Settings(VALID, VALID, ttl, 10);
            default -> throw new IllegalArgumentException(name);
        };
    }
}
