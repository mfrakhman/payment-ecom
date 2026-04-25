package com.prodmicro.payment_service.payment.controller;

import com.prodmicro.payment_service.payment.dto.PaymentResponse;
import com.prodmicro.payment_service.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/order/{orderId}")
    public PaymentResponse getByOrderId(@PathVariable String orderId) {
        return paymentService.getByOrderId(orderId);
    }

    @PostMapping("/webhook/qris")
    @ResponseStatus(HttpStatus.OK)
    public void handleWebhook(@RequestBody Map<String, Object> payload) {
        log.info("[webhook] received payload keys={}", payload.keySet());
        paymentService.handleWebhook(payload);
    }
}
