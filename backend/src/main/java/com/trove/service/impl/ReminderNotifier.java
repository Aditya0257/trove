/*
 * ============================================================================
 *  ReminderNotifier — turns a due reminder into an email to the space's members
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Given a reminder that has come due, work out who should hear about it (the space's
 *  members) and what to say (from the linked document's due date and amount), then
 *  send it via the EmailSender.
 *
 *  Business use case
 *  -----------------
 *  This is the delivery half of reminders — the "your policy renews in 7 days" email.
 *  Phone popups are handled on-device by the mobile app; this reaches the user even
 *  when nothing is open.
 *
 *  Solution architecture
 *  ---------------------
 *  Recipients = every member of the reminder's space (resolved to their email).
 *  Sending goes through the swappable EmailSender, which is a safe no-op until email
 *  is configured — so this never blocks the scheduler.
 *
 *  Reasoning & logic
 *  -----------------
 *  The wording adapts to how far off the due date is (in N days / tomorrow / today /
 *  overdue). Kept plain-text and self-contained; no secrets.
 * ============================================================================
 */
package com.trove.service.impl;
import com.trove.enums.ReminderType;
import com.trove.entity.Reminder;

import com.trove.entity.User;
import com.trove.repository.UserRepository;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.integration.EmailSender;
import com.trove.repository.SpaceMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Component
public class ReminderNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReminderNotifier.class);

    private final DocumentRepository documentRepository;
    private final SpaceMemberRepository spaceMemberRepository;
    private final UserRepository userRepository;
    private final EmailSender emailSender;

    public ReminderNotifier(DocumentRepository documentRepository,
                            SpaceMemberRepository spaceMemberRepository,
                            UserRepository userRepository, EmailSender emailSender) {
        this.documentRepository = documentRepository;
        this.spaceMemberRepository = spaceMemberRepository;
        this.userRepository = userRepository;
        this.emailSender = emailSender;
    }

    /** Emails the space's members about a due reminder. Failures are logged, not thrown. */
    public void dispatch(Reminder reminder, LocalDate today) {
        List<String> recipients = recipientsFor(reminder.getSpaceId());
        if (recipients.isEmpty()) {
            return;
        }
        Document doc = reminder.getDocumentId() == null
                ? null
                : documentRepository.findById(reminder.getDocumentId()).orElse(null);
        LocalDate dueDate = doc != null ? doc.getDueDate() : null;

        String when = phraseFor(today, dueDate);
        String noun = nounFor(reminder.getType());
        String subject = "Trove reminder: " + noun + " " + when;

        StringBuilder body = new StringBuilder();
        body.append("Heads up from your Trove vault.\n\n");
        body.append("A ").append(noun).append(' ').append(when).append('.');
        if (dueDate != null) {
            body.append("\nDate: ").append(dueDate);
        }
        if (doc != null && doc.getAmount() != null) {
            body.append("\nAmount: ")
                .append(doc.getCurrency() != null ? doc.getCurrency() + " " : "")
                .append(doc.getAmount().toPlainString());
        }
        body.append("\n\nOpen Trove to review it.");

        boolean sent = emailSender.send(recipients, subject, body.toString());
        log.info("Reminder {} ({}) -> {} recipient(s), emailed={}",
                reminder.getId(), reminder.getType(), recipients.size(), sent);
    }

    private List<String> recipientsFor(java.util.UUID spaceId) {
        return spaceMemberRepository.findBySpaceId(spaceId).stream()
                .map(m -> userRepository.findById(m.getUserId()).map(User::getEmail).orElse(null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private String nounFor(String type) {
        return switch (type) {
            case ReminderType.RENEWAL -> "renewal";
            case ReminderType.WARRANTY_EXPIRY -> "warranty";
            default -> "payment";
        };
    }

    private String phraseFor(LocalDate today, LocalDate dueDate) {
        if (dueDate == null) {
            return "needs your attention";
        }
        long days = ChronoUnit.DAYS.between(today, dueDate);
        if (days > 1) return "is due in " + days + " days";
        if (days == 1) return "is due tomorrow";
        if (days == 0) return "is due today";
        return "is overdue by " + Math.abs(days) + " day" + (Math.abs(days) == 1 ? "" : "s");
    }
}
