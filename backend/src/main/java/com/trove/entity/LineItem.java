/*
 * ============================================================================
 *  LineItem — one itemized row of a document
 * ============================================================================
 *  Purpose:        maps the `line_item` table (DESIGN.md §2): itemized detail for a
 *                  document (e.g. each product on a receipt).
 *  Business use:    enables itemized review and later per-item analytics/search.
 *  Design:         document_id stored as a plain UUID column. unit_price is nullable
 *                  because the stub/DTO (DESIGN §6.2) only provides qty + amount.
 * ============================================================================
 */
package com.trove.entity;

import com.trove.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "line_item")
public class LineItem extends BaseEntity {

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "description")
    private String description;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "amount")
    private BigDecimal amount;

    protected LineItem() {
        // for JPA
    }

    public LineItem(UUID documentId, String description, BigDecimal quantity,
                    BigDecimal unitPrice, BigDecimal amount) {
        this.documentId = documentId;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.amount = amount;
    }

    public UUID getDocumentId() { return documentId; }
    public String getDescription() { return description; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getAmount() { return amount; }
}
