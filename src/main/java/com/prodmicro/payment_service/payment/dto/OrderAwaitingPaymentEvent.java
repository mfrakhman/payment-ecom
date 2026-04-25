package com.prodmicro.payment_service.payment.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderAwaitingPaymentEvent(
        String orderId,
        String userId,
        BigDecimal amount,
        List<OrderItemDto> items
) {}
