package com.Url_Shortener.billing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Checks that payment confirmations really come from Razorpay. Both are
 * HMAC-SHA256 in hex:
 *
 * <ul>
 *   <li>Checkout result: over "order_id|payment_id" with the key secret.</li>
 *   <li>Webhook: over the raw request body with the webhook secret.</li>
 * </ul>
 *
 * Compared in constant time, so the check does not leak how much matched.
 */
public final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    public static boolean isValidPayment(String orderId, String paymentId, String signature, String keySecret) {
        return matches(hmacSha256Hex(orderId + "|" + paymentId, keySecret), signature);
    }

    public static boolean isValidWebhook(String body, String signature, String webhookSecret) {
        return matches(hmacSha256Hex(body, webhookSecret), signature);
    }

    static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean matches(String expected, String given) {
        return given != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
