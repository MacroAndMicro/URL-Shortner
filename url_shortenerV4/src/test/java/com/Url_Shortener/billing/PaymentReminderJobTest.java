package com.Url_Shortener.billing;

import com.Url_Shortener.entity.PaymentReminder;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.repository.PaymentReminderRepository;
import com.Url_Shortener.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSendException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentReminderJobTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 9, 0);

    private UserRepository users;
    private PaymentReminderRepository reminders;
    private ReminderMailer mailer;
    private PaymentReminderJob job;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        reminders = mock(PaymentReminderRepository.class);
        mailer = mock(ReminderMailer.class);
        PlanService planService = new PlanService(users, mock(StringRedisTemplate.class),
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        BillingService billing = new BillingService(mock(RazorpayClient.class), null, users, planService, null, 9900, 99900);
        job = new PaymentReminderJob(users, reminders, mailer, billing, planService);
    }

    private User user(long id, Plan plan, LocalDateTime due, String email) {
        User u = new User();
        u.setId(id);
        u.setUsername("user" + id);
        u.setEmail(email);
        u.setPlan(plan);
        u.setPlanPeriodEnd(due);
        return u;
    }

    @Test
    void eachWindowHasItsReminder() {
        LocalDateTime due = NOW;
        assertThat(ReminderKind.at(due, NOW.minusDays(3).minusMinutes(1))).isNull();
        assertThat(ReminderKind.at(due, NOW.minusDays(2))).isEqualTo(ReminderKind.DUE_SOON);
        assertThat(ReminderKind.at(due, NOW.plusHours(5))).isEqualTo(ReminderKind.DUE_TODAY);
        assertThat(ReminderKind.at(due, NOW.plusDays(2))).isNull();
        assertThat(ReminderKind.at(due, NOW.plusDays(4))).isEqualTo(ReminderKind.GRACE);
        assertThat(ReminderKind.at(due, NOW.plusDays(6).plusHours(3))).isEqualTo(ReminderKind.GRACE_LAST_DAY);
        assertThat(ReminderKind.at(due, NOW.plusDays(8))).isEqualTo(ReminderKind.LAPSED);
        assertThat(ReminderKind.at(due, NOW.plusDays(11).plusHours(1))).isEqualTo(ReminderKind.LINKS_LAST_DAY);
        assertThat(ReminderKind.at(due, NOW.plusDays(12))).isNull();
    }

    @Test
    void sendsTheDueReminderOnceAndRecordsIt() {
        User soon = user(1L, Plan.MONTHLY, NOW.plusDays(2), "soon@example.com");
        when(users.findByPlanInAndPlanPeriodEndBetween(any(), eq(NOW.minusDays(12)), eq(NOW.plusDays(3))))
                .thenReturn(List.of(soon));

        assertThat(job.run()).isEqualTo(1);

        verify(mailer).send(soon, ReminderKind.DUE_SOON, "₹99");
        verify(reminders).save(argThat((PaymentReminder r) -> r.getKind().equals("DUE_SOON")
                && r.getPeriodEnd().equals(soon.getPlanPeriodEnd())));

        when(reminders.existsByUserIdAndPeriodEndAndKind(1L, soon.getPlanPeriodEnd(), "DUE_SOON")).thenReturn(true);
        assertThat(job.run()).isZero();
        verify(mailer, times(1)).send(any(), any(), any());
    }

    @Test
    void aFailedSendIsNotRecordedSoItIsRetriedTomorrow() {
        User lapsed = user(2L, Plan.YEARLY, NOW.minusDays(8), "lapsed@example.com");
        when(users.findByPlanInAndPlanPeriodEndBetween(any(), any(), any())).thenReturn(List.of(lapsed));
        doThrow(new MailSendException("smtp down")).when(mailer).send(any(), any(), any());

        assertThat(job.run()).isZero();

        verify(reminders, never()).save(any());
    }

    @Test
    void usersWithoutARealEmailGetNoEmail() {
        User placeholder = user(3L, Plan.MONTHLY, NOW, "github-42@users.noreply.shortlink.invalid");
        when(users.findByPlanInAndPlanPeriodEndBetween(any(), any(), any())).thenReturn(List.of(placeholder));

        assertThat(job.run()).isZero();

        verifyNoInteractions(mailer);
    }

    @Test
    void emailsNameThePriceDatesAndLinkToPay() {
        ReminderMailer realMailer = new ReminderMailer((org.springframework.mail.javamail.JavaMailSender) null, "ShortLink <no-reply@x>", "http://localhost:4200");
        User u = user(4L, Plan.MONTHLY, LocalDateTime.of(2026, 12, 1, 10, 0), "a@example.com");

        ReminderMailer.Email grace = realMailer.write(u, ReminderKind.GRACE, "₹99");
        ReminderMailer.Email lapsed = realMailer.write(u, ReminderKind.LAPSED, "₹99");

        assertThat(grace.subject()).contains("pay by 8 December 2026");
        assertThat(grace.body()).contains("₹99").contains("http://localhost:4200/plan");
        assertThat(lapsed.body()).contains("stop working on 13 December 2026");
    }

    @Test
    void pricesAreShownInRupees() {
        assertThat(PaymentReminderJob.rupees(9900)).isEqualTo("₹99");
        assertThat(PaymentReminderJob.rupees(99900)).isEqualTo("₹999");
    }
}
