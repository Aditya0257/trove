# Trove Documentation

This folder is the engineering reference for Trove: what it is, how it is built, and
why each decision was made. It is written to be read cover to cover by someone new to
the codebase, and to be kept accurate as the system evolves.

Trove is a private vault for the documents that matter: bills, receipts, policies,
warranties, IDs, tickets. You photograph or upload a document; Trove reads it, files it
by category, extracts the key fields, and layers spend tracking, anomaly alerts,
due-date and renewal reminders, and natural-language search on top. Any user has all of
this privately, and can also create shared spaces to keep common documents together.

The one idea everything else follows from:

> **The app is disposable. The data is not. Losing the entire app, database, and host
> must lose zero documents.**

## How this documentation is organised

Read in this order for a full understanding, or jump to what you need.

### Architecture (the big picture)

| Document | What it covers |
| --- | --- |
| [architecture/01-hld.md](architecture/01-hld.md) | High-Level Design: system context, the core principle and the three design rules it forces, the module map, runtime and deployment topology, the request lifecycle, and the non-functional requirements. Start here. |
| [architecture/02-data-model.md](architecture/02-data-model.md) | The complete data model: every table, column, key and relationship, an entity-relationship diagram, and the migration history (V1 to V23). |
| [architecture/03-security-and-access.md](architecture/03-security-and-access.md) | Identity, stateless JWT auth, two-factor (TOTP), password reset, admin approval, per-space access control and roles, and encryption at rest for vital documents. |
| [architecture/04-resilience-and-backup.md](architecture/04-resilience-and-backup.md) | The resilience model in full: sidecars, the three independent copies (R2, Backblaze B2, Google Drive), on-demand export and import, disaster recovery, and the backup-integrity checks. |
| [architecture/05-ai-and-extraction.md](architecture/05-ai-and-extraction.md) | The AI surface: the swappable extraction provider chain, the daily-budget guard, cost-aware model routing, retrieval-augmented "Ask your vault", embeddings, and anomaly detection. Includes how it all stays inside the free tier. |

### Low-Level Design (per feature)

The `lld/` documents go module by module: responsibilities, key classes, endpoints,
tables touched, events, configuration, and the notable flows. See
[lld/](lld/) once written; the module map in the HLD lists every feature package.

### Reference

| Document | What it covers |
| --- | --- |
| [api/reference.md](api/reference.md) | Every REST endpoint by controller: method, path, request, response, and access rule. |
| [frontend/web.md](frontend/web.md) | The Angular web client: standalone components, signal-based state, routing and guards, the shared services, and the feature-by-feature screen map. |
| [operations/configuration.md](operations/configuration.md) | Every environment variable and configuration property, grouped by concern, with dev and production values. |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Deploying the stateless jar to the Oracle Cloud host, with Neon, R2, B2 and Drive. |
| [API.md](API.md) | The original quick API sketch (superseded by `api/reference.md`; kept for history). |

### Source-of-truth design records (repository root)

These predate this folder and remain the canonical design narrative and decision log:

- [../DESIGN.md](../DESIGN.md): the original solution architecture and design (HLD + LLD).
- [../DECISIONS.md](../DECISIONS.md): the decision log, D1 to D23, each with context and
  consequences. Every non-obvious choice in the code traces back to a decision here.
- [../CLAUDE.md](../CLAUDE.md): the project brief (what we are building and why).
- [../README.md](../README.md): repository README (build and run).

## A two-week study plan

For someone learning the whole system, a sensible path:

1. **Days 1 to 2 - the shape of it.** Read the HLD and the data model. Run the stack
   locally (root README), sign in as the dev user, upload one receipt, and watch it move
   from `needs_review` to `confirmed`. Keep the data model open while you click around.
2. **Days 3 to 5 - the core vertical.** Follow one document end to end in the code:
   upload to storage and sidecar, the extraction provider chain, the confirm step, and
   soft delete. Read `lld/documents.md` and `architecture/05-ai-and-extraction.md`.
3. **Days 6 to 7 - identity and access.** Read `architecture/03-security-and-access.md`,
   then trace a request through the JWT filter and the per-space authorization check.
4. **Days 8 to 10 - the resilience story.** Read `architecture/04-resilience-and-backup.md`.
   Run an export, unzip it, and read the manifest. Understand how the database is rebuilt
   from the bucket if it is ever lost.
5. **Days 11 to 12 - the insight features.** Reminders (lifecycle, recurrence, warranty,
   subscription detection), spend and anomaly, and search plus "Ask your vault".
6. **Days 13 to 14 - the edges.** Spaces and sharing, Drive pooling and the B2 mirror,
   forward-to-file ingestion, and the Notice System that ties feedback together across
   API, web and mobile.

## Conventions used in this documentation

- Diagrams are Mermaid, rendered by GitHub and most Markdown viewers.
- Table and column names are the real database identifiers.
- Endpoint paths are the real routes. Access rules are stated per endpoint.
- Where a design choice is non-obvious, the relevant decision (Dn) is cited so the full
  rationale can be read in `DECISIONS.md`.
