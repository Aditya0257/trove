/*
 * ============================================================================
 *  Category — the filing taxonomy entity
 * ============================================================================
 *  Purpose:        maps the `category` table (DESIGN.md §2). A category is either
 *                  global (space_id null) or space-custom.
 *  Business use:    documents are filed and browsed by category; "list by category"
 *                  and spend-by-category both key off this.
 *  Design:         space_id is stored as a plain UUID column (not a JPA relation) to
 *                  keep the read path simple; the FK still exists in the DB.
 * ============================================================================
 */
package com.trove.entity;

import com.trove.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "category")
public class Category extends BaseEntity {

    /** Null = global/system category shared by all spaces. */
    @Column(name = "space_id")
    private UUID spaceId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "label", nullable = false)
    private String label;

    protected Category() {
        // for JPA
    }

    public UUID getSpaceId() {
        return spaceId;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
