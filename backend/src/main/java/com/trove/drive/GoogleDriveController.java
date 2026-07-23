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
import com.trove.common.error.ForbiddenException;
import com.trove.common.error.NotFoundException;
import com.trove.common.security.CurrentUser;
import com.trove.common.security.EncryptionService;
import com.trove.space.SpaceAuthorization;
import com.trove.space.SpaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
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
        // Any writing member may link a Drive — pooling means members contribute their own
        // 15 GB, not just the owner. Viewers (read-only) still can't.
        authorization.requireCanWrite(spaceId, userId);
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

    /** Connection status for a space (any member): the sync mode + every linked Drive. */
    @GetMapping("/status")
    public StatusResponse status(@RequestParam("spaceId") UUID spaceId) {
        authorization.requireCanRead(spaceId, currentUser.requireUserId());
        List<DriveConnection> conns = driveSyncService.connections(spaceId);
        List<ConnectionView> views = conns.stream().map(c -> new ConnectionView(
                c.getId().toString(), c.getGoogleEmail(), c.getGoogleAccountName(),
                connectorName(c.getConnectedBy()), c.isActive(), c.getStatus(),
                c.getConnectedAt(), c.getLastSyncAt(),
                c.getStorageLimitBytes(), c.getStorageUsageBytes(),
                driveSyncService.troveBytesForConnection(c.getId()))).toList();
        return new StatusResponse(!conns.isEmpty(), driveSyncService.mode(spaceId), views);
    }

    /** Trigger a sync now (any writing member). */
    @PostMapping("/sync")
    public DriveSyncService.DriveSyncSummary sync(@RequestParam("spaceId") UUID spaceId) {
        authorization.requireCanWrite(spaceId, currentUser.requireUserId());
        return driveSyncService.sync(spaceId);
    }

    /** Owner switches the active write target (rotate mode). */
    @PostMapping("/connections/{connectionId}/activate")
    public void activate(@RequestParam("spaceId") UUID spaceId, @PathVariable("connectionId") UUID connectionId) {
        authorization.requireOwner(spaceId, currentUser.requireUserId());
        driveSyncService.activate(spaceId, connectionId);
    }

    /** Owner sets how the space spreads backups across its Drives: 'rotate' or 'mirror'. */
    @PutMapping("/mode")
    public java.util.Map<String, String> setMode(@RequestParam("spaceId") UUID spaceId,
                                                  @RequestParam("mode") String mode) {
        authorization.requireOwner(spaceId, currentUser.requireUserId());
        driveSyncService.setMode(spaceId, mode);
        return java.util.Map.of("mode", driveSyncService.mode(spaceId));
    }

    /** Unlinks a Drive. The owner may remove any; a member may remove only the one they linked. */
    @DeleteMapping("/connections/{connectionId}")
    public void disconnect(@RequestParam("spaceId") UUID spaceId, @PathVariable("connectionId") UUID connectionId) {
        UUID userId = currentUser.requireUserId();
        String role = authorization.requireMembership(spaceId, userId);
        DriveConnection conn = driveSyncService.connections(spaceId).stream()
                .filter(c -> c.getId().equals(connectionId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Drive connection not found in this space"));
        if (!SpaceRole.OWNER.equals(role) && !userId.equals(conn.getConnectedBy())) {
            throw new ForbiddenException("Only the owner or the member who linked it can remove this Drive");
        }
        driveSyncService.disconnect(spaceId, connectionId);
    }

    /** Resolves a connector user id to a friendly name (null-safe). */
    private String connectorName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
                .orElse(null);
    }

    /** Bounce the browser back into the app's Spaces page, carrying a status the UI turns
     *  into a toast — nicer than leaving the user on a bare backend page. */
    private ResponseEntity<String> backToApp(String status) {
        String target = webBaseUrl.replaceAll("/+$", "") + "/spaces?drive=" + status;
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    public record StatusResponse(boolean connected, String mode, List<ConnectionView> connections) {
    }

    /** One linked Drive, as the UI shows it. */
    public record ConnectionView(String id, String googleEmail, String googleAccountName, String connectedByName,
                                 boolean active, String status, Instant connectedAt, Instant lastSyncAt,
                                 Long storageLimitBytes, Long storageUsageBytes, Long troveBytes) {
    }
}
