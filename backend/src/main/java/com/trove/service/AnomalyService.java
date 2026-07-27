package com.trove.service;

import com.trove.dto.AnomalyResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Service contract for AnomalyService. */
public interface AnomalyService {
    AnomalyResult evaluate(UUID spaceId, UUID categoryId, BigDecimal amount, UUID excludeDocId, LocalDate asOf);
}
