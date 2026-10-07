package com.manuelgo.similarproducts.domain;

import java.math.BigDecimal;

public record Product(String id, String name, BigDecimal price, boolean availability) {
}
