module.exports = {
  root: true,
  env: { browser: true, es2022: true, node: true },
  parser: "@typescript-eslint/parser",
  parserOptions: { ecmaVersion: 2022, sourceType: "module" },
  ignorePatterns: ["dist", "node_modules", "coverage", "playwright-report"],
  rules: {},
};
