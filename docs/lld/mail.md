# LLD: Mail

Module: `mail`. Emails are filed as documents of their own category and grouped into threads,
so a forwarded or screenshotted email lands in the vault as a browsable conversation rather than
a loose bill. This builds directly on the document core in
[documents.md](documents.md); the conceptual framing is the same capture-to-confirm model.

## 1. Purpose

Let a user file an email as one or more screenshots plus email-specific metadata (account,
address, topic, subject, date, notes), keep the screenshots of one email together as a thread,
and browse the mailbox thread-by-thread without loading every email. An email is just a document
of category `email` with a shared `extra.mailBundleId` tying a thread together.

## 2. Key classes

| Class | Role |
| --- | --- |
| `MailController` | REST surface: the paged mailbox and its add-form facets. |
| `MailService` | Thread aggregation and mapping, reusing `DocumentService`'s document mapping so an email document maps exactly like any other. |
| `DocumentRepository` | Holds the aggregation queries: group documents by bundle id, fetch the documents for a page's bundles, and count threads. |

## 3. Endpoints

See [../api/reference.md](../api/reference.md) under Mail. In short: `GET /api/mail` (the paged
mailbox of threads, plus add-form facets) and `GET /api/documents/mail-bundle` (a single
thread). Filing an email reuses the document upload path with category `email` and a shared
`mailBundleId`.

## 4. Data touched

The `document` table (rows of category `email`) and the `extra` jsonb, whose `mailBundleId`
groups a thread and whose email fields (account, address, topic, subject, date, notes) carry the
metadata. No mail-specific table exists; a thread is purely the set of documents sharing a bundle
id. Column detail is in the [data model](../architecture/02-data-model.md).

## 5. Listing flow (thread paging)

The mailbox pages through **threads**, not individual documents, server-side:

1. One query returns the page's bundle ids, newest-first.
2. A second query fetches just those bundles' documents, for thumbnails and the thread summary.
3. A count query gives the total thread count, returned in the `X-Total-Count` header for the
   pager.

The same response also returns the distinct account, topic and address facets used by the
add-form autocomplete, so the client never has to load the whole mailbox just to build those
suggestions. The Mail detail view loads a single thread via `GET /api/documents/mail-bundle`
rather than fetching all emails.

## 6. Filing an email

An email is filed as one or more screenshots plus its metadata (account, address, topic,
subject, date, notes), stored as documents sharing a `mailBundleId`. AI reading is **off by
default** for mail and only runs if the user ticks it, unlike the general upload page where each
image is read when the "read images with AI" preference is on.

## 7. Edge cases

- An email is a document of its own category, filed and displayed in the Mail section rather than
  the generic bill form. The default document listing excludes the `email` category at the
  database level so emails do not appear twice (see [documents.md](documents.md)).
- A thread with a single screenshot is still a bundle of one; the same aggregation path serves it.
