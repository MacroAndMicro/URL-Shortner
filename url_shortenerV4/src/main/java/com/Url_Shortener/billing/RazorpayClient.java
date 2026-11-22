package com.Url_Shortener.billing;

import com.Url_Shortener.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * The one Razorpay API call the app makes: creating an order. Plain HTTP
 * (Razorpay's Orders API with basic auth) rather than Razorpay's SDK, so the
 * base URL can point at a fake in tests.
 *
 * Payments are off unless the key id and secret are set (RAZORPAY_KEY_ID,
 * RAZORPAY_KEY_SECRET); see docs/razorpay-setup.md.
 */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final RestClient http;

    public RazorpayClient(@Value("${app.billing.razorpay.key-id:}") String keyId,
                          @Value("${app.billing.razorpay.key-secret:}") String keySecret,
                          @Value("${app.billing.razorpay.webhook-secret:}") String webhookSecret,
                          @Value("${app.billing.razorpay.base-url:https://api.razorpay.com}") String baseUrl) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeouts)
                .defaultHeaders(h -> h.setBasicAuth(keyId, keySecret))
                .build();
    }

    public boolean enabled() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    public String keyId() {
        return keyId;
    }

    String keySecret() {
        return keySecret;
    }

    String webhookSecret() {
        return webhookSecret;
    }

    /** What Razorpay returns for a new order (fields we use). */
    public record Order(String id, int amount, String currency, String status) {
    }

    /**
     * Creates an order for the amount; Checkout then collects payment for it.
     *
     * @param receipt our reference, shown in Razorpay's dashboard
     */
    public Order createOrder(int amountPaise, String currency, String receipt, Map<String, String> notes) {
        try {
            Order order = http.post().uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amountPaise, "currency", currency, "receipt", receipt, "notes", notes))
                    .retrieve()
                    .body(Order.class);
            if (order == null || order.id() == null) {
                throw new IllegalStateException("Razorpay returned no order");
            }
            return order;
        } catch (RuntimeException e) {
            log.warn("Could not create Razorpay order: {}", e.getMessage());
            throw new ServiceUnavailableException("Payments are temporarily unavailable. Please try again shortly.");
        }
    }
}
