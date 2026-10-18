import { expect, test, type Page } from "@playwright/test";
import path from "node:path";
import { writeFile } from "node:fs/promises";

test("real persona workspaces, driver actions, PostGIS map and guarded dispatch", async ({
  browser,
}) => {
  test.setTimeout(180000);
  if (!process.env.FLEETFLOW_E2E_ACCOUNTS)
    throw new Error("Use the disposable browser harness");
  const accounts = JSON.parse(process.env.FLEETFLOW_E2E_ACCOUNTS) as Record<
    string,
    { username: string; password: string }
  >;
  const branch = process.env.FLEETFLOW_E2E_BRANCH!;
  const order = process.env.FLEETFLOW_E2E_ORDER!;
  const evidence = process.env.FLEETFLOW_E2E_EVIDENCE_DIR!;
  async function login(page: Page, persona: string, route: string) {
    await page.goto("https://demo.fleetflow.localhost" + route);
    await page.getByRole("button", { name: "Continue to sign in" }).click();
    await page
      .getByRole("textbox", { name: /Username|Email/i })
      .fill(accounts[persona]!.username);
    await page
      .getByLabel("Password", { exact: true })
      .fill(accounts[persona]!.password);
    await page.getByRole("button", { name: /Sign in/i }).click();
  }
  for (const persona of ["merchant", "driver", "dispatcher", "bootstrap"]) {
    const context = await browser.newContext({
      ignoreHTTPSErrors: true,
      viewport: { width: 1440, height: 1000 },
      permissions: [],
    });
    const page = await context.newPage();
    const errors: string[] = [];
    page.on("pageerror", (e) =>
      errors.push(
        e.message.replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g, "[REDACTED]"),
      ),
    );
    try {
      if (persona === "merchant") {
        await login(page, persona, `/workspace/merchant?branchId=${branch}`);
        await expect(
          page.getByRole("heading", { name: "Branch orders" }),
        ).toBeVisible();
        await expect(
          page.getByRole("button", { name: "Start preparation", exact: true }),
        ).toBeVisible();
        await expect(page.getByRole("alert")).toHaveCount(0);
        await page.screenshot({
          path: path.join(evidence, "merchant-live.png"),
          fullPage: true,
        });
        await page
          .getByRole("navigation", { name: "Main navigation" })
          .getByRole("link", { name: "Delivery zones", exact: true })
          .click();
        await expect(
          page.getByRole("heading", { name: "Central Demo Zone", exact: true }),
        ).toBeVisible();
        await expect(page.locator(".map-canvas")).toBeVisible();
        await expect(
          page.locator(".leaflet-overlay-pane path").first(),
        ).toBeVisible();
        const basemapLoaded = await page
          .waitForFunction(
            () => document.querySelectorAll(".leaflet-tile-loaded").length > 0,
            undefined,
            { timeout: 10000 },
          )
          .then(
            () => true,
            () => false,
          );
        await writeFile(
          path.join(evidence, "basemap-result.json"),
          JSON.stringify(
            {
              actualTileLoaded: basemapLoaded,
              tileHost: "https://tile.openstreetmap.org",
              authorizedPostgisOverlayVisible: true,
              disposition: basemapLoaded
                ? "VERIFIED_PRIVATE_EDGE"
                : "NOT_VERIFIED_EXTERNAL_TILE_DELIVERY",
            },
            null,
            2,
          ),
        );
        await page.screenshot({
          path: path.join(evidence, "zones-live.png"),
          fullPage: true,
        });
        await page.setViewportSize({ width: 390, height: 844 });
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        ).toBe(true);
        await page.screenshot({
          path: path.join(evidence, "zones-live-mobile.png"),
          fullPage: true,
        });
      } else if (persona === "driver") {
        await login(page, persona, "/workspace/driver");
        await expect(
          page.getByRole("heading", { name: "Synthetic movement" }),
        ).toBeVisible();
        await expect(
          page.getByRole("button", { name: "Share current position" }),
        ).toHaveCount(0);
        await page
          .getByRole("button", { name: "Go online", exact: true })
          .click();
        await page
          .getByRole("button", { name: "Make available", exact: true })
          .click();
        await page
          .getByRole("button", { name: "Refresh heartbeat", exact: true })
          .click();
        await expect(
          page.getByText("Heartbeat refreshed.", { exact: true }),
        ).toBeVisible();
        const locationRequest = page.waitForRequest(
          (request) =>
            new URL(request.url()).pathname === "/api/v1/drivers/me/location" &&
            request.method() === "POST",
          { timeout: 15000 },
        );
        await page
          .getByRole("button", { name: "Send synthetic pickup", exact: true })
          .click();
        const request = await locationRequest;
        const locationResponse = await request.response();
        expect(
          locationResponse?.status(),
          "the authenticated driver location update should return 204",
        ).toBe(204);
        await expect(
          page.getByText("Synthetic position shared.", { exact: true }),
        ).toBeVisible();
        await page.screenshot({
          path: path.join(evidence, "driver-live.png"),
          fullPage: true,
        });
        await page
          .getByRole("button", { name: "Pause offers", exact: true })
          .click();
        await page
          .getByRole("button", { name: "Go offline", exact: true })
          .click();
        await expect(
          page.getByRole("button", { name: "Go online", exact: true }),
        ).toBeVisible();
        await expect(page.getByRole("alert")).toHaveCount(0);
      } else if (persona === "dispatcher") {
        await login(page, persona, `/workspace/dispatcher?orderId=${order}`);
        await expect(
          page.getByRole("heading", { name: "Matching evidence", exact: true }),
        ).toBeVisible();
        await expect(page.getByRole("alert")).toHaveCount(0);
        await page
          .getByRole("button", { name: "Start matching", exact: true })
          .click();
        await expect(
          page.getByRole("heading", { name: "This information changed" }),
        ).toBeVisible();
        await page.screenshot({
          path: path.join(evidence, "dispatch-guard-live.png"),
          fullPage: true,
        });
      } else {
        await login(page, persona, "/workspace/admin");
        await expect(
          page.getByRole("heading", { name: "Synthetic dataset", exact: true }),
        ).toBeVisible();
        await expect(
          page.getByRole("button", {
            name: "Prepare synthetic demo",
            exact: true,
          }),
        ).toBeVisible();
        await expect(page.getByRole("alert")).toHaveCount(0);
        await page.screenshot({
          path: path.join(evidence, "operations-live.png"),
          fullPage: true,
        });
      }
      expect(errors).toEqual([]);
      await page.getByRole("button", { name: "Sign out", exact: true }).click();
      await expect(
        page.getByRole("heading", { name: "You’re signed out" }),
      ).toBeVisible();
    } finally {
      await context.close();
    }
  }
});
