package com.Url_Shortener.billing;

import com.Url_Shortener.dto.ApiResponse;
import com.Url_Shortener.entity.Payment;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billing;
    private final UserService userService;

    public BillingController(BillingService billing, UserService userService) {
        this.billing = billing;
        this.userService = userService;
    }

    /** Plans and prices, and whether payments are set up. Public, for the landing page. */
    @GetMapping("/prices")
    public ResponseEntity<ApiResponse<BillingService.Prices>> prices() {
        return ResponseEntity.ok(ApiResponse.success(billing.prices()));
    }

    public record OrderRequest(Plan plan) {
    }

    /** Starts a payment: returns what Razorpay Checkout needs. */
    @PostMapping("/orders")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<BillingService.CheckoutOrder>> createOrder(@RequestBody OrderRequest request,
                                                                                Principal principal) {
        if (request.plan() == null) {
            throw new IllegalArgumentException("plan is required");
        }
        User user = userService.findByUsername(principal.getName());
        return ResponseEntity.ok(ApiResponse.success(billing.createOrder(user, request.plan())));
    }

    /** Checkout's result, as Razorpay names the fields. */
    public record VerifyRequest(String razorpay_order_id, String razorpay_payment_id, String razorpay_signature) {
    }

    /** Confirms a payment the browser completed in Razorpay Checkout. */
    @PostMapping("/verify")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<PaymentDto>> verify(@RequestBody VerifyRequest request, Principal principal) {
        User user = userService.findByUsername(principal.getName());
        Payment payment = billing.verify(user, request.razorpay_order_id(), request.razorpay_payment_id(),
                request.razorpay_signature());
        return ResponseEntity.ok(ApiResponse.success(PaymentDto.of(payment), "Payment received. Thank you!"));
    }

    /**
     * Razorpay's webhook. Public, but only acted on when the signature checks
     * out; answers 400 otherwise so Razorpay shows the failure.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String body,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        return billing.webhook(body, signature) ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }

    /** The user's latest payments, newest first. */
    @GetMapping("/payments")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<List<PaymentDto>>> payments(Principal principal) {
        User user = userService.findByUsername(principal.getName());
        return ResponseEntity.ok(ApiResponse.success(billing.history(user).stream().map(PaymentDto::of).toList()));
    }

    public record PaymentDto(Long id, Plan plan, int amountPaise, String currency, Payment.Status status,
                             LocalDateTime createdDate, LocalDateTime paidDate, LocalDateTime periodEndAfter) {
        static PaymentDto of(Payment p) {
            return new PaymentDto(p.getId(), p.getPlan(), p.getAmountPaise(), p.getCurrency(), p.getStatus(),
                    p.getCreatedDate(), p.getPaidDate(), p.getPeriodEndAfter());
        }
    }
}
