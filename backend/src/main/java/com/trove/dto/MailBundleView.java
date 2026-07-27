package com.trove.dto;

import com.trove.repository.DocumentRepository;
import com.trove.service.DocumentService;
import com.trove.dto.DocumentResponse;
import com.trove.security.SpaceAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MailBundleView(String bundleId, String account, String address, String topic,
                             String subject, String date, int count, List<DocumentResponse> docs) {
}
