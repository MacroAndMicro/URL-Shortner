package com.Url_Shortener.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignaturesTest {

    // Expected values computed independently with:
    //   printf 'order_IluGWxBm9U8zJ8|pay_IH4NVgf4Dreq1l' | openssl dgst -sha256 -hmac 'test_key_secret'
    //   printf '{"event":"payment.captured"}' | openssl dgst -sha256 -hmac 'whsec'
    private static final String PAYMENT_SIGNATURE = "34e54fc403561b4f9bd4df51fe30b7c8f9d614d823e82a8e4389a8669f5af0b2";
    private static final String WEBHOOK_SIGNATURE = "4673dd707ef4c41b987cb7fefe1583142dc702388c93145b7814b9ad3d3c183e";

    @Test
    void acceptsRazorpaysCheckoutSignature() {
        assertThat(RazorpaySignatures.isValidPayment("order_IluGWxBm9U8zJ8", "pay_IH4NVgf4Dreq1l",
                PAYMENT_SIGNATURE, "test_key_secret")).isTrue();
    }

    @Test
    void rejectsATamperedOrForgedCheckoutSignature() {
        assertThat(RazorpaySignatures.isValidPayment("order_IluGWxBm9U8zJ8", "pay_SOMEONE_ELSE",
                PAYMENT_SIGNATURE, "test_key_secret")).isFalse();
        assertThat(RazorpaySignatures.isValidPayment("order_IluGWxBm9U8zJ8", "pay_IH4NVgf4Dreq1l",
                PAYMENT_SIGNATURE, "wrong_secret")).isFalse();
        assertThat(RazorpaySignatures.isValidPayment("order_IluGWxBm9U8zJ8", "pay_IH4NVgf4Dreq1l",
                null, "test_key_secret")).isFalse();
    }

    @Test
    void checksWebhookSignaturesOverTheExactBody() {
        assertThat(RazorpaySignatures.isValidWebhook("{\"event\":\"payment.captured\"}", WEBHOOK_SIGNATURE, "whsec"))
                .isTrue();
        assertThat(RazorpaySignatures.isValidWebhook("{\"event\": \"payment.captured\"}", WEBHOOK_SIGNATURE, "whsec"))
                .as("one extra space").isFalse();
    }
}
