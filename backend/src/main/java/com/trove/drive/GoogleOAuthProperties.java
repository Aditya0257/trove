/*
 * ============================================================================
 *  GoogleOAuthProperties — Google OAuth client config for the Drive leg
 * ============================================================================
 *  Purpose:        binds google.oauth.* (client id/secret, redirect uri) from env.
 *  Business use:    the OAuth app credentials used to let each space owner authorize
 *                  Trove to write into their Drive.
 *  Design:         secrets come from env only (never committed). configured() lets
 *                  endpoints fail cleanly when creds are absent.
 * ============================================================================
 */
package com.trove.drive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "google.oauth")
public class GoogleOAuthProperties {

    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "";

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
}
