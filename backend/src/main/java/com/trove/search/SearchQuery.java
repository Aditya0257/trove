/*
 * ============================================================================
 *  SearchQuery — the structured filter set a search resolves to
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  A mutable bag of optional filters (category, text, merchant, date/amount ranges,
 *  status, latest-only, limit). Both the structured API and the natural-language
 *  parser produce one of these; the service turns it into a query.
 *
 *  Business use case
 *  -----------------
 *  It's the common contract that lets "my last water bill" and an explicit filtered
 *  request run through exactly the same search path — and it's returned to the client
 *  so a natural query's interpretation is transparent.
 *
 *  Design
 *  ------
 *  Null fields mean "no constraint". latestOnly + limit express "last"/"all".
 * ============================================================================
 */
package com.trove.search;

import java.math.BigDecimal;
import java.time.LocalDate;

public class SearchQuery {

    private String categoryCode;
    private String text;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal amountMin;
    private BigDecimal amountMax;
    private String status;
    private boolean latestOnly;
    private Integer limit;
    /** "amount" or "date" (default date). */
    private String sortBy;
    /** "asc" or "desc" (default desc). */
    private String sortDir;

    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }

    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }

    public BigDecimal getAmountMin() { return amountMin; }
    public void setAmountMin(BigDecimal amountMin) { this.amountMin = amountMin; }

    public BigDecimal getAmountMax() { return amountMax; }
    public void setAmountMax(BigDecimal amountMax) { this.amountMax = amountMax; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isLatestOnly() { return latestOnly; }
    public void setLatestOnly(boolean latestOnly) { this.latestOnly = latestOnly; }

    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }

    public String getSortBy() { return sortBy; }
    public void setSortBy(String sortBy) { this.sortBy = sortBy; }

    public String getSortDir() { return sortDir; }
    public void setSortDir(String sortDir) { this.sortDir = sortDir; }
}
