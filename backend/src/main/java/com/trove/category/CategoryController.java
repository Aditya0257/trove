/*
 * ============================================================================
 *  CategoryController — read-only list of categories
 * ============================================================================
 *  Purpose:        expose the category taxonomy so a client (or curl) can see the
 *                  valid category codes to filter by or pick during confirm.
 *  Business use:    the reviewer needs to know which categories exist to re-file a
 *                  document correctly.
 *  Design:         Slice-1 convenience endpoint; returns global categories + any for
 *                  the given space. Full CRUD on categories is a later concern.
 * ============================================================================
 */
package com.trove.category;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryRepository repository;

    public CategoryController(CategoryRepository repository) {
        this.repository = repository;
    }

    /** Returns global categories plus (if given) the space's custom categories. */
    @GetMapping
    public List<CategoryView> list(@RequestParam(value = "spaceId", required = false) UUID spaceId) {
        return repository.findAll().stream()
                .filter(c -> c.getSpaceId() == null || c.getSpaceId().equals(spaceId))
                .map(c -> new CategoryView(c.getCode(), c.getLabel(), c.getSpaceId() == null))
                .toList();
    }

    /** Compact API view of a category. */
    public record CategoryView(String code, String label, boolean global) {
    }
}
