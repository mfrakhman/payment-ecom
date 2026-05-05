package com.prodmicro.payment_service.payment.service.impl;

import com.prodmicro.payment_service.payment.dto.OrderAwaitingPaymentEvent;
import com.prodmicro.payment_service.payment.dto.PaymentResponse;
import com.prodmicro.payment_service.payment.entity.Payment;
import com.prodmicro.payment_service.payment.entity.PaymentStatus;
import com.prodmicro.payment_service.payment.repository.PaymentRepository;
import com.prodmicro.payment_service.payment.service.MidtransService;
import com.prodmicro.payment_service.payment.service.PaymentService;
import com.prodmicro.payment_service.rabbitmq.RabbitMQPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
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
    private final MidtransService midtransService;

    public PaymentServiceImpl(PaymentRepository paymentRepository,
                              RabbitMQPublisher publisher,
                              MidtransService midtransService) {
        this.paymentRepository = paymentRepository;
        this.publisher = publisher;
        this.midtransService = midtransService;
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

        boolean chargeSuccess = false;
        String qrCodeUrl = null;
        try {
            Map<String, Object> result = midtransService.chargeGopay(event.orderId(), event.amount());
            String statusCode = (String) result.get("status_code");
            if (!"201".equals(statusCode)) {
                String statusMessage = (String) result.get("status_message");
                log.error("[initializePayment] Midtrans charge failed orderId={} status_code={} message={}",
                        event.orderId(), statusCode, statusMessage);
            } else {
                payment.setTransactionId((String) result.get("transaction_id"));
                qrCodeUrl = midtransService.extractQrCodeUrl(result);
                payment.setQrString(qrCodeUrl);
                chargeSuccess = true;
                log.info("[initializePayment] Midtrans charge success transactionId={}", payment.getTransactionId());
            }
        } catch (RestClientException e) {
            log.error("[initializePayment] Midtrans charge failed orderId={}: {}", event.orderId(), e.getMessage());
        }

        if (!chargeSuccess) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            publisher.publish("payment.failed", buildPayload(payment));
            log.info("[initializePayment] payment failed orderId={}", event.orderId());
            return;
        }

        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        payment.setExpiresAt(expiresAt);
        paymentRepository.save(payment);
        log.info("[initializePayment] payment created orderId={}", event.orderId());

        Map<String, Object> qrReadyPayload = new HashMap<>();
        qrReadyPayload.put("orderId", payment.getOrderId());
        qrReadyPayload.put("transactionId", payment.getTransactionId());
        qrReadyPayload.put("qrString", payment.getQrString());
        qrReadyPayload.put("qrImageUrl", qrCodeUrl);
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
        String orderId = (String) payload.get("order_id");
        String statusCode = (String) payload.get("status_code");
        String grossAmount = (String) payload.get("gross_amount");
        String signatureKey = (String) payload.get("signature_key");
        String transactionStatus = (String) payload.get("transaction_status");

        if (orderId == null || statusCode == null || grossAmount == null || signatureKey == null) {
            log.warn("[webhook] missing required fields, keys={}", payload.keySet());
            return;
        }

        if (!midtransService.verifySignature(orderId, statusCode, grossAmount, signatureKey)) {
            log.warn("[webhook] invalid signature for orderId={}", orderId);
            return;
        }

        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        if (payment == null) {
            log.warn("[webhook] no payment found for orderId={}", orderId);
            return;
        }

        if (payment.getStatus() != PaymentStatus.AWAITING) {
            log.warn("[webhook] already processed orderId={} status={}", orderId, payment.getStatus());
            return;
        }

        if ("settlement".equals(transactionStatus) || "capture".equals(transactionStatus)) {
            payment.setStatus(PaymentStatus.PAID);
            payment.setPaidAt(Instant.now());
            paymentRepository.save(payment);
            publisher.publish("payment.confirmed", buildPayload(payment));
            log.info("[webhook] payment confirmed orderId={}", orderId);

        } else if ("expire".equals(transactionStatus)) {
            payment.setStatus(PaymentStatus.EXPIRED);
            paymentRepository.save(payment);
            publisher.publish("payment.expired", buildPayload(payment));
            log.info("[webhook] payment expired orderId={}", orderId);

        } else if ("deny".equals(transactionStatus) || "cancel".equals(transactionStatus)) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            publisher.publish("payment.failed", buildPayload(payment));
            log.info("[webhook] payment failed orderId={}", orderId);

        } else {
            log.info("[webhook] unhandled status={} orderId={}", transactionStatus, orderId);
        }
    }

    private Map<String, Object> buildPayload(Payment payment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", payment.getOrderId());
        payload.put("transactionId", payment.getTransactionId());
        payload.put("amount", payment.getAmount());
        payload.put("paidAt", payment.getPaidAt() != null ? payment.getPaidAt().toString() : null);
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
