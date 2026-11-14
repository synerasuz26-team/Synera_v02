import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [
    react(),
    {
      name: 'exclude-production-mocks',
      apply: 'build',
      generateBundle(_options, bundle) {
        for (const chunk of Object.values(bundle)) {
          if (
            chunk.type === 'chunk' &&
            Object.keys(chunk.modules).some((id) =>
              id.replaceAll('\\', '/').includes('/packages/mock-data/'),
            )
          ) {
            this.error('Production bundle must not contain packages/mock-data.');
          }
        }
      },
    },
  ],
  server: {
    port: 5173,
    strictPort: true,
  },
});
