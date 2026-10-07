package com.manuelgo.similarproducts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
class SimilarProductsApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsAndServerIsConfiguredOnPort5000() {
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(5000);
    }
}
