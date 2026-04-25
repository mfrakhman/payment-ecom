package com.prodmicro.payment_service.payment.service.impl;

import com.prodmicro.payment_service.payment.dto.OrderAwaitingPaymentEvent;
import com.prodmicro.payment_service.payment.dto.PaymentResponse;
import com.prodmicro.payment_service.payment.entity.Payment;
import com.prodmicro.payment_service.payment.entity.PaymentStatus;
import com.prodmicro.payment_service.payment.repository.PaymentRepository;
import com.prodmicro.payment_service.payment.service.PaymentService;
import com.prodmicro.payment_service.rabbitmq.RabbitMQPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Service
@Transactional
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final PaymentRepository paymentRepository;
    private final RabbitMQPublisher publisher;

    public PaymentServiceImpl(PaymentRepository paymentRepository, RabbitMQPublisher publisher) {
        this.paymentRepository = paymentRepository;
        this.publisher = publisher;
    }

    @Override
    public void initializePayment(OrderAwaitingPaymentEvent event) {
        paymentRepository.findByOrderId(event.orderId()).ifPresent(existing -> {
            log.warn("[initializePayment] payment already exists for orderId={}", event.orderId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment already exists for order");
        });

        Payment payment = new Payment();
        payment.setOrderId(event.orderId());
        payment.setUserId(event.userId());
        payment.setAmount(event.amount());
        payment.setStatus(PaymentStatus.AWAITING);

        // TODO: call QRIS API here to generate QR
        // For now stub: set placeholder values so flow continues
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        payment.setExpiresAt(expiresAt);
        // payment.setTransactionId(qrisResponse.transactionId());
        // payment.setQrString(qrisResponse.qrString());

        paymentRepository.save(payment);
        log.info("[initializePayment] payment created for orderId={}", event.orderId());

        // Publish qr_ready so order-service stores the QR
        Map<String, Object> qrReadyPayload = new HashMap<>();
        qrReadyPayload.put("orderId", event.orderId());
        qrReadyPayload.put("transactionId", payment.getTransactionId());
        qrReadyPayload.put("qrString", payment.getQrString());
        qrReadyPayload.put("expiresAt", expiresAt.toString());
        publisher.publish("payment.qr_ready", qrReadyPayload);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        return toResponse(payment);
    }

    @Override
    public void handleWebhook(Map<String, Object> payload) {
        String transactionId = (String) payload.get("transaction_id");
        String status = (String) payload.get("status");

        if (transactionId == null || status == null) {
            log.warn("[webhook] missing transaction_id or status");
            return;
        }

        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElse(null);

        if (payment == null) {
            log.warn("[webhook] no payment found for transactionId={}", transactionId);
            return;
        }

        if (payment.getStatus() != PaymentStatus.AWAITING) {
            log.warn("[webhook] payment already processed orderId={} status={}", payment.getOrderId(), payment.getStatus());
            return;
        }

        if ("settlement".equals(status) || "capture".equals(status)) {
            payment.setStatus(PaymentStatus.PAID);
            payment.setPaidAt(Instant.now());
            paymentRepository.save(payment);

            Map<String, Object> confirmedPayload = buildPayload(payment, payload);
            publisher.publish("payment.confirmed", confirmedPayload);
            log.info("[webhook] payment confirmed orderId={}", payment.getOrderId());

        } else if ("expire".equals(status)) {
            payment.setStatus(PaymentStatus.EXPIRED);
            paymentRepository.save(payment);

            publisher.publish("payment.expired", buildPayload(payment, payload));
            log.info("[webhook] payment expired orderId={}", payment.getOrderId());

        } else if ("deny".equals(status) || "cancel".equals(status)) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);

            publisher.publish("payment.failed", buildPayload(payment, payload));
            log.info("[webhook] payment failed orderId={}", payment.getOrderId());
        }
    }

    private Map<String, Object> buildPayload(Payment payment, Map<String, Object> webhookData) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("transactionId", payment.getTransactionId());
        payload.put("amount", payment.getAmount());
        payload.put("paidAt", payment.getPaidAt() != null ? payment.getPaidAt().toString() : null);
        // items carried from webhook or left for order-service to resolve from its own DB
        return payload;
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(), p.getOrderId(), p.getUserId(), p.getAmount(),
                p.getStatus(), p.getQrString(), p.getExpiresAt(), p.getPaidAt(),
                p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
