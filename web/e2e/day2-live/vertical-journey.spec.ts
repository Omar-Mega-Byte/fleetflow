import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { writeFile } from "node:fs/promises";
import path from "node:path";

type Account = { username: string; password: string };
type Accounts = Record<"customer" | "merchant" | "driver" | "dispatcher" | "bootstrap", Account>;

const appOrigin = "http://127.0.0.1:5173";
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function accountsFromEnvironment(): Accounts {
  const raw = process.env.FLEETFLOW_E2E_ACCOUNTS;
  if (!raw) throw new Error("Run scripts/Run-Day2VerticalJourney.py to prepare isolated real services");
  return JSON.parse(raw) as Accounts;
}

async function audit(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  expect(result.violations.map(({ id, impact, nodes }) => ({
    id,
    impact,
    nodes: nodes.map(({ target, html, failureSummary }) => ({
      target,
      html,
      failureSummary,
    })),
  }))).toEqual([]);
}

async function mobileAcceptance(page: Page, evidence: string, persona: string) {
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await audit(page);
  await page.screenshot({ path: path.join(evidence, `${persona}-mobile.png`), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
}

async function login(
  browser: Browser,
  persona: keyof Accounts,
  route: string,
  options: { geolocation?: boolean } = {},
): Promise<{ context: BrowserContext; page: Page; errors: string[]; streamRequests: string[] }> {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    permissions: options.geolocation ? ["geolocation"] : [],
    geolocation: { latitude: 30.04442, longitude: 31.235712 },
  });
  const page = await context.newPage();
  const errors: string[] = [];
  const streamRequests: string[] = [];
  page.on("pageerror", (error) => {
    errors.push(error.message.replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g, "[REDACTED]"));
  });
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.endsWith("/tracking/stream")) {
      streamRequests.push(request.url().split("?")[0]!);
    }
  });

  await page.goto(appOrigin + route);
  await expect(page.getByRole("button", { name: "Continue to sign in" })).toBeVisible();
  const authorization = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname.endsWith("/protocol/openid-connect/auth") &&
      url.searchParams.get("client_id") === "fleetflow-web";
  });
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  const authUrl = new URL((await authorization).url());
  expect(authUrl.searchParams.get("response_type")).toBe("code");
  expect(authUrl.searchParams.get("code_challenge_method")).toBe("S256");
  expect(authUrl.searchParams.get("code_challenge")?.length).toBeGreaterThanOrEqual(43);
  expect(authUrl.searchParams.has("access_token")).toBe(false);
  expect(authUrl.searchParams.has("id_token")).toBe(false);

  const account = accountsFromEnvironment()[persona];
  await page.getByRole("textbox", { name: /Username|Email/i }).fill(account.username);
  await page.getByLabel("Password", { exact: true }).fill(account.password);
  await page.getByRole("button", { name: /Sign in/i }).click();
  await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
  await expect.poll(() => {
    const url = new URL(page.url());
    return !url.searchParams.has("access_token") && !url.searchParams.has("refresh_token") &&
      !url.searchParams.has("id_token");
  }).toBe(true);

  const persistedCredential = await page.evaluate(() => {
    const entries = [localStorage, sessionStorage].flatMap((store) =>
      Object.entries(store).flat(),
    );
    return entries.some((entry) =>
      /access_token|refresh_token|id_token|eyJ[\w-]+\.[\w-]+\.[\w-]+/i.test(entry),
    );
  });
  expect(persistedCredential).toBe(false);
  return { context, page, errors, streamRequests };
}

