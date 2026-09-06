import { defineConfig } from "vite-plus";

export default defineConfig({
  fmt: {
    ignorePatterns: [
      ".agents/**",
      ".worktree/**",
      "docs/**",
      "node_modules/**",
      "scripts/git/**",
      "tasks/**",
      "template/**",
    ],
    singleQuote: false,
    semi: true,
    sortPackageJson: true,
  },
  lint: {
    ignorePatterns: [
      ".agents/**",
      ".worktree/**",
      "node_modules/**",
      "scripts/git/**",
      "template/**",
    ],
    options: {
      typeAware: true,
      typeCheck: true,
    },
  },
  test: {
    include: ["test/task/**/*.test.ts"],
  },
});
