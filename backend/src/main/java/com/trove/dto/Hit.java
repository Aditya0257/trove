package com.trove.dto;

import com.trove.integration.CloudflareEmbeddingProvider;
import com.trove.integration.EmbeddingProvider;
import com.trove.integration.StubEmbeddingProvider;
import com.trove.repository.CategoryRepository;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.repository.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

public record Hit(UUID documentId, double distance) {
}
