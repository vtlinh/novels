import { execSync } from 'node:child_process'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// base path only matters for the GH Pages build; dev serves at /
export default defineConfig(({ command }) => ({
  plugins: [react()],
  base: command === 'build' ? (process.env.BASE_PATH ?? '/novels/tinytube/') : '/',
  // strictPort: a second dev server must die loudly, not silently bump to
  // 5174 and leave two live servers serving different code. usePolling: fs
  // events never fire on this machine (endpoint security), so the dev server
  // kept serving stale modules until restarted.
  server: { strictPort: true, watch: { usePolling: true } },
  define: { __COMMIT_SHA__: JSON.stringify(execSync('git rev-parse HEAD', { encoding: 'utf8' }).trim()) },
  // globals so testing-library auto-cleans between tests
  test: { include: ['tests/**/*.test.{js,jsx}'], environment: 'jsdom', globals: true },
}))
