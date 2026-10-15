import { expect, test } from "@playwright/test";
import { installOidc } from "./fixtures/oidc";

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
  const loginButton = page.getByRole("button", { name: "Continue to sign in" });
  await expect(loginButton).toBeEnabled();
  await page.keyboard.press("Tab");
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
