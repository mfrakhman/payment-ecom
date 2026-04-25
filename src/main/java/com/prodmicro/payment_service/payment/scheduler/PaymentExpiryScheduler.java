package com.prodmicro.payment_service.payment.scheduler;

import com.prodmicro.payment_service.payment.entity.Payment;
import com.prodmicro.payment_service.payment.entity.PaymentStatus;
import com.prodmicro.payment_service.payment.repository.PaymentRepository;
import com.prodmicro.payment_service.rabbitmq.RabbitMQPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryScheduler.class);

    private final PaymentRepository paymentRepository;
    private final RabbitMQPublisher publisher;

    public PaymentExpiryScheduler(PaymentRepository paymentRepository, RabbitMQPublisher publisher) {
        this.paymentRepository = paymentRepository;
        this.publisher = publisher;
    }

    @Scheduled(fixedRate = 60_000)
    public void expireStalePayments() {
        List<Payment> stale = paymentRepository.findByStatusAndExpiresAtBefore(
                PaymentStatus.AWAITING, Instant.now());
        if (stale.isEmpty()) return;

        log.info("[scheduler] expiring {} stale payment(s)", stale.size());
        for (Payment payment : stale) {
            payment.setStatus(PaymentStatus.EXPIRED);
            paymentRepository.save(payment);

            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", payment.getOrderId());
            payload.put("transactionId", payment.getTransactionId());
            payload.put("amount", payment.getAmount());
            publisher.publish("payment.expired", payload);

            log.info("[scheduler] expired payment orderId={}", payment.getOrderId());
        }
    }
}
