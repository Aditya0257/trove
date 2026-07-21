/*
 * ============================================================================
 *  EmailProperties — outbound email configuration (trove.email.*)
 * ============================================================================
 *  Purpose:        binds the email sender's credentials and the From identity.
 *  Business use:    reminder emails ("your policy renews in 7 days") need a sender;
 *                  this is where the free provider's API key and From address live.
 *  Design:         provider-agnostic. Blank api-key => email is simply not sent
 *                  (reminders still fire in-app + as phone notifications), so the app
 *                  runs fine before email is configured. Set via env only (no secrets
 *                  in source).
 * ============================================================================
 */
package com.trove.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.email")
public class EmailProperties {

    /** Provider API key (e.g. Brevo). Blank disables sending. */
    private String apiKey = "";

    /** Verified sender address the provider will send from. */
    private String fromEmail = "";

    /** Display name shown on the From line. */
    private String fromName = "Trove";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    /** True only when there's enough config to actually send. */
    public boolean isConfigured() {
        return !apiKey.isBlank() && !fromEmail.isBlank();
    }
}
