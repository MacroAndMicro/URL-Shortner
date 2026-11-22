package com.Url_Shortener.billing;

import com.Url_Shortener.entity.Payment;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.exception.LimitExceededException;
import com.Url_Shortener.exception.ServiceUnavailableException;
import com.Url_Shortener.exception.UrlMappingNotFoundException;
import com.Url_Shortener.plan.OwnerStatus;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.plan.SubscriptionService;
import com.Url_Shortener.repository.PaymentRepository;
import com.Url_Shortener.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paying for Monthly and Yearly plans through Razorpay.
 *
 * 1. createOrder: a Razorpay order for the plan's price, stored as CREATED.
 * 2. The browser collects payment in Razorpay Checkout.
 * 3. verify (from the browser) or webhook (from Razorpay) confirms it. The
 *    first to arrive marks it PAID and extends the plan; the other finds it
 *    already paid and does nothing.
 *
 * Plans are paid manually each period. What a payment buys:
 * - while the plan is active or in its grace period: one more period counted
 *   from the due date, so paying late in the grace period gains nothing;
 * - otherwise (Free, or after a lapse): one period from now.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    static final String CURRENCY = "INR";

    private final RazorpayClient razorpay;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final PlanService planService;
    private final SubscriptionService subscriptions;
    private final int monthlyPricePaise;
    private final int yearlyPricePaise;
    private final JsonMapper json = JsonMapper.builder().build();

    public BillingService(RazorpayClient razorpay, PaymentRepository payments, UserRepository users,
                          PlanService planService, SubscriptionService subscriptions,
                          @Value("${app.billing.monthly-price-paise:9900}") int monthlyPricePaise,
                          @Value("${app.billing.yearly-price-paise:99900}") int yearlyPricePaise) {
        this.razorpay = razorpay;
        this.payments = payments;
        this.users = users;
        this.planService = planService;
        this.subscriptions = subscriptions;
        this.monthlyPricePaise = monthlyPricePaise;
        this.yearlyPricePaise = yearlyPricePaise;
    }

    // ============================================================
    // PRICES
    // ============================================================

    public record Price(Plan plan, int amountPaise, String currency, String period) {
    }

    public record Prices(boolean paymentsEnabled, List<Price> plans) {
    }

    public Prices prices() {
        return new Prices(razorpay.enabled(), List.of(
                new Price(Plan.MONTHLY, monthlyPricePaise, CURRENCY, "month"),
                new Price(Plan.YEARLY, yearlyPricePaise, CURRENCY, "year")));
    }

    int priceOf(Plan plan) {
        return switch (plan) {
            case MONTHLY -> monthlyPricePaise;
            case YEARLY -> yearlyPricePaise;
            default -> throw new IllegalArgumentException("Only Monthly and Yearly plans can be bought");
        };
    }

    // ============================================================
    // ORDER
    // ============================================================

    /** What the browser needs to open Razorpay Checkout for an order. */
    public record CheckoutOrder(String orderId, String keyId, int amountPaise, String currency, Plan plan,
                                String description, String email, String username) {
    }

    @Transactional
    public CheckoutOrder createOrder(User user, Plan plan) {
        if (!razorpay.enabled()) {
            throw new ServiceUnavailableException("Payments are not set up yet.");
        }
        int amount = priceOf(plan);
        checkAllowed(user, plan);

        LocalDateTime now = planService.now();
        String receipt = "u" + user.getId() + "-" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        RazorpayClient.Order order = razorpay.createOrder(amount, CURRENCY, receipt,
                Map.of("userId", user.getId().toString(), "plan", plan.name()));
        payments.save(new Payment(user, plan, amount, CURRENCY, order.id(), now));

        String description = plan.displayName() + " plan, 1 " + (plan == Plan.MONTHLY ? "month" : "year");
        return new CheckoutOrder(order.id(), razorpay.keyId(), amount, CURRENCY, plan, description,
                user.getEmail().endsWith(".invalid") ? null : user.getEmail(), user.getUsername());
    }

    /** Monthly cannot be bought while paid Yearly time remains. */
    void checkAllowed(User user, Plan plan) {
        LocalDateTime end = user.getPlanPeriodEnd();
        if (plan == Plan.MONTHLY && user.getPlan() == Plan.YEARLY && end != null && planService.now().isBefore(end)) {
            throw LimitExceededException.forbidden("Your Yearly plan is paid until "
                    + end.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                    + ". You can switch to Monthly when it ends.");
        }
    }

    // ============================================================
    // CONFIRMATION
    // ============================================================

    /**
     * Confirms a payment from Razorpay Checkout's result in the browser.
     *
     * @throws IllegalArgumentException (400) if the signature is not Razorpay's
     */
    @Transactional
    public Payment verify(User user, String orderId, String paymentId, String signature) {
        Payment payment = payments.findByRazorpayOrderId(orderId)
                .filter(p -> p.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new UrlMappingNotFoundException("Payment not found"));
        if (paymentId == null || !RazorpaySignatures.isValidPayment(orderId, paymentId, signature, razorpay.keySecret())) {
            log.warn("Rejected payment confirmation with a bad signature: order={}", orderId);
            throw new IllegalArgumentException("The payment could not be verified.");
        }
        confirm(payment, paymentId);
        return payments.findById(payment.getId()).orElseThrow();
    }

    /**
     * Handles a Razorpay webhook (payment.captured, order.paid), which confirms
     * a payment even if the browser was closed before verify.
     *
     * @return false if the signature is not Razorpay's (the caller answers 400)
     */
    @Transactional
    public boolean webhook(String body, String signature) {
        if (razorpay.webhookSecret().isBlank()
                || !RazorpaySignatures.isValidWebhook(body, signature, razorpay.webhookSecret())) {
            log.warn("Rejected Razorpay webhook with a bad or unchecked signature");
            return false;
        }
        JsonNode event = json.readTree(body);
        String type = event.path("event").asText();
        if (!type.equals("payment.captured") && !type.equals("order.paid")) {
            return true; // not one we act on
        }
        JsonNode entity = event.path("payload").path("payment").path("entity");
        String orderId = entity.path("order_id").asText(null);
        String paymentId = entity.path("id").asText(null);
        int amount = entity.path("amount").asInt(-1);
        payments.findByRazorpayOrderId(orderId).ifPresentOrElse(payment -> {
            if (amount != payment.getAmountPaise()) {
                log.warn("Razorpay webhook amount {} does not match order {} ({}); ignored",
                        amount, orderId, payment.getAmountPaise());
                return;
            }
            confirm(payment, paymentId);
        }, () -> log.info("Razorpay webhook for an unknown order {}; ignored", orderId));
        return true;
    }

    /**
     * Marks the payment paid and extends the plan, once. Runs inside the
     * caller's transaction: if extending fails, the payment stays CREATED and
     * the next confirmation retries it.
     */
    private void confirm(Payment payment, String paymentId) {
        LocalDateTime now = planService.now();
        if (payments.markPaid(payment.getId(), paymentId, now) == 0) {
            return; // already confirmed
        }
        User user = users.findById(payment.getUser().getId()).orElseThrow();
        LocalDateTime newEnd = periodEndAfterPayment(user, payment.getPlan());
        subscriptions.changeSubscription(user, payment.getPlan(), newEnd);
        Payment paid = payments.findById(payment.getId()).orElseThrow();
        paid.setPeriodEndAfter(newEnd);
        payments.save(paid);
        log.info("Payment {} confirmed: user {} on {} until {}", paymentId, user.getId(), payment.getPlan(), newEnd);
    }

    /** End of the paid period once a payment for plan is applied. */
    LocalDateTime periodEndAfterPayment(User user, Plan plan) {
        OwnerStatus status = planService.status(user);
        LocalDateTime base = status.effective().isPaid() ? user.getPlanPeriodEnd() : planService.now();
        return plan == Plan.MONTHLY ? base.plusMonths(1) : base.plusYears(1);
    }

    // ============================================================
    // HISTORY
    // ============================================================

    public List<Payment> history(User user) {
        return payments.findTop20ByUserIdOrderByCreatedDateDesc(user.getId());
    }
}
