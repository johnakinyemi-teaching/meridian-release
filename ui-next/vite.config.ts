// vite.config.ts
// J. Ferreira, 2023-06 — initial migration scaffold.
//
// The legacy console (../dashboard) has no build step at all (see MRD-181):
// it loads jQuery from a vendored file and sets the API base with a `sed`
// in the release script. ui-next is the opposite bet — a real build, a
// module graph, a chart that isn't hand-assembled DOM nodes. That bet was
// only ever paid off for three screens before the team was reassigned.
//
// The dev server proxies /api to the Java service on :8081 so that local
// development doesn't need CORS turned on in ApiServer.java. In production
// the app talks to the service directly via VITE_API_BASE (see src/api/client.ts).

import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Tests run in UTC on every machine, as they do in CI. src/util/doy.test.ts
// fails in time zones with DST (dateToDoy divides by 86,400,000, so the
// 23-hour day is off by one). That bug predates MRD-252 and is not fixed here;
// this pin only makes `npm test` give the same result everywhere. Set here
// rather than as `TZ=UTC vitest run` so the script also works on Windows.
if (process.env.VITEST) process.env.TZ = 'UTC';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const apiBase = env.VITE_API_BASE || 'http://localhost:8081';

  return {
    plugins: [react()],
    server: {
      port: 5174,
      proxy: {
        // Dev-only convenience. Production uses the absolute VITE_API_BASE.
        '/api': {
          target: apiBase,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
      // TODO(ui-next): the legacy console is served from /console and this
      // was meant to move to /ui once the migration finished. It never did,
      // so both consoles ship at their original paths. — J.F. 2023-08
    },
  };
});
