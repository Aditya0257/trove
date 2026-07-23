/*
 * ============================================================================
 *  DriveSyncService — connect a space's Drive and mirror its documents into it
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Stores a space's (encrypted) Drive refresh token, and syncs its documents into
 *  the owner's Drive under Trove/{categoryCode}/{yyyy-MM}/, idempotently.
 *
 *  Business use case
 *  -----------------
 *  Tier-3 of the backup story: a human-navigable copy in the owner's own Drive, so
 *  "if everything is down, open Drive and find the document" (CLAUDE.md). Free and
 *  permanent per owner.
 *
 *  Solution architecture
 *  ---------------------
 *  Refresh token encrypted at rest (EncryptionService). Folder ids are cached
 *  (drive_folder) so the tree isn't recreated; per-document sync state (document_sync)
 *  makes re-runs skip already-uploaded files. Logs a backup_run. Network I/O is kept
 *  out of a single big DB transaction (per-row repository saves) so a slow Drive call
 *  doesn't hold a DB connection.
 *
 *  Reasoning & logic
 *  -----------------
 *  drive.file scope means we can only see what we created — hence the local folder-id
 *  cache instead of listing Drive. Month bucket uses docDate when present, else the
 *  upload month, matching the object-store layout.
 * ============================================================================
 */
package com.trove.drive;

