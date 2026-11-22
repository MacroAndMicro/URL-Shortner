package com.Url_Shortener.repository;

import com.Url_Shortener.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    List<Payment> findTop20ByUserIdOrderByCreatedDateDesc(Long userId);

    /**
     * Marks a payment paid if it is not already, in one statement, so that when
     * the browser and Razorpay's webhook confirm the same payment at once only
     * one of them applies it.
     *
     * @return 1 for the caller that marked it paid, 0 for everyone else
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Payment p SET p.status = com.Url_Shortener.entity.Payment.Status.PAID,
                                 p.razorpayPaymentId = :paymentId, p.paidDate = :paidDate
             WHERE p.id = :id AND p.status = com.Url_Shortener.entity.Payment.Status.CREATED
            """)
    int markPaid(@Param("id") Long id, @Param("paymentId") String paymentId,
                 @Param("paidDate") LocalDateTime paidDate);
}
