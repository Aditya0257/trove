# Concepts and Terminology

This document explains the technical concepts and patterns Trove relies on, so the rest
of the documentation can use them without stopping to define them each time. Each entry
states what the concept is, how Trove uses it, the trade-off it carries, and where the
same idea appears in industry practice. Nothing here assumes you already know the term.

If a later document uses a word you are unsure of, it is defined here.

---

## Storage and data-durability concepts

### Object storage

Object storage keeps files as opaque "objects" addressed by a string key, in a flat
namespace, reached over an HTTP API rather than a filesystem. There are no real
directories; a key like `electricity/2026-01/reliance-jan.jpg` just contains slashes that
tools display as folders. Each object also carries a little metadata (content type, size,
custom headers).

Trove stores every image and PDF in object storage (Cloudflare R2 in production, MinIO in
local development). This is deliberate: object stores are built for cheap, durable,
effectively unlimited blob storage, and they are the wrong place to run queries, which is
exactly the division of labour Trove wants (files in object storage, queryable metadata in
Postgres).

Industry: Amazon S3 defined this model; Cloudflare R2, Backblaze B2, Google Cloud Storage
and Azure Blob Storage are the same idea. It underpins almost every photo, backup and data
lake product in production today.

### S3-compatible API and provider portability

"S3-compatible" means a storage provider exposes the same HTTP API and authentication
scheme that Amazon S3 does. Because the API is identical, the same client code works
against any of them by changing only the endpoint URL and credentials.

Trove uses the AWS S3 SDK and overrides the endpoint. The identical code therefore targets
MinIO on a developer laptop, Cloudflare R2 in production, and Backblaze B2 for the mirror.
This is why the storage class is named `S3StorageService` (for the protocol it speaks)
rather than `R2StorageService` (see decision D1). The benefit is that swapping providers is
a configuration change, not a rewrite; the trade-off is staying within the common subset of
the S3 API and not depending on one provider's proprietary extensions.

### The sidecar pattern

A "sidecar" is a small companion file stored next to a primary file, holding metadata
about it. Trove writes a JSON sidecar beside every document:

```
electricity/2026-01/reliance-jan.jpg     <- the document (source of truth)
electricity/2026-01/reliance-jan.json     <- the sidecar {category, merchant, date, amount, dueDate, rawText, owner, ...}
```

The point is that the object store becomes self-describing: given only the bucket, you can
reconstruct everything the application knew about each document, because the facts travel
with the file rather than living only in a database. If the database is lost, the sidecars
are enough to rebuild it.

Industry: the same pattern appears as `.xmp` sidecars beside RAW photos in Lightroom, as
`_metadata` and manifest files beside objects in data lakes, and as per-object JSON in
content-addressable stores. It is the standard way to keep a blob store independently
meaningful.

### Source of truth versus rebuildable index (read model)

A "source of truth" is the authoritative copy of data; everything else is derived and can
be regenerated. In Trove the source of truth is the set of files and their sidecars in
object storage. The PostgreSQL database is a rebuildable index: a fast, queryable
projection of those sidecars, holding metadata and extracted text but never the files
themselves. If the database is wiped, a rebuild job scans the bucket, reads each sidecar,
and reinserts the rows. Nothing authoritative is lost because nothing authoritative lived
only in the database.

This is the same separation as a search index that can always be rebuilt from the primary
datastore (for example an Elasticsearch index rebuilt from the system of record), and it is
closely related to the read-model idea in CQRS (Command Query Responsibility Segregation),
where a query-optimised projection is derived from an authoritative write model. The
trade-off is that the projection can lag or drift, so Trove has an integrity check that
compares the tiers and a rebuild path that re-derives the index on demand.

### Presigned URL

A presigned URL is a time-limited, cryptographically signed link that grants temporary
access to a single private object without making the bucket public and without the client
holding storage credentials. The server signs the URL with its own keys; the signature
encodes which object, which operation, and an expiry.

Trove serves non-sensitive document images to the browser as short-lived presigned GET
URLs, so the bucket stays private and links cannot be reused after they expire. Vital
(encrypted) documents do not use presigned URLs; they are streamed through the API and
decrypted in transit (see Encryption at rest).

Industry: presigned URLs are the standard way to let a browser download or upload directly
to and from S3-style storage without proxying the bytes through the application or exposing
long-lived keys.

### Idempotency

An operation is idempotent if running it more than once has the same effect as running it
once. This matters whenever an operation can be retried after a partial failure.

Trove's background copy jobs are idempotent by design. The Backblaze mirror computes the
set of object keys present in R2 but not in B2 and copies only those, so re-running it after
a crash never duplicates work or data. The Drive sync records, per document, that a copy
exists at a target (in `document_sync`), so it skips anything already synced. Reminder
auto-creation is guarded per document and per date, so re-confirming a document never
creates a duplicate reminder.

