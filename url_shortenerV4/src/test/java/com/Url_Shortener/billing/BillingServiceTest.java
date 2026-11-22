package com.Url_Shortener.billing;

import com.Url_Shortener.entity.Payment;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.exception.LimitExceededException;
import com.Url_Shortener.exception.ServiceUnavailableException;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.plan.SubscriptionService;
import com.Url_Shortener.repository.PaymentRepository;
import com.Url_Shortener.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 12, 0);
    private static final String KEY_SECRET = "key_secret";
    private static final String WEBHOOK_SECRET = "webhook_secret";

    private RazorpayClient razorpay;
    private PaymentRepository payments;
    private UserRepository users;
    private SubscriptionService subscriptions;
    private BillingService billing;
    private User user;
    private Payment payment;

    @BeforeEach
    void setUp() {
        razorpay = mock(RazorpayClient.class);
        when(razorpay.enabled()).thenReturn(true);
        when(razorpay.keyId()).thenReturn("rzp_test_key");
        when(razorpay.keySecret()).thenReturn(KEY_SECRET);
        when(razorpay.webhookSecret()).thenReturn(WEBHOOK_SECRET);
        payments = mock(PaymentRepository.class);
        users = mock(UserRepository.class);
        subscriptions = mock(SubscriptionService.class);
        PlanService planService = new PlanService(users, mock(StringRedisTemplate.class),
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        billing = new BillingService(razorpay, payments, users, planService, subscriptions, 9900, 99900);

        user = new User();
        user.setId(7L);
        user.setUsername("payer");
        user.setEmail("payer@example.com");
        when(users.findById(7L)).thenReturn(Optional.of(user));

        payment = spy(new Payment(user, Plan.MONTHLY, 9900, "INR", "order_1", NOW));
        doReturn(41L).when(payment).getId();
        when(payments.findByRazorpayOrderId("order_1")).thenReturn(Optional.of(payment));
        when(payments.findById(41L)).thenReturn(Optional.of(payment));
        when(payments.markPaid(eq(41L), any(), any())).thenReturn(1, 0);
    }

    private void subscribe(Plan plan, LocalDateTime periodEnd) {
        user.setPlan(plan);
        user.setPlanPeriodEnd(periodEnd);
    }

    private static String sign(String orderId, String paymentId) {
        return RazorpaySignatures.hmacSha256Hex(orderId + "|" + paymentId, KEY_SECRET);
    }

    // ============================================================
    // WHAT A PAYMENT BUYS
    // ============================================================

    @Test
    void firstPaymentStartsTheMonthNow() {
        assertThat(billing.periodEndAfterPayment(user, Plan.MONTHLY)).isEqualTo(NOW.plusMonths(1));
        assertThat(billing.periodEndAfterPayment(user, Plan.YEARLY)).isEqualTo(NOW.plusYears(1));
    }

    @Test
    void renewingEarlyAddsToTheCurrentPeriod() {
        subscribe(Plan.MONTHLY, NOW.plusDays(2));

        assertThat(billing.periodEndAfterPayment(user, Plan.MONTHLY)).isEqualTo(NOW.plusDays(2).plusMonths(1));
    }

    @Test
    void payingDuringGraceCountsFromTheDueDate() {
        subscribe(Plan.MONTHLY, NOW.minusDays(5));

        assertThat(billing.periodEndAfterPayment(user, Plan.MONTHLY)).isEqualTo(NOW.minusDays(5).plusMonths(1));
    }

    @Test
    void payingAfterALapseStartsFromNow() {
        subscribe(Plan.MONTHLY, NOW.minusDays(10));

        assertThat(billing.periodEndAfterPayment(user, Plan.MONTHLY)).isEqualTo(NOW.plusMonths(1));
    }

    @Test
    void switchingToYearlyKeepsTheRemainingMonth() {
        subscribe(Plan.MONTHLY, NOW.plusDays(20));

        assertThat(billing.periodEndAfterPayment(user, Plan.YEARLY)).isEqualTo(NOW.plusDays(20).plusYears(1));
    }

    @Test
    void monthlyCannotBeBoughtWhileYearlyIsPaid() {
        subscribe(Plan.YEARLY, NOW.plusDays(100));

        assertThatThrownBy(() -> billing.createOrder(user, Plan.MONTHLY))
                .isInstanceOf(LimitExceededException.class)
                .hasMessageContaining("switch to Monthly when it ends");
        verifyNoInteractions(payments);
    }

    // ============================================================
    // ORDERS
    // ============================================================

    @Test
    void orderUsesTheServerPriceNotTheBrowsers() {
        when(razorpay.createOrder(anyInt(), any(), any(), any()))
                .thenReturn(new RazorpayClient.Order("order_9", 99900, "INR", "created"));

        BillingService.CheckoutOrder order = billing.createOrder(user, Plan.YEARLY);

        verify(razorpay).createOrder(eq(99900), eq("INR"), startsWith("u7-"), eq(Map.of("userId", "7", "plan", "YEARLY")));
        verify(payments).save(argThat(p -> p.getAmountPaise() == 99900 && p.getStatus() == Payment.Status.CREATED));
        assertThat(order.orderId()).isEqualTo("order_9");
        assertThat(order.keyId()).isEqualTo("rzp_test_key");
        assertThat(order.email()).isEqualTo("payer@example.com");
    }

    @Test
    void ordersNeedRazorpayToBeSetUp() {
        when(razorpay.enabled()).thenReturn(false);

        assertThatThrownBy(() -> billing.createOrder(user, Plan.MONTHLY))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void freeAndGuestCannotBeBought() {
        assertThatThrownBy(() -> billing.createOrder(user, Plan.FREE)).isInstanceOf(IllegalArgumentException.class);
    }

    // ============================================================
    // CONFIRMATION
    // ============================================================

    @Test
    void aVerifiedPaymentExtendsThePlan() {
        billing.verify(user, "order_1", "pay_1", sign("order_1", "pay_1"));

        verify(payments).markPaid(eq(41L), eq("pay_1"), eq(NOW));
        verify(subscriptions).changeSubscription(user, Plan.MONTHLY, NOW.plusMonths(1));
        assertThat(payment.getPeriodEndAfter()).isEqualTo(NOW.plusMonths(1));
    }

    @Test
    void aForgedSignatureChangesNothing() {
        assertThatThrownBy(() -> billing.verify(user, "order_1", "pay_1", sign("order_1", "pay_OTHER")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(payments, never()).markPaid(any(), any(), any());
        verifyNoInteractions(subscriptions);
    }

    @Test
    void someoneElsesOrderIsNotFound() {
        User other = new User();
        other.setId(8L);

        assertThatThrownBy(() -> billing.verify(other, "order_1", "pay_1", sign("order_1", "pay_1")))
                .hasMessageContaining("not found");
    }

    @Test
    void browserAndWebhookConfirmingTheSamePaymentExtendTheirPlanOnce() {
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"amount\":9900,\"status\":\"captured\"}}}}";

        billing.verify(user, "order_1", "pay_1", sign("order_1", "pay_1"));
        assertThat(billing.webhook(body, RazorpaySignatures.hmacSha256Hex(body, WEBHOOK_SECRET))).isTrue();

        verify(payments, times(2)).markPaid(eq(41L), eq("pay_1"), any());
        verify(subscriptions, times(1)).changeSubscription(any(), any(), any());
    }

    @Test
    void aWebhookAloneConfirmsThePayment() {
        String body = "{\"event\":\"order.paid\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"amount\":9900}}}}";

        assertThat(billing.webhook(body, RazorpaySignatures.hmacSha256Hex(body, WEBHOOK_SECRET))).isTrue();

        verify(subscriptions).changeSubscription(user, Plan.MONTHLY, NOW.plusMonths(1));
    }

    @Test
    void webhooksWithABadSignatureOrWrongAmountAreIgnored() {
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"amount\":100}}}}";

        assertThat(billing.webhook(body, "not-a-signature")).isFalse();
        assertThat(billing.webhook(body, RazorpaySignatures.hmacSha256Hex(body, WEBHOOK_SECRET))).isTrue();

        verify(payments, never()).markPaid(any(), any(), any());
        verifyNoInteractions(subscriptions);
    }
}
