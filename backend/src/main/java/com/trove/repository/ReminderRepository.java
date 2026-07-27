/*
 * ============================================================================
 *  ReminderRepository — data access for reminders
 * ============================================================================
 *  Purpose:        list a space's reminders, find due ones for the scheduler, and
 *                  prevent duplicate auto-reminders per document/type.
 * ============================================================================
 */
package com.trove.repository;
import com.trove.entity.Reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    List<Reminder> findBySpaceIdOrderByRemindOnAsc(UUID spaceId);

    List<Reminder> findBySpaceIdAndStatusOrderByRemindOnAsc(UUID spaceId, String status);

    /** Due reminders the scheduler should dispatch (across all spaces). */
    List<Reminder> findByStatusAndRemindOnLessThanEqual(String status, LocalDate onOrBefore);

    /** Guards against creating the same auto-reminder twice for a document. */
    boolean existsByDocumentIdAndType(UUID documentId, String type);

    /** Per-date guard: lets one document have several reminders (7/1/0 days) without
     *  duplicating any single lead when a document is re-confirmed. */
    boolean existsByDocumentIdAndTypeAndRemindOn(UUID documentId, String type, LocalDate remindOn);
}