import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.trove.category.Category;
import com.trove.category.CategoryRepository;
import com.trove.common.error.NotFoundException;
import com.trove.common.security.EncryptionService;
import com.trove.backup.BackupKind;
import com.trove.backup.BackupRun;
import com.trove.backup.BackupRunService;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DriveSyncService {

    private static final Logger log = LoggerFactory.getLogger(DriveSyncService.class);
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    // Fraction of quota at which we treat a Drive as full and rotation rolls onward.
    private static final double FULL_AT = 0.98;

    private final DriveConnectionRepository connectionRepository;
    private final DriveFolderRepository folderRepository;
    private final DocumentSyncRepository documentSyncRepository;
    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final com.trove.space.SpaceRepository spaceRepository;
    private final GoogleDriveOAuthService oauthService;
    private final StorageService storageService;
    private final EncryptionService encryptionService;
    private final BackupRunService backupRunService;

    public DriveSyncService(DriveConnectionRepository connectionRepository,
                            DriveFolderRepository folderRepository,
                            DocumentSyncRepository documentSyncRepository,
                            DocumentRepository documentRepository,
                            CategoryRepository categoryRepository,
                            com.trove.space.SpaceRepository spaceRepository,
                            GoogleDriveOAuthService oauthService,
                            StorageService storageService,
                            EncryptionService encryptionService,
                            BackupRunService backupRunService) {
        this.connectionRepository = connectionRepository;
        this.folderRepository = folderRepository;
        this.documentSyncRepository = documentSyncRepository;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.spaceRepository = spaceRepository;
        this.oauthService = oauthService;
        this.storageService = storageService;
        this.encryptionService = encryptionService;
        this.backupRunService = backupRunService;
    }

    /**
     * Links a Google Drive to a space (pooling: a space may have several). Re-consenting
     * with the SAME account updates that connection in place rather than duplicating it.
     * The first Drive linked to a space becomes the active write target; later ones start
     * inactive (the owner promotes them, or rotation does when the active one fills).
     */
    @Transactional
    public void storeConnection(UUID spaceId, UUID userId, String refreshToken) {
        String enc = encryptionService.encrypt(refreshToken);
        GoogleDriveOAuthService.AccountInfo account =
                oauthService.accountInfo(oauthService.driveFor(refreshToken));

        // Dedupe on the Google account so re-consent doesn't create a second row for the
        // same Drive. If we couldn't read the email, treat it as a fresh connection.
        DriveConnection conn = account.email() == null ? null
                : connectionRepository.findBySpaceIdAndGoogleEmail(spaceId, account.email()).orElse(null);
        boolean isNew = conn == null;
        if (isNew) {
            conn = new DriveConnection(spaceId, enc, userId);
            conn.setActive(connectionRepository.countBySpaceId(spaceId) == 0);  // first Drive wins active
        } else {
            conn.setRefreshTokenEnc(enc);
            conn.setConnectedBy(userId);
        }
        applyAccountInfo(conn, account);
        conn.setStatus("active");
        connectionRepository.save(conn);
        log.info("Google Drive {} for space {} by user {} (account {})",
                isNew ? "linked" : "re-linked", spaceId, userId, conn.getGoogleEmail());
    }

    /** Copies identity + storage quota from a fresh about.get onto the connection. */
    private void applyAccountInfo(DriveConnection conn, GoogleDriveOAuthService.AccountInfo account) {
        conn.setGoogleEmail(account.email());
        conn.setGoogleAccountName(account.name());
        conn.setStorageLimitBytes(account.limitBytes());
        conn.setStorageUsageBytes(account.usageBytes());
        conn.setQuotaCheckedAt(Instant.now());
    }

    /** All Drives linked to a space, oldest first. */
    public List<DriveConnection> connections(UUID spaceId) {
        return connectionRepository.findBySpaceIdOrderByConnectedAtAsc(spaceId);
    }

    /** Bytes Trove has stored in one specific Drive (sum of its synced documents' sizes). */
    public long troveBytesForConnection(UUID connectionId) {
        return documentSyncRepository.troveBytesForConnection(connectionId);
    }

    public boolean isConnected(UUID spaceId) {
        return connectionRepository.countBySpaceId(spaceId) > 0;
    }

    /** The space's sync mode ('rotate' | 'mirror'); defaults to rotate. */
    public String mode(UUID spaceId) {
        return spaceRepository.findById(spaceId).map(com.trove.space.Space::getDriveSyncMode).orElse("rotate");
    }

    /** Sets the space's sync mode. Only 'rotate' or 'mirror' are accepted. */
    @Transactional
    public void setMode(UUID spaceId, String mode) {
        String m = "mirror".equalsIgnoreCase(mode) ? "mirror" : "rotate";
        spaceRepository.findById(spaceId).ifPresent(s -> s.setDriveSyncMode(m));
    }

    /** Owner promotes one Drive to the active write target (rotate mode). */
    @Transactional
    public void activate(UUID spaceId, UUID connectionId) {
        List<DriveConnection> conns = connectionRepository.findBySpaceIdOrderByConnectedAtAsc(spaceId);
        boolean found = conns.stream().anyMatch(c -> c.getId().equals(connectionId));
        if (!found) {
            throw new NotFoundException("Drive connection not found in this space");
        }
        conns.forEach(c -> c.setActive(c.getId().equals(connectionId)));
        connectionRepository.saveAll(conns);
    }

    /** Unlinks one Drive from a space. Its folder cache + per-doc sync rows cascade away,
     *  so if it's ever reconnected the documents re-sync. Files already in that Drive stay
     *  there (drive.file scope can't delete what the user may want to keep). */
    @Transactional
    public void disconnect(UUID spaceId, UUID connectionId) {
        DriveConnection conn = connectionRepository.findById(connectionId)
                .filter(c -> c.getSpaceId().equals(spaceId))
                .orElseThrow(() -> new NotFoundException("Drive connection not found in this space"));
        boolean wasActive = conn.isActive();
        connectionRepository.delete(conn);
        // Keep exactly one active Drive in rotate mode: if we removed the active one, promote
        // the oldest remaining connection so syncs still have a target.
        if (wasActive) {
            connectionRepository.findBySpaceIdOrderByConnectedAtAsc(spaceId).stream().findFirst()
                    .ifPresent(next -> { next.setActive(true); connectionRepository.save(next); });
        }
    }

    /** All spaces that have a connected Drive (for the scheduled sweep). */
    public List<UUID> connectedSpaceIds() {
        return connectionRepository.findDistinctSpaceIds();
    }

    /**
     * Syncs a space's documents into its connected Drive(s). Idempotent per Drive.
     * rotate mode writes to the active Drive (rolling to the next when it fills); mirror
     * mode writes every document into every Drive (redundant Tier-3 copies). Network I/O
     * stays out of one big transaction (per-row saves) so a slow Drive doesn't pin a DB
     * connection — a mid-run failure just leaves already-synced rows recorded.
     */
    public DriveSyncSummary sync(UUID spaceId) {
        List<DriveConnection> conns = connectionRepository.findBySpaceIdOrderByConnectedAtAsc(spaceId);
        if (conns.isEmpty()) {
            throw new NotFoundException("Google Drive not connected for this space");
        }
        String mode = mode(spaceId);
        List<DriveConnection> targets = "mirror".equals(mode) ? conns : List.of(rotateTarget(conns));

        Map<UUID, String> categoryCodes = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getCode));
        List<Document> docs = documentRepository.findBySpaceIdOrderByCreatedAtDesc(spaceId);

        BackupRun run = backupRunService.start(BackupKind.DRIVE_SYNC);
        int synced = 0;
        int skipped = 0;
        try {
            for (DriveConnection conn : targets) {
                DriveSyncSummary one = syncOne(conn, docs, categoryCodes);
                synced += one.synced();
                skipped += one.skipped();
            }
            backupRunService.success(run, "drive:space:" + spaceId,
                    "mode=" + mode + " targets=" + targets.size() + " synced=" + synced + " skipped=" + skipped);
            log.info("Drive sync for space {} — mode={} targets={} synced={} skipped={}",
                    spaceId, mode, targets.size(), synced, skipped);
            return new DriveSyncSummary(synced, skipped);
        } catch (Exception e) {
            backupRunService.fail(run, e.getMessage());
            throw new IllegalStateException("Drive sync failed: " + e.getMessage(), e);
        }
    }

    /** Syncs the space's documents into ONE Drive, skipping those already in it. */
    private DriveSyncSummary syncOne(DriveConnection conn, List<Document> docs, Map<UUID, String> categoryCodes)
            throws Exception {
        Drive drive = oauthService.driveFor(encryptionService.decrypt(conn.getRefreshTokenEnc()));
        String rootId = ensureRoot(drive, conn);
        int synced = 0;
        int skipped = 0;
        for (Document doc : docs) {
            if (documentSyncRepository.existsByDocumentIdAndConnectionId(doc.getId(), conn.getId())) {
                skipped++;
                continue;
            }
            String code = doc.getCategoryId() != null
                    ? categoryCodes.getOrDefault(doc.getCategoryId(), "uncategorized") : "uncategorized";
            LocalDate when = doc.getDocDate() != null
                    ? doc.getDocDate() : LocalDate.ofInstant(doc.getCreatedAt(), ZoneOffset.UTC);
            String month = when.format(MONTH);

            String categoryFolderId = ensureFolder(drive, conn, code, code, rootId);
            String monthFolderId = ensureFolder(drive, conn, code + "/" + month, month, categoryFolderId);

            byte[] bytes = storageService.get(doc.getStorageKey());
            String filename = doc.getOriginalFilename() != null
                    ? doc.getOriginalFilename() : basename(doc.getStorageKey());
            String fileId = uploadFile(drive, monthFolderId, filename, doc.getMimeType(), bytes);

            documentSyncRepository.save(new DocumentSync(doc.getId(), conn.getId(), fileId));
            synced++;
        }
        conn.setLastSyncAt(Instant.now());
        // Refresh the cached quota while we hold a live Drive client, then re-flag health so
        // rotation sees an up-to-date "full" the next time around.
        applyAccountInfo(conn, oauthService.accountInfo(drive));
        conn.setStatus(isFull(conn) ? "full" : "active");
        connectionRepository.save(conn);
        return new DriveSyncSummary(synced, skipped);
    }

    /** Rotate write target: the active Drive if it still has room, else the oldest Drive
     *  with free space (promoted to active). Keeps exactly one active connection. */
    private DriveConnection rotateTarget(List<DriveConnection> conns) {
        DriveConnection active = conns.stream().filter(DriveConnection::isActive).findFirst().orElse(null);
        if (active != null && !isFull(active)) {
            return active;
        }
        DriveConnection next = conns.stream().filter(c -> !isFull(c)).findFirst()
                .orElse(active != null ? active : conns.get(0));
        conns.forEach(c -> c.setActive(c.getId().equals(next.getId())));
        connectionRepository.saveAll(conns);
        log.info("Rotation: rolled active Drive to {} ({})", next.getId(), next.getGoogleEmail());
        return next;
    }

    /** A Drive is "full" once its account usage crosses FULL_AT of quota. Unknown/unlimited
     *  quota is never full. */
    boolean isFull(DriveConnection c) {
        Long limit = c.getStorageLimitBytes();
        Long usage = c.getStorageUsageBytes();
        if (limit == null || limit <= 0 || usage == null) {
            return false;
        }
        return usage >= limit * FULL_AT;
    }

    private String ensureRoot(Drive drive, DriveConnection conn) throws Exception {
        if (conn.getRootFolderId() != null && !conn.getRootFolderId().isBlank()) {
            return conn.getRootFolderId();
        }
        String id = createFolder(drive, "Trove", null);
        conn.setRootFolderId(id);
        connectionRepository.save(conn);
        return id;
    }

    private String ensureFolder(Drive drive, DriveConnection conn, String path, String name, String parentId)
            throws Exception {
        var cached = folderRepository.findByConnectionIdAndPath(conn.getId(), path);
        if (cached.isPresent()) {
            return cached.get().getFolderId();
        }
        String id = createFolder(drive, name, parentId);
        folderRepository.save(new DriveFolder(conn.getId(), conn.getSpaceId(), path, id));
        return id;
    }

    private String createFolder(Drive drive, String name, String parentId) throws Exception {
        File meta = new File().setName(name).setMimeType(FOLDER_MIME);
        if (parentId != null) {
            meta.setParents(List.of(parentId));
        }
        return drive.files().create(meta).setFields("id").execute().getId();
    }

    private String uploadFile(Drive drive, String folderId, String name, String mime, byte[] bytes)
            throws Exception {
        File meta = new File().setName(name).setParents(List.of(folderId));
        InputStreamContent content = new InputStreamContent(
                mime != null ? mime : "application/octet-stream", new ByteArrayInputStream(bytes));
        content.setLength(bytes.length);
        return drive.files().create(meta, content).setFields("id").execute().getId();
    }

    private String basename(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    // ── trash: reflect soft-delete / restore / purge into each Drive ─────────────
    private static final String DELETED_FOLDER = "_Deleted";

    /** Moves a document's file into Trove/_Deleted/ in every Drive it was synced to. */
    public void moveToDeletedFolder(UUID documentId) {
        forEachSyncedDrive(documentId, (drive, conn, fileId) -> {
            String rootId = ensureRoot(drive, conn);
            String deletedId = ensureFolder(drive, conn, DELETED_FOLDER, DELETED_FOLDER, rootId);
            reparent(drive, fileId, deletedId);
        });
    }

    /** Moves a restored document's file back to its category/month folder in each Drive. */
    public void moveOutOfDeletedFolder(UUID documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        Map<UUID, String> categoryCodes = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getCode));
        String code = doc.getCategoryId() != null
                ? categoryCodes.getOrDefault(doc.getCategoryId(), "uncategorized") : "uncategorized";
        LocalDate when = doc.getDocDate() != null
                ? doc.getDocDate() : LocalDate.ofInstant(doc.getCreatedAt(), ZoneOffset.UTC);
        String month = when.format(MONTH);
        forEachSyncedDrive(documentId, (drive, conn, fileId) -> {
            String rootId = ensureRoot(drive, conn);
            String categoryFolderId = ensureFolder(drive, conn, code, code, rootId);
            String monthFolderId = ensureFolder(drive, conn, code + "/" + month, month, categoryFolderId);
            reparent(drive, fileId, monthFolderId);
        });
    }

    /** Deletes a document's file from every Drive it was synced to (hard purge). */
    public void deleteFromDrives(UUID documentId) {
        forEachSyncedDrive(documentId, (drive, conn, fileId) -> drive.files().delete(fileId).execute());
    }

    /** Runs an action against the live Drive file for each connection this doc is synced to.
     *  Best-effort per Drive: one failure is logged and the rest still run. */
    private void forEachSyncedDrive(UUID documentId, DriveFileAction action) {
        for (DocumentSync s : documentSyncRepository.findByDocumentIdIn(List.of(documentId))) {
            try {
                DriveConnection conn = connectionRepository.findById(s.getConnectionId()).orElse(null);
                if (conn == null) {
                    continue;
                }
                Drive drive = oauthService.driveFor(encryptionService.decrypt(conn.getRefreshTokenEnc()));
                action.run(drive, conn, s.getExternalId());
            } catch (Exception e) {
                log.warn("Drive trash op failed for doc {} on connection {} — {}",
                        documentId, s.getConnectionId(), e.getMessage());
            }
        }
    }

    /** Reparents a Drive file: removes its current parents, adds the new one. */
    private void reparent(Drive drive, String fileId, String newParentId) throws Exception {
        File current = drive.files().get(fileId).setFields("parents").execute();
        String removeParents = current.getParents() == null ? null : String.join(",", current.getParents());
        drive.files().update(fileId, null).setAddParents(newParentId).setRemoveParents(removeParents).execute();
    }

    @FunctionalInterface
    private interface DriveFileAction {
        void run(Drive drive, DriveConnection conn, String fileId) throws Exception;
    }

    /** Summary of a sync run. */
    public record DriveSyncSummary(int synced, int skipped) {
    }
}
