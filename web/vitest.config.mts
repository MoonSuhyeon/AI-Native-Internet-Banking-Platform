import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'

// tsconfig.json 의 path alias("@/*": "./*") 와 일치. web 루트를 '@' 로.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true, // describe/it/expect 를 import 없이 사용 가능
    setupFiles: ['./vitest.setup.ts'],
    include: ['lib/**/*.{test,spec}.{ts,tsx}', 'components/**/*.{test,spec}.{ts,tsx}'],
  },
  resolve: {
    alias: { '@': resolve(__dirname, '.') },
  },
})
