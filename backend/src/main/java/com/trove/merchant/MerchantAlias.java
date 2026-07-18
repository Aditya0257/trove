/*
 * ============================================================================
 *  MerchantAlias — a raw name that maps to a canonical merchant
 * ============================================================================
 *  Purpose:        maps the `merchant_alias` table (DESIGN.md §2): every raw OCR
 *                  spelling we've seen, pointing at its canonical merchant.
 *  Business use:    normalization — "AMAZON PAY", "amzn", "Amazon.in" all resolve
 *                  to one Amazon merchant.
 *  Design:         merchant_id stored as a plain UUID column to keep writes simple.
 * ============================================================================
 */
package com.trove.merchant;

import com.trove.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "merchant_alias")
public class MerchantAlias extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "alias", nullable = false, unique = true)
    private String alias;

    protected MerchantAlias() {
        // for JPA
    }

    public MerchantAlias(UUID merchantId, String alias) {
        this.merchantId = merchantId;
        this.alias = alias;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getAlias() {
        return alias;
    }
}
