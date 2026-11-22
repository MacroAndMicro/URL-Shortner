package com.Url_Shortener.billing;

import com.Url_Shortener.entity.User;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Writes and sends payment reminder emails (plain text). Locally they go to
 * Mailpit (http://localhost:8025); in production to whatever SMTP server
 * SPRING_MAIL_* points at.
 */
@Component
public class ReminderMailer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** Null when no mail server is configured (spring.mail.host unset). */
    private final JavaMailSender mail;
    private final String from;
    private final String planUrl;

    @Autowired
    public ReminderMailer(ObjectProvider<JavaMailSender> mail,
                          @Value("${app.mail.from:ShortLink <no-reply@shortlink.local>}") String from,
                          @Value("${frontend.url:http://localhost:4200}") String frontendUrl) {
        this(mail.getIfAvailable(), from, frontendUrl);
    }

    ReminderMailer(JavaMailSender mail, String from, String frontendUrl) {
        this.mail = mail;
        this.from = from;
        this.planUrl = frontendUrl + "/plan";
    }

    /** Subject and body of a reminder. */
    record Email(String subject, String body) {
    }

    public void send(User user, ReminderKind kind, String price) {
        if (mail == null) {
            throw new IllegalStateException("no mail server configured (spring.mail.host)");
        }
        Email email = write(user, kind, price);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject(email.subject());
        message.setText(email.body());
        mail.send(message);
    }

    Email write(User user, ReminderKind kind, String price) {
        Plan plan = user.getPlan();
        LocalDateTime due = user.getPlanPeriodEnd();
        String dueDate = due.format(DATE);
        String graceEnd = due.plus(PlanService.GRACE_PERIOD).format(DATE);
        String linksEnd = due.plus(PlanService.GRACE_PERIOD).plus(Plan.FREE.linkLifetime()).format(DATE);
        String name = plan.displayName();

        String subject;
        String text;
        switch (kind) {
            case DUE_SOON -> {
                subject = "Your " + name + " plan renews on " + dueDate;
                text = "Your " + name + " plan is paid until " + dueDate + ". Renew for " + price
                        + " to keep your links and analytics without a break.";
            }
            case DUE_TODAY -> {
                subject = "Your " + name + " plan payment is due today";
                text = "Your " + name + " plan payment of " + price + " is due today. You have until "
                        + graceEnd + " to pay before your account moves to the Free plan.";
            }
            case GRACE -> {
                subject = "Payment overdue: pay by " + graceEnd + " to keep your " + name + " plan";
                text = "We haven't received your " + name + " plan payment of " + price + ". Pay by " + graceEnd
                        + " to keep your plan. After that your account moves to the Free plan.";
            }
            case GRACE_LAST_DAY -> {
                subject = "Last day to keep your " + name + " plan";
                text = "Today is the last day to pay " + price + " for your " + name
                        + " plan. From tomorrow your account moves to the Free plan.";
            }
            case LAPSED -> {
                subject = "Your " + name + " plan has ended";
                text = "Your " + name + " plan has ended and your account is now on the Free plan. The links you made on the "
                        + name + " plan will stop working on " + linksEnd
                        + " and can't be recovered after that. Renew before then to keep them.";
            }
            case LINKS_LAST_DAY -> {
                subject = "Your links stop working tomorrow";
                text = "The links you made on your " + name + " plan stop working tomorrow (" + linksEnd
                        + ") and can't be recovered after that. Renew today to keep them.";
            }
            default -> throw new IllegalArgumentException(kind.name());
        }
        String body = "Hi " + user.getUsername() + ",\n\n" + text + "\n\nPay now: " + planUrl
                + "\n\n— ShortLink\n\nYou are getting this because you have a paid ShortLink plan.\n";
        return new Email(subject, body);
    }
}
