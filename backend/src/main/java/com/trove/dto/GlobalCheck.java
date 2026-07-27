package com.trove.dto;

import com.trove.service.MirrorService;
import com.trove.repository.CategoryRepository;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.enums.DocumentStatus;
import com.trove.dto.IntegrityDtos.IntegrityReport;
import com.trove.dto.IntegrityDtos.Issue;
import com.trove.dto.IntegrityDtos.StorageIntegrity;
import com.trove.repository.MerchantRepository;
import com.trove.integration.StorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record GlobalCheck(int liveDocuments, int missingPrimary, int missingSidecar, long orphanObjects) {
}
