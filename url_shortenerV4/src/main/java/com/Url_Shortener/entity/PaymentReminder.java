package com.Url_Shortener.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** A payment reminder email that was sent; see PaymentReminderJob. */
@Entity
@Table(name = "payment_reminders",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_reminders_once",
                columnNames = {"user_id", "period_end", "kind"}))
public class PaymentReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The paid period it was about: a new period gets fresh reminders. */
    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "sent_date", nullable = false)
    private LocalDateTime sentDate;

    protected PaymentReminder() {
    }

    public PaymentReminder(Long userId, LocalDateTime periodEnd, String kind, LocalDateTime sentDate) {
        this.userId = userId;
        this.periodEnd = periodEnd;
        this.kind = kind;
        this.sentDate = sentDate;
    }

    public Long getUserId() { return userId; }
    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public String getKind() { return kind; }
}
