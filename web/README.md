# Trove — Web client (Angular)

The web UI for Trove. Angular 21 (standalone components, lazy routes). It talks to
the backend REST API documented in [`../docs/API.md`](../docs/API.md).

## What's implemented (first vertical)

The end-to-end spine, mirroring how the backend was built:

- **Auth** — register / login (JWT stored in `localStorage`, attached by an HTTP
  interceptor; a route guard protects the app).
- **Upload** — pick a file, optional **vital** toggle (encrypt at rest).
- **Review & confirm** — after upload, the review screen **polls** until async
  extraction fills in fields, shows the extractor + confidence (and an anomaly note),
  lets you edit category/merchant/amount/dates, and confirms.
- **Documents** — list by category, link into review, view the original file.

Later screens (spend dashboard, reminders, search box, spaces/members, Drive connect,
export) are not built yet — the API for all of them is in `../docs/API.md`.

## Run it

Prereqs: Node + the Angular CLI, and the **backend running on `http://localhost:8080`**
(see the root `README.md`).

```bash
cd web
npm install
ng serve         # http://localhost:4200
```

Register a new account (or use the seeded dev login `dev@trove.local` / `devpassword`),
then Upload → watch the review screen fill in → Confirm.

## Configuration

The API base URL is a single constant in
[`src/app/core/config.ts`](src/app/core/config.ts) (`API_BASE`). Point it at your
hosted HTTPS API for production. CORS on the backend already allows the dev origin.

## Structure

```
src/app/
├── core/        config, models, AuthService, ApiService, JWT interceptor, route guard
├── features/
│   ├── auth/        login, register
│   └── documents/   upload, doc-list, review
├── app.ts / app.html   shell + top nav
├── app.routes.ts       lazy routes (guarded)
└── app.config.ts       router + HttpClient + interceptor providers
```

To extend, add a feature folder + a lazy route in `app.routes.ts`, and a method on
`ApiService` for the endpoint (all endpoints are in `../docs/API.md`).