test("signed browser completes a customer order through every real persona and tracking SSE", async ({ browser }) => {
  test.setTimeout(240_000);
  const evidence = process.env.FLEETFLOW_E2E_EVIDENCE_DIR;
  const branch = process.env.FLEETFLOW_E2E_BRANCH;
  if (!evidence || !branch || !uuidPattern.test(branch)) {
    throw new Error("The disposable journey harness did not provide its evidence directory and branch");
  }

  const sessions: Array<{ context: BrowserContext; page: Page; errors: string[]; streamRequests: string[] }> = [];
  const allErrors: string[] = [];
  try {
    const operator = await login(browser, "bootstrap", "/workspace/admin");
    sessions.push(operator);
    await expect(operator.page.getByRole("heading", { name: "Synthetic dataset" })).toBeVisible();
    await audit(operator.page);
    await mobileAcceptance(operator.page, evidence, "operator");

    const seedResponsePromise = operator.page.waitForResponse((response) =>
      new URL(response.url()).pathname === "/api/v1/demo/seed" &&
      response.request().method() === "POST",
    );
    await operator.page.getByRole("button", { name: "Prepare synthetic demo", exact: true }).click();
    await operator.page.getByLabel("Audit reason").fill("Prepare the Day 2 signed browser journey");
    await operator.page.getByRole("dialog", { name: "Prepare synthetic demo" })
      .getByRole("button", { name: "Prepare synthetic demo", exact: true }).click();
    const seedResponse = await seedResponsePromise;
    expect(seedResponse.status()).toBe(200);
    const seeded = await seedResponse.json() as { generation: number; result: string; orderIds: string[] };
    expect(seeded).toMatchObject({ generation: 1, result: "SEEDED" });
    expect(seeded.orderIds).toHaveLength(1);
    await expect(operator.page.getByText("Synthetic demo prepared successfully.")).toBeVisible();
    await expect(operator.page.locator(".success-state")).toContainText("generation 1 with 1 synthetic order");

    const resetRequests: Array<{ body: string | null; key: string | null }> = [];
    let committedReset: { generation: number; result: string; orderIds: string[] } | null = null;
    await operator.page.route("**/api/v1/demo/reset", async (route) => {
      const request = route.request();
      resetRequests.push({ body: request.postData(), key: await request.headerValue("Idempotency-Key") });
      if (resetRequests.length === 1) {
        const response = await route.fetch();
        expect(response.status()).toBe(200);
        committedReset = await response.json() as { generation: number; result: string; orderIds: string[] };
        await route.abort("failed");
        return;
      }
      await route.continue();
    });
    const resetResponsePromise = operator.page.waitForResponse((response) =>
      new URL(response.url()).pathname === "/api/v1/demo/reset" &&
      response.request().method() === "POST",
    );
    await operator.page.getByRole("button", { name: "Reset demo generation", exact: true }).click();
    await operator.page.getByLabel("Audit reason").fill("Start a clean Day 2 browser journey");
    await operator.page.getByLabel("Type RESET FLEETFLOW DEMO").fill("RESET FLEETFLOW DEMO");
    await operator.page.getByRole("dialog", { name: "Reset synthetic demo" })
      .getByRole("button", { name: "Reset synthetic demo", exact: true }).click();
    await expect(operator.page.getByRole("heading", { name: "Connection unavailable" })).toBeVisible();
    await expect(operator.page.getByText(/The result is unknown\. Retry this same request/)).toBeVisible();
    await expect(operator.page.getByLabel("Audit reason")).toBeDisabled();
    await expect(operator.page.getByRole("button", { name: "Close dialog" })).toBeDisabled();
    await operator.page.getByRole("button", { name: "Try again", exact: true }).click();
    const resetResponse = await resetResponsePromise;
    expect(resetResponse.status()).toBe(200);
    const reset = await resetResponse.json() as { generation: number; result: string; orderIds: string[] };
    expect(reset).toMatchObject({ generation: 2, result: "RESET" });
    expect(reset.orderIds).toHaveLength(1);
    expect(committedReset).toMatchObject(reset);
    expect(resetRequests).toHaveLength(2);
    expect(JSON.parse(resetRequests[1]!.body!)).toEqual(JSON.parse(resetRequests[0]!.body!));
    expect(resetRequests[1]!.key).toBe(resetRequests[0]!.key);
    await expect(operator.page.getByText("Synthetic demo reset successfully.")).toBeVisible();
    await expect(operator.page.locator(".success-state")).toContainText("generation 2 with 1 synthetic order");

    const driver = await login(browser, "driver", "/workspace/driver", { geolocation: true });
    sessions.push(driver);
    await expect(driver.page.getByRole("heading", { name: "Driver workspace" })).toBeVisible();
    await driver.page.getByRole("button", { name: "Go online", exact: true }).click();
    await driver.page.getByRole("button", { name: "Make available", exact: true }).waitFor();
    await driver.page.getByRole("button", { name: "Make available", exact: true }).click();
    await driver.page.getByRole("button", { name: "Share current position", exact: true }).click();
    await expect(driver.page.getByText("Current position shared.", { exact: true })).toBeVisible();
    await driver.page.getByRole("button", { name: "Refresh heartbeat", exact: true }).click();
    await expect(driver.page.getByText("Heartbeat refreshed.", { exact: true })).toBeVisible();
    await audit(driver.page);
    await mobileAcceptance(driver.page, evidence, "driver");

    const customer = await login(browser, "customer", "/customer/orders");
    sessions.push(customer);
    let customerStreamAttempts = 0;
    await customer.page.route("**/api/v1/orders/*/tracking/stream", async (route) => {
      customerStreamAttempts += 1;
      if (customerStreamAttempts === 1) {
        await route.abort("failed");
        return;
      }
      await route.continue();
    });
    await expect(customer.page.getByRole("heading", { name: "Your orders" })).toBeVisible();
    await customer.page.evaluate(() => {
      history.pushState({}, "", "/workspace/admin");
      dispatchEvent(new PopStateEvent("popstate"));
    });
    await expect(customer.page.getByRole("heading", { name: "Access unavailable" })).toBeVisible();
    await customer.page.evaluate(() => {
      history.pushState({}, "", "/customer/orders");
      dispatchEvent(new PopStateEvent("popstate"));
    });
    await expect(customer.page.getByRole("heading", { name: "Your orders" })).toBeVisible();
    await customer.page.getByRole("button", { name: "Create order", exact: true }).click();
    await expect(customer.page.getByRole("dialog", { name: "Create a local test order" })).toBeVisible();
    await customer.page.getByLabel("Merchant branch ID").fill(branch);
    await customer.page.getByLabel("Delivery address").fill("22 Browser Review Street");
    await customer.page.getByLabel("City", { exact: true }).fill("Cairo");
    await customer.page.getByLabel("Currency", { exact: true }).fill("EGP");
    await customer.page.getByLabel("Latitude", { exact: true }).fill("30.052100");
    await customer.page.getByLabel("Longitude", { exact: true }).fill("31.242200");
    await customer.page.getByLabel("Item description").fill("Day 2 signed workflow parcel");
    await customer.page.getByLabel("Quantity", { exact: true }).fill("1");
    await customer.page.getByLabel("Unit amount", { exact: true }).fill("245.00");
    await customer.page.getByRole("button", { name: "Create local order", exact: true }).click();
    await expect(customer.page).toHaveURL(/\/customer\/orders\/[0-9a-f-]+$/);
    const orderId = new URL(customer.page.url()).pathname.split("/").at(-1)!;
    expect(orderId).toMatch(uuidPattern);
    await expect(customer.page.getByRole("heading", { name: "Downtown Branch" })).toBeVisible();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("Created");
    await expect(customer.page.getByText("Live updates", { exact: true })).toBeVisible();
    await expect.poll(() => customer.streamRequests.length).toBeGreaterThanOrEqual(2);
    expect(customerStreamAttempts).toBeGreaterThanOrEqual(2);
    await audit(customer.page);
    await mobileAcceptance(customer.page, evidence, "customer");

    const merchant = await login(browser, "merchant", `/workspace/merchant?branchId=${branch}`);
    sessions.push(merchant);
    await expect(merchant.page.getByRole("heading", { name: "Branch orders" })).toBeVisible();
    const orderRow = merchant.page.locator(".zone-item").filter({ hasText: orderId.slice(0, 8) }).first();
    await expect(orderRow).toBeVisible();
    await orderRow.click();
    await expect(merchant.page.getByText(orderId, { exact: true })).toBeVisible();
    await expect(merchant.page.getByRole("button", { name: "Confirm order", exact: true })).toBeVisible();
    await audit(merchant.page);
    await mobileAcceptance(merchant.page, evidence, "merchant");
    await merchant.page.getByRole("button", { name: "Confirm order", exact: true }).click();
    await expect(merchant.page.getByRole("button", { name: "Start preparation", exact: true })).toBeVisible();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("Confirmed", { timeout: 45_000 });
    await merchant.page.getByRole("button", { name: "Start preparation", exact: true }).click();
    await expect(merchant.page.getByRole("button", { name: "Ready for pickup", exact: true })).toBeVisible();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("Preparing", { timeout: 45_000 });
    await merchant.page.getByRole("button", { name: "Ready for pickup", exact: true }).click();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("Ready For Pickup", { timeout: 45_000 });

    const dispatcher = await login(browser, "dispatcher", `/workspace/dispatcher?orderId=${orderId}`);
    sessions.push(dispatcher);
    await expect(dispatcher.page.getByRole("heading", { name: "Matching evidence" })).toBeVisible();
    await audit(dispatcher.page);
    await mobileAcceptance(dispatcher.page, evidence, "dispatcher");
    await dispatcher.page.getByRole("button", { name: "Start matching", exact: true }).click();
    const eligible = dispatcher.page.getByText("Eligible", { exact: true });
    await expect(eligible).toBeVisible({ timeout: 45_000 });
    await dispatcher.page.getByRole("button", { name: "Send offer", exact: true }).click();
    await expect(dispatcher.page.getByText("Offer awaiting response", { exact: true })).toBeVisible();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("Dispatching");

    await expect(driver.page.getByText(`Order ${orderId.slice(0, 8).toUpperCase()}`, { exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(driver.page.getByRole("button", { name: "Accept offer", exact: true })).toBeVisible();
    await driver.page.getByRole("button", { name: "Accept offer", exact: true }).click();
    await expect(driver.page.getByRole("button", { name: "Confirm pickup", exact: true })).toBeVisible();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("Driver Assigned", { timeout: 45_000 });
    await driver.page.getByRole("button", { name: "Confirm pickup", exact: true }).click();
    await expect(driver.page.getByRole("button", { name: "Start transit", exact: true })).toBeVisible();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("Picked Up", { timeout: 45_000 });
    await driver.page.getByRole("button", { name: "Start transit", exact: true }).click();
    await expect(driver.page.getByRole("button", { name: "Complete delivery", exact: true })).toBeVisible();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("In Transit", { timeout: 45_000 });
    await driver.page.getByRole("button", { name: "Share current position", exact: true }).click();
    await expect(customer.page.getByText(/Position reported/)).toBeVisible();
    await driver.page.getByRole("button", { name: "Complete delivery", exact: true }).click();
    await expect(driver.page.getByText("Delivered", { exact: true })).toBeVisible();
    await expect(customer.page.locator(".detail-heading .status-pill")).toHaveText("Delivered", { timeout: 45_000 });
    await expect(customer.page.getByText("Tracking ended", { exact: true })).toBeVisible();
    await expect.poll(() => customer.streamRequests.length).toBe(2);
    expect(customerStreamAttempts).toBe(2);
    await audit(customer.page);

    await customer.page.setViewportSize({ width: 390, height: 844 });
    expect(await customer.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await customer.page.screenshot({ path: path.join(evidence, "customer-delivered-mobile.png"), fullPage: true });
    await customer.page.setViewportSize({ width: 1440, height: 1000 });
    await customer.page.screenshot({ path: path.join(evidence, "customer-delivered-desktop.png"), fullPage: true });
    await driver.page.setViewportSize({ width: 390, height: 844 });
    expect(await driver.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await driver.page.screenshot({ path: path.join(evidence, "driver-delivered-mobile.png"), fullPage: true });
    await driver.page.setViewportSize({ width: 1440, height: 1000 });
    await driver.page.screenshot({ path: path.join(evidence, "driver-delivered-desktop.png"), fullPage: true });

    for (const session of sessions) allErrors.push(...session.errors);
    expect(allErrors).toEqual([]);
    await customer.page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(customer.page.getByRole("heading", { name: "You’re signed out" })).toBeVisible();

    await writeFile(path.join(evidence, "vertical-result.json"), JSON.stringify({
      seedGeneration: seeded.generation,
      resetGeneration: reset.generation,
      seedResult: seeded.result,
      resetResult: reset.result,
      orderId,
      trackingStreamRequests: customer.streamRequests.length,
      finalCustomerStatus: "DELIVERED",
      logoutConfirmed: true,
    }, null, 2) + "\n", { encoding: "utf-8" });
  } finally {
    await Promise.all(sessions.map(({ context }) => context.close()));
  }
});
