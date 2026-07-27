package com.trove.dto;

import com.trove.entity.Category;
import com.trove.repository.CategoryRepository;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.enums.DocumentStatus;
import com.trove.entity.Merchant;
import com.trove.repository.MerchantRepository;
import com.trove.entity.Reminder;
import com.trove.enums.ReminderRecurrence;
import com.trove.repository.ReminderRepository;
import com.trove.enums.ReminderStatus;
import com.trove.enums.ReminderType;
import com.trove.security.SpaceAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ExpiringItem(String documentId, String title, String category, String kind,
                           LocalDate date, long daysLeft, BigDecimal amount, String currency) {
}
