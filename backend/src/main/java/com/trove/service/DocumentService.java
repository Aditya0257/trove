package com.trove.service;

import com.trove.dto.DownloadedFile;
import com.trove.dto.Paged;
import com.trove.entity.Document;
import com.trove.dto.ConfirmRequest;
import com.trove.dto.DocumentResponse;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.UUID;

/** Service contract for DocumentService. */
public interface DocumentService {
    DocumentResponse upload(UUID spaceId, UUID uploadedBy, MultipartFile file);
    DocumentResponse upload(UUID spaceId, UUID uploadedBy, MultipartFile file, boolean vital);
    DocumentResponse upload(UUID spaceId, UUID uploadedBy, MultipartFile file, boolean vital, boolean extract);
    DocumentResponse upload(UUID spaceId, UUID uploadedBy, MultipartFile file, boolean vital, boolean extract, boolean reuseExisting);
    Paged<DocumentResponse> listPaged(UUID spaceId, UUID userId, String categoryCode, int page, int size);
    List<DocumentResponse> listMailBundle(UUID spaceId, UUID userId, String bundleId);
    List<DocumentResponse> toResponses(List<Document> docs);
    DownloadedFile content(UUID documentId, UUID userId);
    List<DocumentResponse> listAnomalies(UUID spaceId, UUID userId);
    DocumentResponse get(UUID documentId, UUID userId);
    List<DocumentResponse> related(UUID documentId, UUID userId);
    void delete(UUID documentId, UUID userId);
    DocumentResponse reextract(UUID documentId, UUID userId);
    List<DocumentResponse> listTrash(UUID spaceId, UUID userId);
    void restore(UUID documentId, UUID userId);
    void purge(Document doc);
    void purgeNow(UUID documentId, UUID userId);
    int purgeExpired(int retentionDays);
    void purgeStorageForSpace(UUID spaceId);
    DocumentResponse confirm(UUID documentId, UUID reviewerId, ConfirmRequest req);
    List<DocumentResponse> present(List<Document> docs);
}
