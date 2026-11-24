package com.project.devsphere.operations.application.handlers;

import com.project.devsphere.operations.application.ClaimedJob;
import com.project.devsphere.operations.application.JobHandler;
import com.project.devsphere.operations.application.JobResult;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivers the e-mail copy of a notification whose {@code email_status} is {@code PENDING}.
 *
 * <p>The mail sender is optional: when no {@link JavaMailSender} bean, SMTP host or sender address
 * is configured the job fails with a retryable error so it can be replayed once mail is set up.
 * Delivery is idempotent because only PENDING notifications are sent and marked SENT.</p>
 */
@Component
public class NotificationEmailJobHandler implements JobHandler {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;
    private final String smtpHost;

    public NotificationEmailJobHandler(
            JdbcTemplate jdbc,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${devsphere.auth.mail.from:}") String fromAddress,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.jdbc = jdbc;
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
        this.smtpHost = smtpHost;
    }

    @Override
    public BackgroundJob.JobKind kind() {
        return BackgroundJob.JobKind.NOTIFICATION_EMAIL;
    }

    /** The configured sender, present only when SMTP host and from-address are both set. */
    Optional<JavaMailSender> mailSender() {
        if (smtpHost == null || smtpHost.isBlank() || fromAddress == null || fromAddress.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mailSenderProvider.getIfAvailable());
    }

    @Override
    public JobResult handle(ClaimedJob job) {
        UUID notificationId = job.requireUuid("notificationId");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select n.title, n.body, n.deeplink, n.email_status, u.email, u.status as user_status
                from notifications n join users u on u.id=n.user_id
                where n.id=?
                """, notificationId);
        if (rows.isEmpty()) {
            return JobResult.skipped("NOTIFICATION_NOT_FOUND");
        }
        Map<String, Object> row = rows.getFirst();
        if (!"PENDING".equals(row.get("email_status"))) {
            return JobResult.skipped("EMAIL_ALREADY_" + row.get("email_status"));
        }
        if (!"ACTIVE".equals(row.get("user_status"))) {
            markEmail(notificationId, "FAILED");
            return JobResult.skipped("RECIPIENT_NOT_ACTIVE");
        }

        JavaMailSender sender = mailSender()
                .orElseThrow(() -> new IllegalStateException("MAIL_NOT_CONFIGURED"));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(String.valueOf(row.get("email")));
        message.setSubject(String.valueOf(row.get("title")));
        Object deeplink = row.get("deeplink");
        message.setText(row.get("body") + (deeplink == null ? "" : "\n\n" + deeplink));
        try {
            sender.send(message);
        } catch (MailException ex) {
            // Retryable: the notification stays PENDING and the dispatcher reschedules with backoff.
            throw new IllegalStateException("MAIL_SEND_ERROR", ex);
        }
        markEmail(notificationId, "SENT");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("notificationId", notificationId.toString());
        details.put("outcome", "SENT");
        return JobResult.of(details);
    }

    private void markEmail(UUID notificationId, String status) {
        jdbc.update("update notifications set email_status=?, updated_at=now() where id=? and email_status='PENDING'",
                status, notificationId);
    }
}
