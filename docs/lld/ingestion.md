# LLD: Forward-to-file Ingestion

Module: `ingestion`. People already forward bills into email and chat groups, so Trove meets
that habit: forward a document to a per-space address and it files itself. Originating decision:
D16 (ingestion reuses the upload pipeline via a byte adapter), D20 (per-space unguessable ingest
addresses).

## 1. Key classes

| Class | Role |
| --- | --- |
| `EmailIngestController` | `POST /api/ingest/email` inbound-email webhook. |
| `WhatsAppWebhookController` | `POST /api/ingest/whatsapp` (and a `GET` verification handshake). |
| `IngestionService` | Validate the shared secret, resolve the space from the token, and run each attachment through the same upload path as a normal upload. |
| `IngestToken` / `IngestTokenService` / `IngestTokenRepository` | The per-space address secret: issue, resolve, rotate. |
| `IngestTokenController` | `GET`/rotate the address for a space (owner only). |
| `IngestProperties` | The shared webhook secret and ingest domain configuration. |

## 2. How an address routes to a space

Each space has an unguessable `ingest_token`. The address embeds that token (for example
`space-<token>@ingest.example`). When the mail provider delivers an inbound message to the
webhook, `IngestionService` matches the token to the space, then runs each attachment through the
identical `DocumentService` upload pipeline: stored with a sidecar, read by the AI, and left in
`needs_review` for confirmation. It reads the attachment, not the email body; the sender is kept
only for provenance. Treat the address like a password; if it leaks, the owner rotates it.

```mermaid
sequenceDiagram
    participant M as Mail provider
    participant WH as EmailIngestController
    participant Svc as IngestionService
    participant Up as DocumentService.upload
    M->>WH: POST /api/ingest/email (shared secret, to-address, attachments)
    WH->>Svc: ingest(payload)
    Svc->>Svc: verify shared secret; extract token from to-address
    Svc->>Svc: resolve space by ingest_token (else reject)
    loop each attachment (image/PDF)
      Svc->>Up: upload(spaceId, systemUser, bytes, extract=true)
    end
    WH-->>M: 200
```

## 3. Security

The webhooks are public routes (a mail provider cannot carry a JWT), so they are guarded two
ways: a shared webhook secret (`trove.ingest.secret`) that the provider must present, and the
per-space token that must resolve to a real space. Together these mean only your configured mail
provider can post, and a message only files into the space whose unguessable token it carries.

## 4. Deployment note

The address is reserved and functional in code, but real delivery requires routing the ingest
domain's inbound mail to the webhook, which is a deployment step. Until that is configured, the
address exists but does not receive mail. See [../DEPLOYMENT.md](../DEPLOYMENT.md).

## 5. Data and configuration

- Data: `ingest_token` (per space). See the [data model](../architecture/02-data-model.md).
- Configuration: `trove.ingest.secret`, the ingest domain. See
  [../operations/configuration.md](../operations/configuration.md).
