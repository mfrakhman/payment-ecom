package com.prodmicro.payment_service.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Component
public class MidtransService {

    private static final Logger log = LoggerFactory.getLogger(MidtransService.class);

    private final String serverKey;
    private final String baseUrl;
    private final RestClient restClient;

    public MidtransService(
            @Value("${midtrans.server-key}") String serverKey,
            @Value("${midtrans.is-production:false}") boolean isProduction) {
        this.serverKey = serverKey;
        this.baseUrl = isProduction
                ? "https://api.midtrans.com"
                : "https://api.sandbox.midtrans.com";
        String credentials = Base64.getEncoder().encodeToString((serverKey + ":").getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Basic " + credentials)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chargeQris(String orderId, BigDecimal amount) {
        Map<String, Object> transactionDetails = new HashMap<>();
        transactionDetails.put("order_id", orderId);
        transactionDetails.put("gross_amount", amount.longValue());

        Map<String, Object> qris = new HashMap<>();
        qris.put("acquirer", "gopay");

        Map<String, Object> body = new HashMap<>();
        body.put("payment_type", "qris");
        body.put("transaction_details", transactionDetails);
        body.put("qris", qris);

        log.info("[midtrans] charging QRIS for orderId={} amount={}", orderId, amount);
        Map<String, Object> response = restClient.post()
                .uri("/v2/charge")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        log.info("[midtrans] charge response status_code={} status_message={} transaction_id={}",
                response != null ? response.get("status_code") : "null",
                response != null ? response.get("status_message") : "null",
                response != null ? response.get("transaction_id") : "null");
        return response;
    }

    public String getQrImageUrl(String transactionId) {
        return baseUrl + "/v2/qris/" + transactionId + "/qr-code";
    }

    public boolean verifySignature(String orderId, String statusCode, String grossAmount, String signatureKey) {
        try {
            String raw = orderId + statusCode + grossAmount + serverKey;
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hash);
            return expected.equals(signatureKey);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 not available", e);
        }
    }
}
