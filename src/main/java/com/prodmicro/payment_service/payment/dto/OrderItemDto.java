package com.prodmicro.payment_service.payment.dto;

import java.math.BigDecimal;

public record OrderItemDto(String skuId, int quantity, BigDecimal price) {}
