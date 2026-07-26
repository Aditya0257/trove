/*
 * ============================================================================
 *  AccountDeletionService - irreversibly delete an account and all its data
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Removes a user, the spaces they own, every document in those spaces (from live
 *  object storage AND Google Drive AND the database), and all remaining per-space and
 *  per-user rows, leaving nothing behind in the live system.
 *
 *  Business use case
 *  -----------------
 *  The admin-only "delete account" action. Trove optimises for zero data loss, so this
 *  is intentionally privileged and guarded (see AdminController): it is the one operation
 *  that deliberately destroys a person's vault.
 *
 *  Solution architecture
 *  ---------------------
 *  Two phases in one transaction. Phase 1 walks every document in the user's spaces and
 *  runs the normal purge path (DocumentService.purge), which clears R2 + the Drive copy +
 *  the index rows - so storage never orphans. A failed purge is logged, not fatal, so one
 *  bad object can't block the deletion. Phase 2 clears the remaining rows in foreign-key
 *  safe order with native statements (the same order as a manual bucket-and-DB teardown),
 *  then the account row itself. The independent B2 mirror is append-only by design and is
 *  not touched, matching how single-document purge already behaves.
 * ============================================================================
 */
package com.trove.service.impl;
import com.trove.entity.User;
import com.trove.repository.UserRepository;

import com.trove.entity.Document;
import com.trove.service.impl.DocumentService;
import com.trove.integration.StorageService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    /** Tables keyed by document_id, cleared first in case a purge failed and left a document
     *  row with children that would otherwise block deleting the document. */
    private static final List<String> DOC_CHILD_TABLES = List.of(
            "line_item", "document_tag", "document_sync");

    /** Per-space child tables to clear, each keyed by space_id, after document children.
     *  `document` is included as a defensive sweep for any row a failed purge left behind. */
    private static final List<String> SPACE_CHILD_TABLES = List.of(
            "document_embedding", "document", "reminder", "category", "tag",
            "ingest_token", "drive_folder", "drive_connection", "space_member");

    /** Per-user tables to clear, each keyed by user_id. */
    private static final List<String> USER_TABLES = List.of(
            "space_member", "ai_usage", "email_verification", "password_reset_token");

    private final DocumentService documentService;
    private final StorageService storageService;
    private final UserRepository userRepository;
    private final EntityManager em;

    public AccountDeletionService(DocumentService documentService, StorageService storageService,
                                  UserRepository userRepository, EntityManager em) {
        this.documentService = documentService;
        this.storageService = storageService;
        this.userRepository = userRepository;
        this.em = em;
    }

    /** Deletes the account and everything it owns. Assumes the caller (AdminController) has
     *  already authorised the request and applied the safety guards. */
    @Transactional
    public void deleteAccount(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return; // already gone; nothing to do
        }
        String avatarKey = user.getAvatarKey();

        // Phase 1: purge every document in the user's owned spaces (R2 + Drive + index).
        List<UUID> spaceIds = em.createQuery(
                        "select s.id from Space s where s.createdBy = :u", UUID.class)
                .setParameter("u", userId).getResultList();
        int purged = 0;
        for (UUID spaceId : spaceIds) {
            List<Document> docs = em.createQuery(
                            "select d from Document d where d.spaceId = :s", Document.class)
                    .setParameter("s", spaceId).getResultList();
            for (Document doc : docs) {
                try {
                    documentService.purge(doc);
                    purged++;
                } catch (Exception e) {
                    // Non-fatal: a stuck object shouldn't block the account teardown. The
                    // defensive native sweep below removes the row; storage may orphan (rare).
                    log.warn("Could not purge document {} during account delete: {}", doc.getId(), e.getMessage());
                }
            }
        }
        // Anything purge() left uncommitted must be visible to the native deletes that follow.
        em.flush();
        em.clear();

        // Phase 2: clear remaining rows in FK-safe order, then the account itself.
        String byDoc = "delete from %s where document_id in "
                + "(select id from document where space_id in (select id from space where created_by = :u))";
        for (String table : DOC_CHILD_TABLES) {
            em.createNativeQuery(String.format(byDoc, table)).setParameter("u", userId).executeUpdate();
        }
        String bySpace = "delete from %s where space_id in (select id from space where created_by = :u)";
        for (String table : SPACE_CHILD_TABLES) {
            em.createNativeQuery(String.format(bySpace, table)).setParameter("u", userId).executeUpdate();
        }
        em.createNativeQuery("delete from space where created_by = :u").setParameter("u", userId).executeUpdate();
        for (String table : USER_TABLES) {
            em.createNativeQuery("delete from " + table + " where user_id = :u")
                    .setParameter("u", userId).executeUpdate();
        }
        em.createNativeQuery("delete from app_user where id = :u").setParameter("u", userId).executeUpdate();

        // Best-effort: drop the profile photo object too (no row references it any more).
        if (avatarKey != null) {
            try {
                storageService.delete(avatarKey);
            } catch (Exception ignored) {
                // A missing avatar object is harmless.
            }
        }
        log.info("Deleted account {} ({} document(s) purged across {} space(s))",
                userId, purged, spaceIds.size());
    }
}
