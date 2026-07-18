/*
 * ============================================================================
 *  GoogleDriveController — connect / callback / status / sync endpoints
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Owner-driven OAuth: start the consent flow, handle Google's redirect, report
 *  connection status, and trigger a sync.
 *
 *  Business use case
 *  -----------------
 *  Lets a space owner link their Google Drive and back their documents up into it.
 *
 *  Solution architecture
 *  ---------------------
 *  connect/status/sync require auth (owner/member); the callback is public (the
 *  browser arrives from Google with no JWT) and instead trusts the signed `state`
 *  (AES-GCM encrypted space id — tamper-evident, stateless, no server-side table).
 *
 *  Reasoning & logic
 *  -----------------
 *  A missing refresh token on callback means Google didn't re-consent; prompt=consent
 *  is set to avoid that, and the message tells the user how to recover if it happens.
 * ============================================================================
 */
package com.trove.drive;

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.trove.common.security.CurrentUser;
import com.trove.common.security.EncryptionService;
import com.trove.space.SpaceAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/integrations/google-drive")
public class GoogleDriveController {

    private final GoogleDriveOAuthService oauthService;
    private final DriveSyncService driveSyncService;
    private final GoogleOAuthProperties props;
    private final SpaceAuthorization authorization;
    private final EncryptionService encryptionService;
    private final CurrentUser currentUser;

    public GoogleDriveController(GoogleDriveOAuthService oauthService, DriveSyncService driveSyncService,
                                GoogleOAuthProperties props, SpaceAuthorization authorization,
                                EncryptionService encryptionService, CurrentUser currentUser) {
        this.oauthService = oauthService;
        this.driveSyncService = driveSyncService;
        this.props = props;
        this.authorization = authorization;
        this.encryptionService = encryptionService;
        this.currentUser = currentUser;
    }

    /** Owner starts the flow → 302 to Google's consent screen (for direct/curl use). */
    @GetMapping("/connect")
    public ResponseEntity<Void> connect(@RequestParam("spaceId") UUID spaceId) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(buildAuthorizeUrl(spaceId))).build();
    }

    /**
     * Returns the Google consent URL as JSON so a SPA (which holds the JWT in memory,
     * not in navigations) can fetch it authenticated, then redirect the browser to it.
     */
    @GetMapping("/authorize-url")
    public java.util.Map<String, String> authorizeUrl(@RequestParam("spaceId") UUID spaceId) {
        return java.util.Map.of("url", buildAuthorizeUrl(spaceId));
    }

    private String buildAuthorizeUrl(UUID spaceId) {
        if (!props.configured()) {
            throw new IllegalStateException("Google OAuth is not configured (set google.oauth.*)");
        }
        authorization.requireOwner(spaceId, currentUser.requireUserId());
        String state = encryptionService.encrypt(spaceId + "|" + UUID.randomUUID());
        return oauthService.authorizationUrl(state);
    }

    /** Google redirects here after consent (public; identity comes from signed state). */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error) {
        if (error != null) {
            return html("Google Drive authorization was cancelled or failed: " + error);
        }
        UUID spaceId;
        try {
            spaceId = UUID.fromString(encryptionService.decrypt(state).split("\\|")[0]);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid state");
        }
        try {
            GoogleTokenResponse token = oauthService.exchange(code);
            if (token.getRefreshToken() == null) {
                return html("No refresh token returned. Remove Trove's access at "
                        + "https://myaccount.google.com/permissions and try connecting again.");
            }
            driveSyncService.storeConnection(spaceId, null, token.getRefreshToken());
            return html("Google Drive connected for this space. You can close this tab "
                    + "and trigger a sync from Trove.");
        } catch (Exception e) {
            return html("Failed to complete Google Drive connection: " + e.getMessage());
        }
    }

    /** Connection status for a space (any member). */
    @GetMapping("/status")
    public StatusResponse status(@RequestParam("spaceId") UUID spaceId) {
        authorization.requireCanRead(spaceId, currentUser.requireUserId());
        DriveConnection conn = driveSyncService.connection(spaceId);
        if (conn == null) {
            return new StatusResponse(false, null, null);
        }
        return new StatusResponse(true, conn.getConnectedAt(), conn.getLastSyncAt());
    }

    /** Trigger a sync now (owner only). */
    @PostMapping("/sync")
    public DriveSyncService.DriveSyncSummary sync(@RequestParam("spaceId") UUID spaceId) {
        authorization.requireOwner(spaceId, currentUser.requireUserId());
        return driveSyncService.sync(spaceId);
    }

    private ResponseEntity<String> html(String message) {
        String body = "<html><body style=\"font-family:sans-serif;padding:2rem\"><h3>Trove · Google Drive</h3><p>"
                + message + "</p></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    public record StatusResponse(boolean connected, Instant connectedAt, Instant lastSyncAt) {
    }
}
