/*
 * ============================================================================
 *  BaseEntity — shared JPA identity base for application-assigned UUIDs
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  A MappedSuperclass that gives every entity a UUID primary key that is assigned
 *  in the application (not by the database), while still letting Spring Data JPA
 *  correctly distinguish a brand-new row from a loaded one.
 *
 *  Business use case
 *  -----------------
 *  Trove must write a file's sidecar JSON keyed by the document's UUID *before*
 *  the row is even a certainty. Application-assigned IDs let us know the ID up
 *  front, so the sidecar and DB row always agree on identity.
 *
 *  Solution architecture
 *  ---------------------
 *  The schema (DESIGN.md §2) declares `id uuid primary key default gen_random_uuid()`.
 *  We instead generate the UUID in Java. Postgres' default is only a fallback for
 *  rows inserted outside the app (e.g. manual SQL) — the app always supplies its own.
 *
 *  Design
 *  ------
 *  Implements Persistable<UUID>: `isNew()` returns true only until the entity is
 *  first persisted or loaded. Without this, Spring Data would treat an assigned-ID
 *  entity as "detached" and issue a SELECT-then-UPDATE (a merge) on every save,
 *  which is both wrong (no row yet) and slow. With it, save() does a clean INSERT.
 *
 *  Reasoning & logic
 *  -----------------
 *  equals/hashCode are based on the immutable id so entities behave correctly in
 *  collections across the persistence lifecycle.
 * ============================================================================
 */
package com.trove.common;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.Objects;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    /** True until the row has been persisted or loaded; drives INSERT vs UPDATE. */
    @Transient
    private boolean isNew = true;

    /** New instances get an application-assigned UUID immediately. */
    protected BaseEntity() {
        this.id = UUID.randomUUID();
    }

    /** Allows callers/tests to pin a specific id (e.g. seeded/known identities). */
    protected BaseEntity(UUID id) {
        this.id = id;
    }

    @Override
    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Once Hibernate has loaded or inserted the row, it is no longer "new". */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
