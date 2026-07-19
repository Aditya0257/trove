/**
 * Bootstrap. First loads /config.json (so the API base URL is set at RUNTIME, not
 * baked into the build), stores it on globalThis, THEN dynamically imports the app so
 * core/config.ts reads the configured value. Falls back to localhost for dev.
 */
async function boot(): Promise<void> {
  let apiBase = 'http://localhost:8080';
  try {
    const res = await fetch('config.json', { cache: 'no-store' });
    if (res.ok) {
      const cfg = await res.json();
      if (cfg?.apiBase) {
        apiBase = cfg.apiBase;
      }
    }
  } catch {
    // no config.json (or dev) → keep the localhost default
  }
  (globalThis as Record<string, unknown>)['__TROVE_API_BASE'] = apiBase;

  const [{ bootstrapApplication }, { appConfig }, { App }] = await Promise.all([
    import('@angular/platform-browser'),
    import('./app/app.config'),
    import('./app/app'),
  ]);
  bootstrapApplication(App, appConfig).catch((err) => console.error(err));
}

void boot();
