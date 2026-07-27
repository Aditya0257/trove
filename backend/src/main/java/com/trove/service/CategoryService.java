package com.trove.service;

import com.trove.entity.Category;
import java.util.Optional;
import java.util.UUID;

/** Service contract for CategoryService. */
public interface CategoryService {
    Category resolve(UUID spaceId, String code);
    Optional<Category> find(UUID spaceId, String code);
}
