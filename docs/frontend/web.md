# Frontend: The Web Client (Angular)

The web client is an Angular single-page application built with standalone components and
signal-based state. It is a thin, typed client over the REST API: the backend holds the logic,
the client renders it and handles interaction. This document maps its architecture and screens.

## 1. Architecture at a glance

- **Standalone components.** There is no root `NgModule`; each component declares its own imports.
  Feature screens are lazy-loaded by the router, so a screen's code is fetched only when visited.
- **Signals for state.** Component and shared state is held in Angular signals, with `computed`
  for derived values and `effect` for reactions (for example reloading when the current space
  changes). This keeps state explicit and change detection cheap.
- **Thin services over the API.** A single `ApiService` wraps HTTP; feature-specific services and
  components call it and map to typed models in `core/models/models.ts`.
- **Cross-cutting UI is centralised.** Authentication, the current-space context, notices,
  confirmation dialogs, settings and theme are shared services mounted once at the app root.

## 2. Core services and shared building blocks (`core/` + `shared/`)

The app is laid out as `core/` (app-wide singletons, imported once — split into
`guards/`, `interceptors/`, `services/`, `models/`, `config/`), `shared/`
(reusable pieces — `components/`, `directives/`, `pipes/`), and `features/` (one
folder per screen). Every component is a four-file unit: `name.ts` + `name.html`
+ `name.scss` (specs deferred). The table lists the files by name; their homes
follow that layout — for example `api.service.ts` lives in `core/services/`,
`auth.guard.ts` in `core/guards/`, `models.ts` in `core/models/`, the pipes in
`shared/pipes/`, and the reusable widgets in `shared/components/`.

| File | Role |
| --- | --- |
| `api.service.ts` | The typed HTTP surface: every endpoint the client calls, returning typed models. |
| `auth.service.ts` | Holds the JWT and current user in signals (persisted to localStorage); login, register, reset, TOTP, admin calls. Also holds account state (a signal loaded via `GET /api/account/me` after login) so the nav avatar and name stay in sync. |
| `auth.interceptor.ts` | Attaches the bearer token to every request; on 401 clears the session and routes to login. |
| `auth.guard.ts` | Route guard that redirects unauthenticated users to login. |
| `avatar.ts` | The round profile avatar in the top bar: the profile photo (presigned URL) or initials on a name-derived colour. |
| `auth-steps.ts` | The 3-step sign-up indicator ("Your details" / "Verify email" / "Admin approval"). |
| `space.context.ts` | The loaded spaces and the current space id (signals); screens read the id and reload on change. Loads with retry and a re-entrancy guard so a slow first load cannot wedge the switcher. |
| Notice System | The client Notice System, now spread across the layout: `shared/components/notice-toast.ts` and `dev-drawer.ts`, `core/interceptors/notice.interceptor.ts`, `core/services/notice.service.ts` and `dev-log.service.ts`, `core/models/notice.model.ts` — a toast, an interceptor that raises notices from API responses, and a developer drawer that retains recent notices, API calls, and the live AI-budget gauge. |
| `confirm.service.ts` / `confirm-dialog.ts` | An in-app confirmation dialog (replacing the browser's native confirm), with a busy state so the dialog stays up while the action runs. |
| `settings.service.ts` | Persisted user preferences, notably the "read images with AI" toggle. |
| `theme.service.ts` | Light and dark theme. |
| `models.ts` | The typed DTOs mirroring the backend responses. |
| `money.pipe.ts` / `datetime.pipe.ts` | Currency and date formatting. |
| `select.ts` (`TroveSelect`) / `password-input.ts` / `help-card.ts` / `info-tip.ts` | Reusable form and help widgets (a custom select, a password field with a show/hide toggle, an expandable help card with user and developer notes, and a hover info tip). |
| `terms.ts` | Vendor-neutral labels (for example "Drive backup") so provider names are a one-file change. |
| `currencies.ts` | The currency option list. |

## 3. Feature screens (`features/`)

| Area | Screens | Notes |
| --- | --- | --- |
| Auth | `login`, `register`, `verify`, `forgot`, `reset`, `account`, `admin` (approvals) | Login is 2FA-aware; register is a 3-step flow (details, verify email, admin approval) with a redesigned OTP `verify` screen. `/account` holds the profile photo (upload/remove, stored in R2, shown via presigned URL), display-name edit, email change (confirmed by an OTP to the new address), change password (re-checks the current one), TOTP two-factor, session info, and an admin-only delete-account section (choose an account, then type its email to confirm); the old `/security` page redirects here. |
| Documents | `documents/doc-list`, `documents/review`, `documents/upload` | List with a category filter, an anomaly marker and a reminders strip, paged server-side (page sizes 25/50/100 or All, total read from the `X-Total-Count` header) and excluding the `email` category; an admin also sees a calm blue approvals-pending strip linking to `/admin` when sign-ups await approval. Review-and-confirm with warranty and vital toggle. Upload accepts multiple files by paste, drop or picker (images and PDF, 25 MB), each stored as its own document, with the AI-reading toggle (images only); one file opens its review screen, several land back on the list. Trash is a `?view=trash` URL state. |
| Mail | `mail/mail`, `mail/mail-detail` | Emails filed as their own kind (category `email`), grouped into threads by a shared bundle id and paged server-side over threads; the list carries account/topic/address facets for add-form autocomplete. A thread is one or more screenshots plus email fields (account, address, topic, subject, date, notes); AI reading is off by default and only runs if ticked. Detail loads a single thread. |
| Insight | `spend`, `reminders`, `search`, `ask/assistant` | Spend charts; the reminders lifecycle (tabs, snooze/done/edit, edit dialog), where the "For document" picker loads its document list lazily only when opened; NL search; and the floating "Ask your vault" assistant with citations, help, and re-index. |
| Spaces | `spaces/spaces`, `spaces/join` | Space settings, members, invitations, join link, ingest address, Drive backup (pooling, rotate/mirror), and export. Join accepts a request-to-join link. |
| Resilience | `backups` | The backup-integrity dashboard: the three tiers, object-store stats, and read-more developer notes. |

## 4. Routing and app shell

Routes are declared in `app.routes.ts`, lazy-loading each feature and guarding authenticated
routes; the default route redirects to `documents`. The app shell (`app.ts` / `app.html`) hosts
the top navigation, the space switcher (shown once spaces load), the theme toggle, and mounts the
always-present pieces once: the notice toast, the developer drawer, the confirm dialog, and the
floating assistant launcher.

## 5. State and data-flow patterns

- **Space-driven reloads.** Screens read `spaceCtx.currentSpaceId()` inside an `effect`, so
  changing the current space reloads that screen's data. Before spaces load the id is undefined
  and calls omit `spaceId`, so the backend serves the personal space.
- **Notices, not raw errors.** The notice interceptor turns API responses into toasts and drawer
  entries, so no screen renders a raw error; success notices use the same channel.
- **Optimistic-but-safe actions.** Destructive actions (delete, purge, dismiss) go through the
  in-app confirm dialog, which stays busy until the action completes, so there is always feedback.

## 6. Configuration

The client's API base and a couple of tunables live in `core/config.ts` (with a runtime
`config.json` idea mirrored from the mobile `--dart-define`). The mobile client is documented for
running in [../../mobile/SETUP.md](../../mobile/SETUP.md); the two clients share the same API and
the same Notice rendering model.
