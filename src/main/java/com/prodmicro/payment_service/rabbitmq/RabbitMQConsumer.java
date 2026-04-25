package com.prodmicro.payment_service.rabbitmq;

import com.prodmicro.payment_service.payment.dto.OrderAwaitingPaymentEvent;
import com.prodmicro.payment_service.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RabbitMQConsumer {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConsumer.class);

    private final PaymentService paymentService;

    public RabbitMQConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.payment}")
    public void onOrderAwaitingPayment(OrderAwaitingPaymentEvent event) {
        log.info("[order.awaiting_payment] received orderId={} amount={}", event.orderId(), event.amount());
        try {
            paymentService.initializePayment(event);
        } catch (Exception e) {
            log.error("[order.awaiting_payment] failed for orderId={}: {}", event.orderId(), e.getMessage(), e);
        }
    }
}
