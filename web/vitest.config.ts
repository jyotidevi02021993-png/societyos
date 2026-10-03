import { defineConfig } from "vitest/config";

// `npm test` at the root runs every project: the BFF package and both apps.
export default defineConfig({
  test: {
    projects: ["packages/auth", "apps/admin-web", "apps/resident-web"],
  },
});
