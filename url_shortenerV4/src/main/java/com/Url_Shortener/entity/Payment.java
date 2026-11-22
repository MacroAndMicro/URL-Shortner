package com.Url_Shortener.entity;

import com.Url_Shortener.plan.Plan;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One payment for a paid plan, made through Razorpay. Created with the
 * Razorpay order (CREATED) and marked PAID once Razorpay confirms it, through
 * the browser (verify) or Razorpay's webhook, whichever comes first.
 */
@Entity
@Table(name = "payments")
public class Payment {

    public enum Status { CREATED, PAID, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan;

    @Column(name = "amount_paise", nullable = false)
    private int amountPaise;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "razorpay_order_id", nullable = false, unique = true, length = 64)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    @Column(name = "period_end_after")
    private LocalDateTime periodEndAfter;

    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate;

    @Column(name = "paid_date")
    private LocalDateTime paidDate;

    protected Payment() {
    }

    public Payment(User user, Plan plan, int amountPaise, String currency, String razorpayOrderId,
                   LocalDateTime createdDate) {
        this.user = user;
        this.plan = plan;
        this.amountPaise = amountPaise;
        this.currency = currency;
        this.status = Status.CREATED;
        this.razorpayOrderId = razorpayOrderId;
        this.createdDate = createdDate;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Plan getPlan() { return plan; }
    public int getAmountPaise() { return amountPaise; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getRazorpayOrderId() { return razorpayOrderId; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public LocalDateTime getPeriodEndAfter() { return periodEndAfter; }
    public LocalDateTime getCreatedDate() { return createdDate; }
    public LocalDateTime getPaidDate() { return paidDate; }

    public void setPeriodEndAfter(LocalDateTime periodEndAfter) { this.periodEndAfter = periodEndAfter; }
}
