import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { resolve } from 'node:path';

export default defineConfig({
  base: './',
  plugins: [
    tailwindcss(),
    react(),
    {
      name: 'dev-csp',
      apply: 'serve',
      transformIndexHtml(html) {
        return html
          .replace("script-src 'self'", "script-src 'self' 'unsafe-inline'")
          .replace(
            "connect-src 'self'",
            "connect-src 'self' ws://localhost:* http://localhost:*",
          );
      },
    },
  ],
  resolve: {
    alias: { '@': resolve('src') },
  },
  server: { port: 5173, strictPort: true },
  build: { outDir: 'dist', target: 'chrome120', emptyOutDir: true },
});
