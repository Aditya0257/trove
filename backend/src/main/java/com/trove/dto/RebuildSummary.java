package com.trove.dto;

import com.trove.enums.BackupKind;
import com.trove.entity.BackupRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.entity.Category;
import com.trove.service.CategoryService;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.entity.Merchant;
import com.trove.service.MerchantService;
import com.trove.dto.DocumentSidecar;
import com.trove.integration.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;

public record RebuildSummary(int scanned, int rebuilt, int skipped, int failed) {
}
