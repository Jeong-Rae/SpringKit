import { defineConfig } from "vite-plus";

const taskEntries = {
  "add-task": "scripts/task/add-task.ts",
  "issue-task-id": "scripts/task/issue-task-id.ts",
  "lint-task": "scripts/task/lint-task.ts",
  "validate-tasks": "scripts/task/validate-tasks.ts",
};

const taskPacks = Object.entries(taskEntries).map(([name, entry]) => ({
  entry: { [name]: entry },
  format: "esm" as const,
  platform: "node" as const,
  target: "node20",
  dts: false,
  sourcemap: false,
  minify: false,
  clean: false,
  outDir: ".agents/skills/task-graph/scripts",
  deps: {
    alwaysBundle: ["yaml"],
    onlyBundle: ["yaml"],
    onlyImport: [],
  },
  outputOptions: {
    entryFileNames: "[name].mjs",
  },
}));

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
  pack: taskPacks,
  test: {
    include: ["test/task/**/*.test.ts"],
  },
});
