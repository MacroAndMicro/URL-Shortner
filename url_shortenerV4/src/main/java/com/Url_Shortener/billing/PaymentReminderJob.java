package com.Url_Shortener.billing;

import com.Url_Shortener.entity.PaymentReminder;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.repository.PaymentReminderRepository;
import com.Url_Shortener.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Once a day, emails users whose paid plan is due, overdue or lapsed (see
 * ReminderKind). Each reminder is sent once per paid period; paying starts a
 * new period, so the remaining reminders for the old one never go out.
 *
 * If sending fails the reminder is not recorded, so it is tried again the
 * next day while its window lasts. Disable with app.billing.reminders.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "app.billing.reminders.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReminderJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReminderJob.class);

    private final UserRepository users;
    private final PaymentReminderRepository reminders;
    private final ReminderMailer mailer;
    private final BillingService billing;
    private final PlanService planService;

    public PaymentReminderJob(UserRepository users, PaymentReminderRepository reminders, ReminderMailer mailer,
                              BillingService billing, PlanService planService) {
        this.users = users;
        this.reminders = reminders;
        this.mailer = mailer;
        this.billing = billing;
        this.planService = planService;
    }

    @Scheduled(cron = "${app.billing.reminders.cron:0 0 9 * * *}")
    public void scheduledRun() {
        int sent = run();
        if (sent > 0) {
            log.info("Sent {} payment reminders", sent);
        }
    }

    /** @return how many reminders were sent */
    public int run() {
        var now = planService.now();
        List<User> due = users.findByPlanInAndPlanPeriodEndBetween(List.of(Plan.MONTHLY, Plan.YEARLY),
                ReminderKind.earliestDue(now), ReminderKind.latestDue(now));
        int sent = 0;
        for (User user : due) {
            ReminderKind kind = ReminderKind.at(user.getPlanPeriodEnd(), now);
            if (kind == null || user.getEmail() == null || user.getEmail().endsWith(".invalid")
                    || reminders.existsByUserIdAndPeriodEndAndKind(user.getId(), user.getPlanPeriodEnd(), kind.name())) {
                continue;
            }
            try {
                mailer.send(user, kind, rupees(billing.priceOf(user.getPlan())));
                reminders.save(new PaymentReminder(user.getId(), user.getPlanPeriodEnd(), kind.name(), now));
                sent++;
            } catch (DataIntegrityViolationException e) {
                // Another instance recorded it first; the email may have gone out twice.
                log.info("Reminder {} for user {} already recorded", kind, user.getId());
            } catch (RuntimeException e) {
                log.warn("Could not send {} reminder to user {}: {}", kind, user.getId(), e.getMessage());
            }
        }
        return sent;
    }

    static String rupees(int paise) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
        format.setMaximumFractionDigits(paise % 100 == 0 ? 0 : 2);
        return format.format(paise / 100.0);
    }
}
