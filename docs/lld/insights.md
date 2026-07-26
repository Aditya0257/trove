# LLD: Document Intelligence (Insights)

Read-only "document intelligence" over confirmed documents: what is expiring soon, what
recurs (subscriptions), and which documents are related. No new storage and no AI cost -
everything is computed live from data already captured (due dates, `extra.warrantyUntil`,
merchant, category, amount).

## 1. Key classes

- `InsightsController` (`/api/insights`) - `expiring` and `recurring` endpoints; resolves
  the space (defaults to the caller's personal space) like `AnalyticsController`.
- `InsightsService` - the logic; authorizes with `SpaceAuthorization.requireCanRead`, loads
  confirmed documents, and folds them in memory (per-space counts are small). Response
  records `ExpiringItem` and `RecurringGroup` are nested here.
- Related documents live on `DocumentController.related` → `DocumentService.related` (kept
  on the document path, `GET /api/documents/{id}/related`).

## 2. Endpoints

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/insights/expiring?spaceId=&withinDays=90` | `[ExpiringItem]` |
| GET | `/api/insights/recurring?spaceId=` | `[RecurringGroup]` |
| GET | `/api/documents/{id}/related` | `[DocumentResponse]` |

`ExpiringItem`: `{documentId, title, category, kind, date, daysLeft, amount, currency}` -
`kind` ∈ `due | renewal | warranty`; `daysLeft` is signed (negative = overdue).
`RecurringGroup`: `{merchant, category, categoryLabel, occurrences, cadence, averageAmount,
currency, lastSeen, nextExpected}` - `cadence` ∈ `weekly | monthly | quarterly | yearly`.

## 3. How it decides

**Expiring soon.** For each confirmed document (email category excluded), an item is emitted
for its `dueDate` and for `extra.warrantyUntil` when the date falls in
`[today - 30 days, today + withinDays]` (the 30-day tail keeps a just-lapsed ID visible).
`kind` is `renewal` for the `insurance`/`subscription` categories, else `due`; a warranty
date is `warranty`. Sorted soonest-first.

**Recurring.** Confirmed documents are grouped by `(merchantId, categoryId)`; a group of two
or more is tested for a regular cadence with the same tolerance bands the reminder detector
uses (weekly 5-9d, monthly 26-35d, quarterly 82-98d, yearly 350-380d): the average gap must
land in a band AND every individual gap must land in that same band. The predicted
`nextExpected` is `ReminderRecurrence.next(cadence, lastSeen, today)`.

**Related.** Other confirmed documents from the same `merchantId` (newest first), falling
back to the same `categoryId` when there is no merchant; the document itself is excluded and
the list is capped.

## 4. Relationship to Reminders

Reminders and Insights draw on the same due/warranty dates but play different roles:
Reminders is the stateful **action inbox** (it notifies, and holds Done/Snooze/Dismiss),
while Insights is the read-only **overview** of what is still outstanding plus the recurring
and related views Reminders does not offer. To avoid double-nagging, `expiring` **excludes
any document whose reminder is Done or Dismissed** (matched by `documentId` and a group that
folds `warranty_expiry` → warranty and `due`/`renewal` → date). Reminders auto-creation and
lifecycle are unchanged - see [reminders.md](reminders.md).

## 5. Edge cases

- Only **confirmed** documents contribute (verified dates/amounts).
- Missing/unparseable `warrantyUntil` is ignored; a document with neither a due date nor a
  warranty simply does not appear in expiring.
- Recurring needs a real merchant and doc date; groups with no consistent cadence are
  dropped (so one-off bills from a shop never masquerade as a subscription).
- All three are pure reads: nothing is written, so a stale result can never accumulate -
  each call recomputes from the current index.