Industry: payment APIs require an idempotency key so a retried charge does not bill twice;
data-pipeline and sync systems lean on idempotency so that "at least once" delivery does not
corrupt state.

---

## Consistency and background work

### Transactions and "after commit" events

A database transaction groups changes so they all succeed or all fail together. A subtle
bug appears when you want to trigger side effects (send an email, enqueue a job, publish an
event) as part of a transaction: if you fire the side effect before the transaction
commits, and the commit then fails, you have acted on data that was rolled back.

Trove publishes domain events (for example "a document was confirmed") using Spring's
`@TransactionalEventListener` with the `AFTER_COMMIT` phase, meaning the listener runs only
once the database change has durably committed. Reminder auto-creation, embedding for
search, and the anomaly check all hang off the confirm event this way, so they never act on
a document that did not actually get confirmed.

Industry: this is the reasoning behind the transactional outbox pattern, where events are
written in the same transaction as the data and published only after commit, to avoid the
"dual write" problem of a database and a message system disagreeing.

### Eventual consistency

Two copies of data are "eventually consistent" if they are allowed to differ briefly and
are guaranteed to converge. Trove accepts eventual consistency between the hot store (R2,
written synchronously on upload) and the mirror and Drive copies (written by hourly jobs).
A document is safe the moment it is in R2 with its sidecar; the other copies catch up
shortly after. This is a deliberate trade of immediacy for simplicity and cost, appropriate
because the primary copy is already durable.

### Scheduled jobs and fixed-delay execution

Trove runs periodic work (reminder dispatch, the pg_dump backup, the B2 mirror, the Drive
sync, the 30-day trash purge, the embedding sweep, the integrity check) as in-process
scheduled methods on a fixed delay, inside the same jar. At this scale (about 100 users)
that is correct and far simpler than a separate scheduler or worker fleet. The trade-off is
that jobs run only while the instance is up and do not coordinate across multiple instances;
both are acceptable given a single stateless instance and idempotent jobs.

---

## Identity, access and cryptography

### Stateless authentication with JWT

A JSON Web Token (JWT) is a signed, self-contained token carrying claims (for example the
user id and email) in a compact, URL-safe form. "Stateless" means the server does not keep a
session record; it simply verifies the token's signature on each request and trusts the
claims inside. The client sends the token in the `Authorization: Bearer <token>` header.

Trove issues a JWT at login (signed with an HMAC-SHA256 secret) and validates it in a filter
on every request, exposing the caller through a `CurrentUser` object. The benefit is that any
instance can serve any request with no shared session store, which suits a single stateless
jar that may be redeployed at any time. The classic trade-off is revocation: because there is
no server session, a token is valid until it expires, so tokens are kept short-lived and the
signing secret can be rotated to invalidate all tokens at once.

Industry: bearer JWTs are the default for stateless APIs and single-page apps; the trade-offs
(revocation, token lifetime, secret rotation) are well understood and are handled the same way
here.

### BCrypt password hashing

Passwords are never stored, only their BCrypt hashes. BCrypt is an adaptive hashing function:
it is deliberately slow, includes a per-password random salt, and has a tunable cost factor so
it can be made slower as hardware improves. This makes offline brute-force attacks expensive
even if the hash table leaks.

Industry: BCrypt (with Argon2 and scrypt) is a standard recommended choice for password
storage precisely because ordinary fast hashes like SHA-256 are unsuitable for passwords.

### TOTP two-factor authentication

TOTP (Time-based One-Time Password, RFC 6238) generates a six-digit code that changes every
30 seconds from a secret shared once between the server and an authenticator app. Both sides
compute HMAC-SHA1 over the current time step and the shared secret; because they share the
secret and the clock, they derive the same code without any network call.

Trove implements TOTP so a user can protect sign-in with any standard authenticator app. It
is free, works offline, and avoids the cost and deliverability problems of SMS codes. The
shared secret is encrypted at rest. A small clock-drift tolerance (plus or minus one step) is
allowed so a slightly fast or slow phone still works.

### OAuth 2.0 and refresh tokens

OAuth 2.0 is the standard by which a user grants an application limited access to their
account on another service without sharing their password. The user consents on the provider's
screen; the application receives tokens: a short-lived access token for calls, and a long-lived
refresh token to obtain new access tokens without asking the user again.

Trove uses OAuth to back up a space into a member's Google Drive. It requests only the
`drive.file` scope, which grants access solely to files the application itself creates, not the
user's whole Drive. This is least privilege: even fully compromised, Trove could not read a
user's unrelated Drive files. The refresh token is encrypted at rest and used by the sync job
to mint access tokens as needed.

### Encryption at rest and AES-256-GCM

