/*
 * ============================================================================
 *  GoogleDriveOAuthService — the OAuth 2.0 authorization-code flow for Drive
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Builds the Google consent URL, exchanges the returned code for tokens (including
 *  a refresh token), and builds an authenticated Drive client from a refresh token.
 *
 *  Business use case
 *  -----------------
 *  Lets a space owner grant Trove permission to write into THEIR Google Drive, so
 *  each user's backups live in their own free 15 GB (DECISIONS.md → D17).
 *
 *  Solution architecture
 *  ---------------------
 *  Uses the official Google Java client libraries. Scope is drive.file only (the app
 *  can only touch what it creates). access_type=offline + prompt=consent are set so
 *  Google reliably returns a refresh token we can use for unattended sync.
 *
 *  Reasoning & logic
 *  -----------------
 *  GoogleCredential auto-refreshes short-lived access tokens from the stored refresh
 *  token, so scheduled syncs keep working without re-consent.
 * ============================================================================
 */
package com.trove.drive;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GoogleDriveOAuthService {

    private static final String APP_NAME = "Trove";

    private final GoogleOAuthProperties props;
    private final HttpTransport transport;
    private final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    public GoogleDriveOAuthService(GoogleOAuthProperties props) {
        this.props = props;
        try {
            this.transport = GoogleNetHttpTransport.newTrustedTransport();
        } catch (Exception e) {
            throw new IllegalStateException("Could not init Google HTTP transport", e);
        }
    }

    private GoogleAuthorizationCodeFlow flow() {
        return new GoogleAuthorizationCodeFlow.Builder(
                transport, jsonFactory, props.getClientId(), props.getClientSecret(),
                List.of(DriveScopes.DRIVE_FILE))
                .setAccessType("offline")
                .build();
    }

    /** The Google consent URL to send the owner to (state carries the signed space id). */
    public String authorizationUrl(String state) {
        com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl url =
                flow().newAuthorizationUrl();
        url.setRedirectUri(props.getRedirectUri());
        url.setState(state);
        url.set("prompt", "consent");             // force a refresh token every time
        url.set("include_granted_scopes", "true");
        return url.build();
    }

    /** Exchanges the auth code for tokens (contains the refresh token on first consent). */
    public GoogleTokenResponse exchange(String code) throws Exception {
        return flow().newTokenRequest(code).setRedirectUri(props.getRedirectUri()).execute();
    }

    /** Builds an authenticated Drive client from a stored refresh token. */
    public Drive driveFor(String refreshToken) {
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(transport)
                .setJsonFactory(jsonFactory)
                .setClientSecrets(props.getClientId(), props.getClientSecret())
                .build()
                .setRefreshToken(refreshToken);
        return new Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName(APP_NAME)
                .build();
    }
}
