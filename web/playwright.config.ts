import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  testIgnore: ["**/live/**", "**/day2-live/**", "**/performance.spec.ts"],
  fullyParallel: false,
  reporter: "list",
  use: {
    ...devices["Desktop Chrome"],
    baseURL: "http://127.0.0.1:5173",
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npm run dev",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: false,
    timeout: 60_000,
    env: {
      VITE_API_BASE_URL: "/api",
      VITE_OIDC_AUTHORITY: "http://127.0.0.1:8081/realms/fleetflow",
      VITE_OIDC_CLIENT_ID: "fleetflow-web",
      VITE_OIDC_REDIRECT_URI: "http://127.0.0.1:5173/auth/callback",
      VITE_OIDC_POST_LOGOUT_URI: "http://127.0.0.1:5173/auth/signed-out",
    },
  },
});
