package com.trove.dto;

import com.trove.entity.DocumentSync;
import com.trove.dto.DriveFolder;
import com.trove.repository.DocumentSyncRepository;
import com.trove.entity.DriveConnection;
import com.trove.repository.DriveConnectionRepository;
import com.trove.repository.DriveFolderRepository;
import com.trove.integration.GoogleDriveOAuthService;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.trove.entity.Category;
import com.trove.repository.CategoryRepository;
import com.trove.exception.NotFoundException;
import com.trove.security.EncryptionService;
import com.trove.enums.BackupKind;
import com.trove.entity.BackupRun;
import com.trove.service.BackupRunService;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.integration.StorageService;
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

public record DriveSyncSummary(int synced, int skipped) {
}
