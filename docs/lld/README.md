# Low-Level Design

These documents go module by module through the backend, at the level of classes, tables,
events and flows. They complement the architecture documents: architecture explains the
"why" and the cross-cutting model; the LLD explains the concrete implementation of each
feature. Concepts are defined in [../architecture/00-concepts.md](../architecture/00-concepts.md);
endpoints are catalogued in [../api/reference.md](../api/reference.md); design rationale is in
[../../DECISIONS.md](../../DECISIONS.md).

Each document follows the same shape: purpose, key classes, endpoints, data touched, events,
configuration, notable flows, and edge cases.

| Document | Modules covered |
| --- | --- |
| [documents.md](documents.md) | `document`, `storage`, `extraction`, `category`, `merchant` - the capture-to-confirm core |
| [mail.md](mail.md) | `mail` - email documents grouped into threads, thread paging, add-form facets |
| [reminders.md](reminders.md) | `reminder`, `notification` - lifecycle, recurrence, auto-creation, dispatch |
| [spaces-and-access.md](spaces-and-access.md) | `space`, `auth`, `common/security` - spaces, membership, roles, joining |
| [search-and-chat.md](search-and-chat.md) | `search`, `chat` - NL search, embeddings, retrieval-augmented answering |
| [spend-and-anomaly.md](spend-and-anomaly.md) | `analytics`, `anomaly` - spend aggregation and higher-than-usual detection |
| [insights.md](insights.md) | `insights` - expiring-soon, recurring/subscription detection, related documents |
| [drive-and-mirror.md](drive-and-mirror.md) | `drive`, `backup`, `integrity` - the three-tier copies, export/import, DR, verification |
| [ingestion.md](ingestion.md) | `ingestion` - forward-to-file email and WhatsApp webhooks |
| [notice-system.md](notice-system.md) | `common/notice`, `common` exception handling - the cross-cutting feedback envelope |
