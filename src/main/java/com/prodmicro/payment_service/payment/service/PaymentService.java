package com.prodmicro.payment_service.payment.service;

import com.prodmicro.payment_service.payment.dto.OrderAwaitingPaymentEvent;
import com.prodmicro.payment_service.payment.dto.PaymentResponse;

import java.util.Map;

public interface PaymentService {
    void initializePayment(OrderAwaitingPaymentEvent event);
    PaymentResponse getByOrderId(String orderId);
    void handleWebhook(Map<String, Object> payload);
}
