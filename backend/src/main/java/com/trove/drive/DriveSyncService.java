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
    private static final String TARGET = "google_drive";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DriveConnectionRepository connectionRepository;
    private final DriveFolderRepository folderRepository;
    private final DocumentSyncRepository documentSyncRepository;
    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final GoogleDriveOAuthService oauthService;
    private final StorageService storageService;
    private final EncryptionService encryptionService;
    private final BackupRunService backupRunService;

    public DriveSyncService(DriveConnectionRepository connectionRepository,
                            DriveFolderRepository folderRepository,
                            DocumentSyncRepository documentSyncRepository,
                            DocumentRepository documentRepository,
                            CategoryRepository categoryRepository,
                            GoogleDriveOAuthService oauthService,
                            StorageService storageService,
                            EncryptionService encryptionService,
                            BackupRunService backupRunService) {
        this.connectionRepository = connectionRepository;
        this.folderRepository = folderRepository;
        this.documentSyncRepository = documentSyncRepository;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.oauthService = oauthService;
        this.storageService = storageService;
        this.encryptionService = encryptionService;
        this.backupRunService = backupRunService;
    }

    /** Stores (or updates) the encrypted refresh token for a space, and records which
     *  Google account it points at (read from Drive under the existing drive.file scope). */
    @Transactional
    public void storeConnection(UUID spaceId, UUID userId, String refreshToken) {
        String enc = encryptionService.encrypt(refreshToken);
        DriveConnection conn = connectionRepository.findBySpaceId(spaceId).orElse(null);
        if (conn == null) {
            conn = new DriveConnection(spaceId, enc, userId);
        } else {
            conn.setRefreshTokenEnc(enc);
            conn.setConnectedBy(userId);
        }
        // Ask Drive which account this token belongs to so the UI can show "connected as
        // <email>". Best-effort: a failure here must not block linking the Drive.
        GoogleDriveOAuthService.AccountInfo account = oauthService.accountInfo(oauthService.driveFor(refreshToken));
        conn.setGoogleEmail(account.email());
        conn.setGoogleAccountName(account.name());
        connectionRepository.save(conn);
        log.info("Google Drive connected for space {} by user {} (account {})", spaceId, userId, account.email());
    }

    public boolean isConnected(UUID spaceId) {
        return connectionRepository.findBySpaceId(spaceId).isPresent();
    }

    public DriveConnection connection(UUID spaceId) {
        return connectionRepository.findBySpaceId(spaceId).orElse(null);
    }

    /** All spaces that have a connected Drive (for the scheduled sweep). */
    public List<UUID> connectedSpaceIds() {
        return connectionRepository.findAll().stream().map(DriveConnection::getSpaceId).toList();
    }

    /** Syncs a space's documents into the owner's Drive. Idempotent. */
    public DriveSyncSummary sync(UUID spaceId) {
        DriveConnection conn = connectionRepository.findBySpaceId(spaceId)
                .orElseThrow(() -> new NotFoundException("Google Drive not connected for this space"));
        Drive drive = oauthService.driveFor(encryptionService.decrypt(conn.getRefreshTokenEnc()));
        BackupRun run = backupRunService.start(BackupKind.DRIVE_SYNC);
        int synced = 0;
        int skipped = 0;
        try {
            String rootId = ensureRoot(drive, conn);
            Map<UUID, String> categoryCodes = categoryRepository.findAll().stream()
                    .collect(Collectors.toMap(Category::getId, Category::getCode));

            for (Document doc : documentRepository.findBySpaceIdOrderByCreatedAtDesc(spaceId)) {
                if (documentSyncRepository.existsByDocumentIdAndTarget(doc.getId(), TARGET)) {
                    skipped++;
                    continue;
                }
                String code = doc.getCategoryId() != null
                        ? categoryCodes.getOrDefault(doc.getCategoryId(), "uncategorized") : "uncategorized";
                LocalDate when = doc.getDocDate() != null
                        ? doc.getDocDate() : LocalDate.ofInstant(doc.getCreatedAt(), ZoneOffset.UTC);
                String month = when.format(MONTH);

                String categoryFolderId = ensureFolder(drive, spaceId, code, code, rootId);
                String monthFolderId = ensureFolder(drive, spaceId, code + "/" + month, month, categoryFolderId);

                byte[] bytes = storageService.get(doc.getStorageKey());
                String filename = doc.getOriginalFilename() != null
                        ? doc.getOriginalFilename() : basename(doc.getStorageKey());
                String fileId = uploadFile(drive, monthFolderId, filename, doc.getMimeType(), bytes);

                documentSyncRepository.save(new DocumentSync(doc.getId(), TARGET, fileId));
                synced++;
            }

            conn.setLastSyncAt(Instant.now());
            connectionRepository.save(conn);
            backupRunService.success(run, "drive:space:" + spaceId, "synced=" + synced + " skipped=" + skipped);
            log.info("Drive sync for space {} — synced={} skipped={}", spaceId, synced, skipped);
            return new DriveSyncSummary(synced, skipped);
        } catch (Exception e) {
            backupRunService.fail(run, e.getMessage());
            throw new IllegalStateException("Drive sync failed: " + e.getMessage(), e);
        }
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

    private String ensureFolder(Drive drive, UUID spaceId, String path, String name, String parentId)
            throws Exception {
        var cached = folderRepository.findBySpaceIdAndPath(spaceId, path);
        if (cached.isPresent()) {
            return cached.get().getFolderId();
        }
        String id = createFolder(drive, name, parentId);
        folderRepository.save(new DriveFolder(spaceId, path, id));
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

    /** Summary of a sync run. */
    public record DriveSyncSummary(int synced, int skipped) {
    }
}
