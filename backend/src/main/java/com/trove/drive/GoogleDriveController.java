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
    private final com.trove.auth.UserRepository userRepository;
    private final String webBaseUrl;

    public GoogleDriveController(GoogleDriveOAuthService oauthService, DriveSyncService driveSyncService,
                                GoogleOAuthProperties props, SpaceAuthorization authorization,
                                EncryptionService encryptionService, CurrentUser currentUser,
                                com.trove.auth.UserRepository userRepository,
                                @org.springframework.beans.factory.annotation.Value("${trove.web.base-url:http://localhost:4200}") String webBaseUrl) {
        this.oauthService = oauthService;
        this.driveSyncService = driveSyncService;
        this.props = props;
        this.authorization = authorization;
        this.encryptionService = encryptionService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.webBaseUrl = webBaseUrl;
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
        UUID userId = currentUser.requireUserId();
        authorization.requireOwner(spaceId, userId);
        // state carries who started the flow (so the public callback can record connected_by)
        // plus a random nonce; the whole thing is AES-GCM encrypted, so it's tamper-evident.
        String state = encryptionService.encrypt(spaceId + "|" + userId + "|" + UUID.randomUUID());
        return oauthService.authorizationUrl(state);
    }

    /** Google redirects here after consent (public; identity comes from signed state). */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error) {
        if (error != null) {
            return backToApp("error");
        }
        UUID spaceId;
        UUID connectedBy;
        try {
            String[] parts = encryptionService.decrypt(state).split("\\|");
            spaceId = UUID.fromString(parts[0]);
            // parts[1] is the initiating user (present since V15); older 2-part states have none.
            connectedBy = parts.length >= 3 ? UUID.fromString(parts[1]) : null;
        } catch (Exception e) {
            return backToApp("error");
        }
        try {
            GoogleTokenResponse token = oauthService.exchange(code);
            if (token.getRefreshToken() == null) {
                return backToApp("noRefresh");
            }
            driveSyncService.storeConnection(spaceId, connectedBy, token.getRefreshToken());
            return backToApp("connected");
        } catch (Exception e) {
            return backToApp("error");
        }
    }

    /** Connection status for a space (any member). */
    @GetMapping("/status")
    public StatusResponse status(@RequestParam("spaceId") UUID spaceId) {
        authorization.requireCanRead(spaceId, currentUser.requireUserId());
        DriveConnection conn = driveSyncService.connection(spaceId);
        if (conn == null) {
            return new StatusResponse(false, null, null, null, null, null, null, null, null, null);
        }
        // Resolve who linked it to a friendly name for the UI (null-safe: the connector
        // may be unknown on legacy connections made before we recorded connected_by).
        String connectedByName = conn.getConnectedBy() == null ? null
                : userRepository.findById(conn.getConnectedBy())
                        .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
                        .orElse(null);
        return new StatusResponse(true, conn.getConnectedAt(), conn.getLastSyncAt(),
                conn.getGoogleEmail(), conn.getGoogleAccountName(), connectedByName,
                conn.getStorageLimitBytes(), conn.getStorageUsageBytes(),
                driveSyncService.troveBytes(spaceId), conn.getQuotaCheckedAt());
    }

    /** Trigger a sync now (owner only). */
    @PostMapping("/sync")
    public DriveSyncService.DriveSyncSummary sync(@RequestParam("spaceId") UUID spaceId) {
        authorization.requireOwner(spaceId, currentUser.requireUserId());
        return driveSyncService.sync(spaceId);
    }

    /** Bounce the browser back into the app's Spaces page, carrying a status the UI turns
     *  into a toast — nicer than leaving the user on a bare backend page. */
    private ResponseEntity<String> backToApp(String status) {
        String target = webBaseUrl.replaceAll("/+$", "") + "/spaces?drive=" + status;
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    public record StatusResponse(boolean connected, Instant connectedAt, Instant lastSyncAt,
                                 String googleEmail, String googleAccountName, String connectedByName,
                                 Long storageLimitBytes, Long storageUsageBytes, Long troveBytes,
                                 Instant quotaCheckedAt) {
    }
}
