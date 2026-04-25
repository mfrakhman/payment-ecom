package com.prodmicro.payment_service.payment.dto;

import com.prodmicro.payment_service.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String id,
        String orderId,
        String userId,
        BigDecimal amount,
        PaymentStatus status,
        String qrString,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {}
