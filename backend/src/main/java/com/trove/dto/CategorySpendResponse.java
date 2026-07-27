package com.trove.dto;

import com.trove.dto.CategorySpend;
import com.trove.dto.MonthlySpend;
import com.trove.repository.AnalyticsRepository;
import com.trove.security.SpaceAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CategorySpendResponse(String category, String label, BigDecimal total, long count) {
}
