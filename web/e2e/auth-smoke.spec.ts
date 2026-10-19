import { expect, test } from "@playwright/test";
import { installOidc } from "./fixtures/oidc";
import { productFixture } from "./fixtures/product";

test("first-party form keeps credentials on the login request and uses the session header", async ({ page }) => {
  await productFixture(page, ["CUSTOMER"]);
  const token = "a".repeat(43);
  await page.route("**/api/v1/auth/login", route => route.fulfill({
    json: { token, expiresAt: new Date(Date.now() + 90_000).toISOString() },
    headers: { "Cache-Control": "no-store" },
  }));
  await page.goto("/login");
  await page.getByRole("textbox", { name: "Email" }).fill("customer@fleetflow.demo");
  await page.getByLabel("Password", { exact: true }).fill("synthetic-password");
  const loginRequest = page.waitForRequest(request => new URL(request.url()).pathname === "/api/v1/auth/login");
  const sessionRequest = page.waitForRequest(request => new URL(request.url()).pathname === "/api/v1/session"
    && request.headers()["x-fleetflow-session"] === token);
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  const request = await loginRequest;
  expect(request.postDataJSON()).toEqual({ email: "customer@fleetflow.demo", password: "synthetic-password" });
  await sessionRequest;
  await expect(page.getByRole("heading", { name: "Command Center" })).toBeVisible();
});

test("signed PKCE login, protected customer route, and logout", async ({
  page,
}) => {
  await installOidc(page);
  await page.route("**/*", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/session") {
      await route.fulfill({ json: { roles: ["CUSTOMER"] } });
    } else if (path === "/api/v1/notifications/me") {
      await route.fulfill({ json: { content: [] } });
    } else if (path === "/api/v1/orders") {
      await route.fulfill({
        json: {
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        },
      });
    } else await route.fallback();
  });

  await page.setViewportSize({ width: 360, height: 740 });
  await page.goto("/login");
  const loginButton = page.getByRole("button", { name: "Continue with workspace SSO" });
  await expect(loginButton).toBeEnabled();
  await loginButton.focus();
  await expect(loginButton).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/command-center$/);
  await expect(
    page.getByRole("heading", { name: "Command Center" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Orders", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Your orders" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "No orders yet" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/auth\/signed-out$/);
  await expect(
    page.getByRole("heading", { name: "You’re signed out" }),
  ).toBeVisible();
});
