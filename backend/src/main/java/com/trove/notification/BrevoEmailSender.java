/*
 * ============================================================================
 *  BrevoEmailSender — EmailSender over Brevo's transactional email API
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Sends reminder emails through Brevo (formerly Sendinblue), whose free tier
 *  (300 emails/day) is ample for a 50–100 user vault and needs no custom domain —
 *  only a verified sender address.
 *
 *  Business use case
 *  -----------------
 *  The reliable reminder channel: it reaches the user whether or not the app is open,
 *  unlike an on-device notification.
 *
 *  Solution architecture
 *  ---------------------
 *  POST /v3/smtp/email with the api-key header. Blank credentials => no-op (logged),
 *  so the app runs before email is set up. Never throws on failure: a bad send is
 *  logged and reported as false so the scheduler keeps going.
 *
 *  Reasoning & logic
 *  -----------------
 *  Plain-text content keeps deliverability simple and the message legible. The API
 *  key lives only in configuration/env, never in source.
 * ============================================================================
 */
package com.trove.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class BrevoEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailSender.class);
    private static final String ENDPOINT = "https://api.brevo.com/v3/smtp/email";

    private final EmailProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public BrevoEmailSender(EmailProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean send(List<String> to, String subject, String textBody) {
        return send(to, subject, textBody, null);
    }

    @Override
    public boolean send(List<String> to, String subject, String textBody, String htmlBody) {
        if (!props.isConfigured()) {
            log.info("Email not configured (trove.email.*) - skipping send of '{}'", subject);
            return false;
        }
        if (to == null || to.isEmpty()) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(20))
                    .header("api-key", props.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(to, subject, textBody, htmlBody)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                return true;
            }
            log.warn("Brevo send failed (HTTP {}) for '{}'", resp.statusCode(), subject);
            return false;
        } catch (Exception e) {
            log.warn("Brevo send errored for '{}': {}", subject, e.getMessage());
            return false;
        }
    }

    private String buildBody(List<String> to, String subject, String textBody, String htmlBody) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode sender = root.putObject("sender");
        sender.put("name", props.getFromName());
        sender.put("email", props.getFromEmail());
        ArrayNode recipients = root.putArray("to");
        for (String addr : to) {
            recipients.addObject().put("email", addr);
        }
        root.put("subject", subject);
        root.put("textContent", textBody);
        // A themed HTML version renders in clients that support it; textContent is the fallback.
        if (htmlBody != null && !htmlBody.isBlank()) {
            root.put("htmlContent", htmlBody);
        }
        return root.toString();
    }
}
