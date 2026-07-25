/*
 * ============================================================================
 *  MailService - paginated view of filed emails, grouped into threads
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Lists filed emails as threads (bundles) one page at a time, newest thread first,
 *  plus the distinct account / topic / address values for the add-email autocomplete.
 *
 *  Business use case
 *  -----------------
 *  The Mail page. Emails are stored as documents of category 'email', with a shared
 *  extra.mailBundleId grouping the screenshots of one email thread. This lets the page
 *  page through threads instead of pulling every email into the browser to group there.
 *
 *  Solution architecture
 *  ---------------------
 *  The database does the grouping: one query returns the page's bundle ids (grouped and
 *  ordered by newest), a second fetches just those bundles' documents (for thumbnails and
 *  the summary), and a count query gives the pager total. Document mapping is reused from
 *  DocumentService so a thread's screenshots carry the same presigned URLs as anywhere else.
 *  This keeps the payload and the stateless app server's work proportional to one page,
 *  not to the whole mailbox.
 * ============================================================================
 */
package com.trove.mail;

import com.trove.document.DocumentRepository;
import com.trove.document.DocumentService;
import com.trove.document.dto.DocumentResponse;
import com.trove.space.SpaceAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MailService {

    /** Cap for the unpaged ("show all") request, so it can never fetch an unbounded set. */
    private static final int MAX_ALL = 500;

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final SpaceAuthorization authorization;

    public MailService(DocumentRepository documentRepository, DocumentService documentService,
                       SpaceAuthorization authorization) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.authorization = authorization;
    }

    /** One page of email threads for the space, with the autocomplete facets and the total
     *  thread count (for the pager). {@code size <= 0} means "all" (bounded by MAX_ALL). */
    @Transactional(readOnly = true)
    public MailPage bundles(UUID spaceId, UUID userId, int page, int size) {
        authorization.requireCanRead(spaceId, userId);
        int limit = size <= 0 ? MAX_ALL : size;
        int offset = size <= 0 ? 0 : Math.max(0, page) * size;

        List<String> ids = documentRepository.findMailBundleIds(spaceId, limit, offset);
        List<MailBundleView> bundles = new ArrayList<>();
        if (!ids.isEmpty()) {
            // Fetch the page's documents and group them by bundle, preserving the id order
            // (which is already newest-thread-first from the aggregation query).
            List<DocumentResponse> docs = documentService.toResponses(
                    documentRepository.findEmailDocsInBundles(spaceId, ids));
            Map<String, List<DocumentResponse>> byBundle = new LinkedHashMap<>();
            for (String id : ids) {
                byBundle.put(id, new ArrayList<>());
            }
            for (DocumentResponse d : docs) {
                byBundle.computeIfAbsent(bundleIdOf(d), k -> new ArrayList<>()).add(d);
            }
            for (String id : ids) {
                List<DocumentResponse> threadDocs = byBundle.get(id);
                if (threadDocs == null || threadDocs.isEmpty()) {
                    continue;
                }
                // Docs are oldest-first, so the last one carries the latest metadata to show.
                DocumentResponse latest = threadDocs.get(threadDocs.size() - 1);
                bundles.add(new MailBundleView(id, str(latest, "mailAccount"), str(latest, "mailAddress"),
                        str(latest, "mailTopic"), str(latest, "mailSubject"), mailDate(latest),
                        threadDocs.size(), threadDocs));
            }
        }
        long total = documentRepository.countMailBundles(spaceId);
        return new MailPage(bundles, total,
                documentRepository.findMailAccounts(spaceId),
                documentRepository.findMailTopics(spaceId),
                documentRepository.findMailAddresses(spaceId));
    }

    /** The bundle id of a document: its shared mailBundleId, or its own id for a singleton. */
    private static String bundleIdOf(DocumentResponse d) {
        String bundle = str(d, "mailBundleId");
        return bundle.isEmpty() ? d.id().toString() : bundle;
    }

    /** A string field out of a document's extra map (empty string when absent). */
    private static String str(DocumentResponse d, String key) {
        Object v = d.extra() == null ? null : d.extra().get(key);
        return v == null ? "" : v.toString();
    }

    /** The mail date shown for a thread: the stored mailDate, else the document date. */
    private static String mailDate(DocumentResponse d) {
        String mailDate = str(d, "mailDate");
        return !mailDate.isEmpty() ? mailDate : (d.docDate() == null ? "" : d.docDate().toString());
    }

    /** One email thread: the latest metadata plus every screenshot in it (oldest first). */
    public record MailBundleView(String bundleId, String account, String address, String topic,
                                 String subject, String date, int count, List<DocumentResponse> docs) {
    }

    /** A page of threads plus the autocomplete facets and the total thread count. */
    public record MailPage(List<MailBundleView> bundles, long total,
                           List<String> accounts, List<String> topics, List<String> addresses) {
    }
}
