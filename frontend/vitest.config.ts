import { defineConfig } from "vitest/config";

export default defineConfig({
  esbuild: {
    jsx: "automatic",
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./tests/setup.ts"],
    // Cap concurrent workers to bound memory. Threads share heap more
    // efficiently than forks; 2 is enough for parallelism on this suite
    // without each run blowing past ~1GB. Tune lower if memory is still tight.
    pool: "threads",
    poolOptions: {
      threads: {
        minThreads: 1,
        maxThreads: 2,
      },
    },
    coverage: {
      provider: "v8",
      reporter: ["text", "html"],
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/types/**",
        "src/main.tsx",
        "src/pdf-worker.ts",
        "src/**/*.d.ts",
        "tests/**",
        "vite.config.ts",
        "vitest.config.ts",
        "eslint.config.js",
      ],
      thresholds: {
        lines: 70,
        branches: 60,
        functions: 70,
        statements: 70,
      },
    },
  },
});
