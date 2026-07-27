package com.trove.dto;

import com.trove.repository.DocumentSpecifications;
import com.trove.integration.LlmQueryParser;
import com.trove.dto.SearchQuery;
import com.trove.entity.Category;
import com.trove.service.CategoryService;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.service.DocumentService;
import com.trove.dto.DocumentResponse;
import com.trove.entity.Merchant;
import com.trove.repository.MerchantRepository;
import com.trove.security.SpaceAuthorization;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record SearchResult(SearchQuery interpreted, int count, List<DocumentResponse> results) {
}