"Encryption at rest" means data is stored encrypted, so the stored bytes are useless without
the key. Trove encrypts vital documents (passport, ID, policies) before writing them to object
storage, using AES-256-GCM. GCM (Galois/Counter Mode) is authenticated encryption: it provides
both confidentiality and integrity, so tampering with the ciphertext is detected on decryption.
The encryption is a single seam at the storage layer, so the rest of the system does not need to
know which documents are encrypted; vital documents are served through a decrypt-stream path
rather than a public presigned URL. The same AES-GCM service also encrypts other secrets at rest
(TOTP secrets, Google refresh tokens).

Industry: AES-256-GCM is the widely used default for authenticated symmetric encryption; the
practice of a narrow encryption seam with everything else oblivious to it is standard envelope
design.

---

## AI, search and retrieval

### Vector embeddings and semantic search

An embedding is a fixed-length list of numbers (a vector) that represents the meaning of a
piece of text, produced by a model so that texts with similar meaning have vectors that are
close together. "Semantic search" ranks results by meaning rather than by exact keyword match,
by embedding the query and finding the stored vectors nearest to it.

Trove embeds a compact description of each document (category, merchant, date, amount, key
fields, and OCR text) into a 768-dimension vector and stores it in Postgres using the pgvector
extension. A question like "my last electricity bill" is embedded the same way and matched
against those vectors, so it finds the right document even when the wording differs from what is
printed on it.

### Cosine distance and the nearest-neighbour index

"Cosine distance" measures how far apart two vectors point, independent of their length; a
smaller distance means more similar meaning. pgvector exposes it through the `<=>` operator.
Because comparing a query against every stored vector is slow at scale, pgvector can build an
approximate-nearest-neighbour index (HNSW, Hierarchical Navigable Small World) that finds the
closest vectors quickly. Retrieval is always scoped to the caller's current space, so one user's
question never reaches another's documents.

Industry: embeddings, cosine similarity and HNSW indexes are the standard building blocks of
modern semantic search and recommendation systems.

### Retrieval-augmented generation (RAG)

RAG answers a question by first retrieving the most relevant source material, then asking a
language model to answer using only that material, with citations back to the sources. This
"grounds" the model in real data and is the main defence against hallucination (a model stating
something plausible but false).

Trove's "Ask your vault" retrieves the documents most relevant to a question by embedding
similarity, builds a context block from them, and instructs the model to answer only from that
context and to cite each fact by document number. If nothing relevant is found, or the model
cites nothing, it says so rather than inventing an answer. See
`architecture/05-ai-and-extraction.md` for the full design, including cost-aware model routing.

### The extraction provider chain and graceful degradation

Trove reads a document's fields with a vision model behind a swappable `ExtractionProvider`
interface, arranged as a fallback chain: the real provider first, then a safe stub. "Graceful
degradation" means that when a dependency fails, the system continues at reduced capability
rather than failing outright. If the vision model errors or the daily AI budget is spent, the
chain falls back to the stub and the document is still stored, just left for the user to fill in
by hand. An upload therefore never fails because the AI failed. See decision D9.

---

## Schema, data access and structure

### Database migrations (Flyway)

A database migration is a versioned, ordered change to the schema, applied automatically and
recorded so it runs exactly once. Trove uses Flyway, whose migrations (`V1` to `V23`) are the
single source of truth for the schema. Hibernate is configured with `ddl-auto: validate`, meaning
the ORM never changes the schema itself; on start-up it only checks that the schema matches the
mapped entities. Migrations are forward-only and additive, so deploying a new version never
requires editing an applied migration.

Industry: versioned migrations (Flyway, Liquibase, Rails migrations, and others) are the standard
way to evolve a schema safely across environments and deployments.

### Connection pooling

Opening a database connection is expensive, so applications keep a pool of open connections and
lend them to requests. Trove uses HikariCP (Spring Boot's default). This matters more with a
serverless Postgres such as Neon, where a small, well-behaved pool keeps within connection limits
and avoids the latency of constant reconnection.

### Package-by-feature

Code can be organised by layer (all controllers together, all services together) or by feature
(everything for "reminders" together, everything for "documents" together). Trove is organised
package-by-feature: each module owns its controller, service, repository and domain types, and
modules talk through interfaces and events. The benefit is that a feature is understandable and
changeable in one place, and cross-feature coupling is explicit; the trade-off is a little
duplication compared with sharing a single fat service layer.

### The Notice System

The Notice System is Trove's uniform feedback envelope. Every API response, success or failure,
can carry a notice with two channels: a calm, human-readable message for the user, and a developer
note with a stable code for diagnosis. A single global exception handler converts any thrown error
into a notice, so the client never renders a raw stack trace, and a developer can always see what
actually happened. The web and mobile clients both render notices the same way (a toast plus a
developer drawer). See decision D23 and `lld/notice-system.md`.
