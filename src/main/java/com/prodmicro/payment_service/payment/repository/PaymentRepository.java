package com.prodmicro.payment_service.payment.repository;

import com.prodmicro.payment_service.payment.entity.Payment;
import com.prodmicro.payment_service.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {
    Optional<Payment> findByOrderId(String orderId);
    Optional<Payment> findByTransactionId(String transactionId);
    List<Payment> findByStatus(PaymentStatus status);
    List<Payment> findByStatusAndExpiresAtBefore(PaymentStatus status, Instant threshold);
}
