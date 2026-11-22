package com.Url_Shortener.repository;

import com.Url_Shortener.entity.PaymentReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface PaymentReminderRepository extends JpaRepository<PaymentReminder, Long> {

    boolean existsByUserIdAndPeriodEndAndKind(Long userId, LocalDateTime periodEnd, String kind);
}
