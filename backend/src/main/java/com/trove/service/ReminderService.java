package com.trove.service;

import com.trove.entity.Reminder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Service contract for ReminderService. */
public interface ReminderService {
    Reminder create(UUID spaceId, UUID userId, UUID documentId, String type, String title, LocalDate remindOn, String recurrence);
    Reminder snooze(UUID reminderId, UUID userId, int days);
    Reminder markDone(UUID reminderId, UUID userId);
    Reminder update(UUID reminderId, UUID userId, String type, String title, LocalDate remindOn, String recurrence, UUID documentId);
    void createRemindersFromDocument(UUID spaceId, UUID documentId, LocalDate dueDate);
    List<Reminder> list(UUID spaceId, UUID userId, String status);
    Map<UUID, String> documentFilenames(List<Reminder> reminders);
    Reminder dismiss(UUID reminderId, UUID userId);
    int dispatchDue(LocalDate today);
}
