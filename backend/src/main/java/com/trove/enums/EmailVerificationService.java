/*
 * ============================================================================
 *  EmailVerificationService — issue and check the sign-up email OTP
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Generates a six-digit one-time code, stores only its SHA-256 hash with a short
 *  expiry, emails the plaintext to the address the user signed up with, and later
 *  checks a presented code against it.
 *
 *  Business use case
 *  -----------------
 *  Proves a new user owns the email they registered, before the account is shown to
 *  the admin for approval, so only real, reachable addresses get into Trove.
 *
 *  Reasoning & logic
 *  -----------------
 *  One active code per user (overwritten on resend). The code expires after
 *  EXPIRY_MINUTES and allows at most MAX_ATTEMPTS wrong tries, so it cannot be
 *  brute-forced. Only the hash is stored, mirroring the password-reset design.
 * ============================================================================
 */
package com.trove.enums;
import com.trove.entity.EmailVerification;
import com.trove.repository.EmailVerificationRepository;

import com.trove.common.HashUtil;
import com.trove.integration.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int EXPIRY_MINUTES = 15;
    private static final int MAX_ATTEMPTS = 5;

    /** The outcome of checking a presented code. */
    public enum Result { OK, INVALID, EXPIRED, LOCKED, NOT_FOUND }

    private final EmailVerificationRepository repository;
    private final EmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailVerificationRepository repository, EmailSender emailSender) {
        this.repository = repository;
        this.emailSender = emailSender;
    }

    /** Issues a fresh code for the user (overwriting any previous one) and emails it. */
    @Transactional
    public void send(UUID userId, String email, String displayName) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        String hash = HashUtil.sha256Hex(code.getBytes(StandardCharsets.UTF_8));
        repository.save(new EmailVerification(userId, hash, Instant.now().plus(EXPIRY_MINUTES, ChronoUnit.MINUTES)));

        String text = "Hi " + displayName + ",\n\n"
                + "Your Trove verification code is:\n\n"
                + "    " + code + "\n\n"
                + "Enter it in the app to confirm your email. It stays valid for the next "
                + EXPIRY_MINUTES + " minutes.\n\n"
                + "If you did not sign up for Trove, you can safely ignore this email.";
        String html = buildHtml(displayName, code);

        boolean sent = emailSender.send(java.util.List.of(email), "Your Trove verification code", text, html);
        log.info("Issued email-verification code for user {} (emailed={})", userId, sent);
    }

    /** A small, themed HTML email: greeting, the code shown large and evenly spaced (so the
     *  digits never look cramped), and a plain expiry line. Inline styles for email clients. */
    private String buildHtml(String displayName, String code) {
        return """
            <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\
            max-width:480px;margin:0 auto;padding:8px 4px;color:#21252c;">
              <p style="font-size:15px;line-height:1.5;margin:0 0 6px;">Hi %s,</p>
              <p style="font-size:15px;line-height:1.5;margin:0 0 18px;">Enter this code in Trove to confirm your email:</p>
              <div style="font-size:34px;font-weight:700;letter-spacing:10px;text-indent:10px;\
            background:#f3f2ef;border:1px solid #e3e1da;border-radius:14px;padding:20px 0;\
            text-align:center;color:#2f6f6a;margin:0 0 18px;">%s</div>
              <p style="font-size:14px;line-height:1.55;color:#6c6a63;margin:0 0 4px;">\
            This code is valid for the next %d minutes.</p>
              <p style="font-size:13px;line-height:1.55;color:#9a978d;margin:0;">\
            If you did not sign up for Trove, you can safely ignore this email.</p>
            </div>""".formatted(escape(displayName), code, EXPIRY_MINUTES);
    }

    /** Minimal HTML escaping for the one interpolated free-text value (the display name). */
    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Checks a presented code. On OK the row is consumed (deleted); on a wrong code the
     *  attempt is counted and the row locks once MAX_ATTEMPTS is reached. */
    @Transactional
    public Result verify(UUID userId, String code) {
        EmailVerification ev = repository.findById(userId).orElse(null);
        if (ev == null) {
            return Result.NOT_FOUND;
        }
        if (Instant.now().isAfter(ev.getExpiresAt())) {
            return Result.EXPIRED;
        }
        if (ev.getAttempts() >= MAX_ATTEMPTS) {
            return Result.LOCKED;
        }
        String hash = HashUtil.sha256Hex((code == null ? "" : code.trim()).getBytes(StandardCharsets.UTF_8));
        if (hash.equals(ev.getCodeHash())) {
            repository.deleteById(userId);
            return Result.OK;
        }
        ev.setAttempts(ev.getAttempts() + 1);
        repository.save(ev);
        return Result.INVALID;
    }
}
