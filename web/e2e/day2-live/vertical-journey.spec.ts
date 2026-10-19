import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Page,
  type Locator,
} from "@playwright/test";
import { writeFile } from "node:fs/promises";
import path from "node:path";

type Account = { username: string; password: string };
type Accounts = Record<
  "customer" | "merchant" | "driver" | "dispatcher" | "bootstrap",
  Account
>;

const appOrigin = "http://127.0.0.1:5173";
const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

async function activate(control: Locator) {
  await expect(control).toBeEnabled();
  await control.press("Enter");
}

function accountsFromEnvironment(): Accounts {
  const raw = process.env.FLEETFLOW_E2E_ACCOUNTS;
  if (!raw)
    throw new Error(
      "Run scripts/Run-Day2VerticalJourney.py to prepare isolated real services",
    );
  return JSON.parse(raw) as Accounts;
}

async function audit(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  expect(
    result.violations.map(({ id, impact, nodes }) => ({
      id,
      impact,
      nodes: nodes.map(({ target, html, failureSummary }) => ({
        target,
        html,
        failureSummary,
      })),
    })),
  ).toEqual([]);
}

async function mobileAcceptance(page: Page, evidence: string, persona: string) {
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await audit(page);
  const modal = page.getByRole("dialog");
  if (await modal.count())
    await modal.getByRole("button", { name: "Close dialog" }).focus();
  else await page.getByRole("heading", { level: 1 }).focus();
  await page.keyboard.press("Tab");
  expect(
    await page.evaluate(() => {
      const active = document.activeElement;
      if (!active || active === document.body) return false;
      const style = getComputedStyle(active);
      return (
        style.outlineStyle !== "none" && parseFloat(style.outlineWidth) >= 2
      );
    }),
  ).toBe(true);
  await page.screenshot({
    path: path.join(evidence, `${persona}-mobile.png`),
    fullPage: true,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
}

async function login(
  browser: Browser,
  persona: keyof Accounts,
  route: string,
  options: { failedTiles?: boolean } = {},
): Promise<{
  context: BrowserContext;
  page: Page;
  errors: string[];
  streamRequests: string[];
}> {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    permissions: [],
  });
  await context.addInitScript(() => {
    let calls = 0;
    Object.defineProperty(window, "__fleetflowGpsCalls", { get: () => calls });
    const original = navigator.geolocation.getCurrentPosition.bind(
      navigator.geolocation,
    );
    navigator.geolocation.getCurrentPosition = (...args) => {
      calls += 1;
      original(...args);
    };
  });
  const page = await context.newPage();
  page.setDefaultTimeout(15_000);
  if (options.failedTiles)
    await page.route("https://tile.openstreetmap.org/**", (route) =>
      route.abort("failed"),
    );
  const errors: string[] = [];
  const streamRequests: string[] = [];
  page.on("pageerror", (error) => {
    errors.push(
      error.message.replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g, "[REDACTED]"),
    );
  });
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.endsWith("/tracking/stream")) {
      streamRequests.push(request.url().split("?")[0]!);
    }
  });

  await page.goto(appOrigin + route);
  await expect(
    page.getByRole("button", { name: "Continue with workspace SSO" }),
  ).toBeVisible();
  const authorization = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return (
      url.pathname.endsWith("/protocol/openid-connect/auth") &&
      url.searchParams.get("client_id") === "fleetflow-web"
    );
  });
  await page.getByRole("button", { name: "Continue with workspace SSO" }).click();
  const authUrl = new URL((await authorization).url());
  expect(authUrl.searchParams.get("response_type")).toBe("code");
  expect(authUrl.searchParams.get("code_challenge_method")).toBe("S256");
  expect(
    authUrl.searchParams.get("code_challenge")?.length,
  ).toBeGreaterThanOrEqual(43);
  expect(authUrl.searchParams.has("access_token")).toBe(false);
  expect(authUrl.searchParams.has("id_token")).toBe(false);

  const account = accountsFromEnvironment()[persona];
  await page
    .getByRole("textbox", { name: /Username|Email/i })
    .fill(account.username);
  await page.getByLabel("Password", { exact: true }).fill(account.password);
  await page.getByRole("button", { name: /Sign in/i }).click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible({ timeout: 30_000 });
  await expect
    .poll(() => {
      const url = new URL(page.url());
      return (
        !url.searchParams.has("access_token") &&
        !url.searchParams.has("refresh_token") &&
        !url.searchParams.has("id_token")
      );
    })
    .toBe(true);

  const persistedCredential = await page.evaluate(() => {
    const entries = [localStorage, sessionStorage].flatMap((store) =>
      Object.entries(store).flat(),
    );
    return entries.some((entry) =>
      /access_token|refresh_token|id_token|eyJ[\w-]+\.[\w-]+\.[\w-]+/i.test(
        entry,
      ),
    );
  });
  expect(persistedCredential).toBe(false);
  return { context, page, errors, streamRequests };
}

test("signed browser completes a customer order through every real persona and tracking SSE", async ({
  browser,
}) => {
  test.setTimeout(240_000);
  const evidence = process.env.FLEETFLOW_E2E_EVIDENCE_DIR;
  const branch = process.env.FLEETFLOW_E2E_BRANCH;
  if (!evidence || !branch || !uuidPattern.test(branch)) {
    throw new Error(
      "The disposable journey harness did not provide its evidence directory and branch",
    );
  }

  const sessions: Array<{
    context: BrowserContext;
    page: Page;
    errors: string[];
    streamRequests: string[];
  }> = [];
  const allErrors: string[] = [];
  try {
    const operator = await login(browser, "bootstrap", "/workspace/admin");
    sessions.push(operator);
    await expect(
      operator.page.getByRole("heading", { name: "Synthetic dataset" }),
    ).toBeVisible();
    await audit(operator.page);
    await mobileAcceptance(operator.page, evidence, "operator");

    const seedResponsePromise = operator.page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/api/v1/demo/seed" &&
        response.request().method() === "POST",
    );
    await activate(
      operator.page.getByRole("button", {
        name: "Prepare synthetic demo",
        exact: true,
      }),
    );
    await operator.page
      .getByLabel("Audit reason")
      .fill("Prepare the Day 2 signed browser journey");
    await activate(
      operator.page
        .getByRole("dialog", { name: "Prepare synthetic demo" })
        .getByRole("button", { name: "Prepare synthetic demo", exact: true }),
    );
    const seedResponse = await seedResponsePromise;
    expect(seedResponse.status()).toBe(200);
    const seeded = (await seedResponse.json()) as {
      generation: number;
      result: string;
      orderIds: string[];
    };
    expect(seeded).toMatchObject({ generation: 1, result: "SEEDED" });
    expect(seeded.orderIds).toHaveLength(1);
    await expect(
      operator.page.getByText("Synthetic demo prepared successfully."),
    ).toBeVisible();
    await expect(operator.page.locator(".success-state")).toContainText(
      "generation 1 with 1 synthetic order",
    );

    const resetRequests: Array<{ body: string | null; key: string | null }> =
      [];
    let committedReset: {
      generation: number;
      result: string;
      orderIds: string[];
    } | null = null;
    await operator.page.route("**/api/v1/demo/reset", async (route) => {
      const request = route.request();
      resetRequests.push({
        body: request.postData(),
        key: await request.headerValue("Idempotency-Key"),
      });
      if (resetRequests.length === 1) {
        const response = await route.fetch();
        expect(response.status()).toBe(200);
        committedReset = (await response.json()) as {
          generation: number;
          result: string;
          orderIds: string[];
        };
        await route.abort("failed");
        return;
      }
      await route.continue();
    });
    const resetResponsePromise = operator.page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/api/v1/demo/reset" &&
        response.request().method() === "POST",
    );
    await operator.page
      .getByRole("button", { name: "Reset demo generation", exact: true })
      .click();
    await operator.page
      .getByLabel("Audit reason")
      .fill("Start a clean Day 2 browser journey");
    await operator.page
      .getByLabel("Type RESET FLEETFLOW DEMO")
      .fill("RESET FLEETFLOW DEMO");
    await activate(
      operator.page
        .getByRole("dialog", { name: "Reset synthetic demo" })
        .getByRole("button", { name: "Reset synthetic demo", exact: true }),
    );
    await expect(
      operator.page.getByRole("heading", { name: "Connection unavailable" }),
    ).toBeVisible();
    await expect(
      operator.page.getByText(
        /The result is unknown\. Retry this same request/,
      ),
    ).toBeVisible();
    await expect(operator.page.getByLabel("Audit reason")).toBeDisabled();
    await expect(
      operator.page.getByRole("button", { name: "Close dialog" }),
    ).toBeDisabled();
    await activate(
      operator.page.getByRole("button", { name: "Try again", exact: true }),
    );
    const resetResponse = await resetResponsePromise;
    expect(resetResponse.status()).toBe(200);
    const reset = (await resetResponse.json()) as {
      generation: number;
      result: string;
      orderIds: string[];
    };
    expect(reset).toMatchObject({ generation: 2, result: "RESET" });
    expect(reset.orderIds).toHaveLength(1);
    expect(committedReset).toMatchObject(reset);
    expect(resetRequests).toHaveLength(2);
    expect(JSON.parse(resetRequests[1]!.body!)).toEqual(
      JSON.parse(resetRequests[0]!.body!),
    );
    expect(resetRequests[1]!.key).toBe(resetRequests[0]!.key);
    await expect(
      operator.page.getByText("Synthetic demo reset successfully."),
    ).toBeVisible();
    await expect(operator.page.locator(".success-state")).toContainText(
      "generation 2 with 1 synthetic order",
    );

    const driver = await login(browser, "driver", "/workspace/driver");
    sessions.push(driver);
    await expect(
      driver.page.getByRole("heading", { name: "Driver workspace" }),
    ).toBeVisible();
    await activate(
      driver.page.getByRole("button", { name: "Go online", exact: true }),
    );
    await driver.page
      .getByRole("button", { name: "Make available", exact: true })
      .waitFor();
    await activate(
      driver.page.getByRole("button", { name: "Make available", exact: true }),
    );
    await expect(
      driver.page.getByRole("button", {
        name: "Share current position",
        exact: true,
      }),
    ).toHaveCount(0);
    await activate(
      driver.page.getByRole("button", {
        name: "Send synthetic pickup",
        exact: true,
      }),
    );
    await expect(
      driver.page.getByText("1 of 5 synthetic positions confirmed."),
    ).toBeVisible();
    await activate(
      driver.page.getByRole("button", {
        name: "Refresh heartbeat",
        exact: true,
      }),
    );
    await expect(
      driver.page.getByText("Heartbeat refreshed.", { exact: true }),
    ).toBeVisible();
    await audit(driver.page);
    await mobileAcceptance(driver.page, evidence, "driver");

    const customer = await login(browser, "customer", "/customer/orders");
    sessions.push(customer);
    let customerStreamAttempts = 0;
    await customer.page.route(
      "**/api/v1/orders/*/tracking/stream",
      async (route) => {
        customerStreamAttempts += 1;
        if (customerStreamAttempts === 1) {
          await route.abort("failed");
          return;
        }
        await route.continue();
      },
    );
    await expect(
      customer.page.getByRole("heading", { name: "Your orders" }),
    ).toBeVisible();
    await customer.page.evaluate(() => {
      history.pushState({}, "", "/workspace/admin");
      dispatchEvent(new PopStateEvent("popstate"));
    });
    await expect(
      customer.page.getByRole("heading", { name: "Access unavailable" }),
    ).toBeVisible();
    await customer.page.evaluate(() => {
      history.pushState({}, "", "/customer/orders");
      dispatchEvent(new PopStateEvent("popstate"));
    });
    await expect(
      customer.page.getByRole("heading", { name: "Your orders" }),
    ).toBeVisible();
    const creationRequests: Array<{ body: string | null; key: string | null }> =
      [];
    await customer.page.route("**/api/v1/orders", async (route) => {
      if (route.request().method() !== "POST") {
        await route.continue();
        return;
      }
      creationRequests.push({
        body: route.request().postData(),
        key: await route.request().headerValue("Idempotency-Key"),
      });
      if (creationRequests.length === 1) {
        const response = await route.fetch();
        expect(response.status()).toBe(201);
        await route.abort("failed");
      } else await route.continue();
    });
    await activate(
      customer.page.getByRole("button", { name: "Create order", exact: true }),
    );
    await expect(
      customer.page.getByRole("dialog", { name: "Create a local test order" }),
    ).toBeVisible();
    await customer.page.getByLabel("Merchant branch ID").fill(branch);
    await customer.page
      .getByLabel("Delivery address")
      .fill("22 Browser Review Street");
    await customer.page.getByLabel("City", { exact: true }).fill("Cairo");
    await customer.page.getByLabel("Currency", { exact: true }).fill("EGP");
    await customer.page
      .getByLabel("Latitude", { exact: true })
      .fill("30.052100");
    await customer.page
      .getByLabel("Longitude", { exact: true })
      .fill("31.242200");
    await customer.page
      .getByLabel("Item description")
      .fill("Day 2 signed workflow parcel");
    await customer.page.getByLabel("Quantity", { exact: true }).fill("1");
    await customer.page
      .getByLabel("Unit amount", { exact: true })
      .fill("245.00");
    await activate(
      customer.page.getByRole("button", {
        name: "Create local order",
        exact: true,
      }),
    );
    await expect(
      customer.page.getByRole("heading", { name: "Connection unavailable" }),
    ).toBeVisible();
    await expect(customer.page.getByLabel("Item description")).toBeDisabled();
    await expect(
      customer.page.getByRole("button", { name: "Close dialog" }),
    ).toBeDisabled();
    await activate(
      customer.page.getByRole("button", { name: "Try again", exact: true }),
    );
    await expect(customer.page).toHaveURL(/\/customer\/orders\/[0-9a-f-]+$/);
    expect(creationRequests).toHaveLength(2);
    expect(creationRequests[1]).toEqual(creationRequests[0]);
    const orderId = new URL(customer.page.url()).pathname.split("/").at(-1)!;
    expect(orderId).toMatch(uuidPattern);
    await expect(
      customer.page.getByRole("heading", { name: "Downtown Branch" }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Created");
    await expect(
      customer.page.getByText("Live updates", { exact: true }),
    ).toBeVisible();
    await expect
      .poll(() => customer.streamRequests.length)
      .toBeGreaterThanOrEqual(2);
    expect(customerStreamAttempts).toBeGreaterThanOrEqual(2);
    await audit(customer.page);
    await mobileAcceptance(customer.page, evidence, "customer");

    const merchant = await login(
      browser,
      "merchant",
      `/workspace/merchant?branchId=${branch}`,
    );
    sessions.push(merchant);
    await expect(
      merchant.page.getByRole("heading", { name: "Branch orders" }),
    ).toBeVisible();
    const orderRow = merchant.page
      .locator(".zone-item")
      .filter({ hasText: orderId.slice(0, 8) })
      .first();
    await expect(orderRow).toBeVisible();
    await orderRow.click();
    await expect(
      merchant.page.getByText(orderId, { exact: true }),
    ).toBeVisible();
    await expect(
      merchant.page.getByRole("button", { name: "Confirm order", exact: true }),
    ).toBeVisible();
    await audit(merchant.page);
    await mobileAcceptance(merchant.page, evidence, "merchant");
    await activate(
      merchant.page.getByRole("button", { name: "Confirm order", exact: true }),
    );
    await expect(
      merchant.page.getByRole("button", {
        name: "Start preparation",
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Confirmed", { timeout: 45_000 });
    await activate(
      merchant.page.getByRole("button", {
        name: "Start preparation",
        exact: true,
      }),
    );
    await expect(
      merchant.page.getByRole("button", {
        name: "Ready for pickup",
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Preparing", { timeout: 45_000 });
    await activate(
      merchant.page.getByRole("button", {
        name: "Ready for pickup",
        exact: true,
      }),
    );
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Ready For Pickup", { timeout: 45_000 });

    const dispatcher = await login(
      browser,
      "dispatcher",
      `/workspace/dispatcher?orderId=${orderId}`,
    );
    sessions.push(dispatcher);
    await expect(
      dispatcher.page.getByRole("heading", { name: "Matching evidence" }),
    ).toBeVisible();
    await audit(dispatcher.page);
    await mobileAcceptance(dispatcher.page, evidence, "dispatcher");
    await activate(
      dispatcher.page.getByRole("button", {
        name: "Start matching",
        exact: true,
      }),
    );
    const eligible = dispatcher.page.getByText("Eligible", { exact: true });
    await expect(eligible).toBeVisible({ timeout: 45_000 });
    await activate(
      dispatcher.page.getByRole("button", { name: "Send offer", exact: true }),
    );
    await expect(
      dispatcher.page.getByText("Offer awaiting response", { exact: true }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Dispatching");

    await expect(
      driver.page.getByText(`Order ${orderId.slice(0, 8).toUpperCase()}`, {
        exact: true,
      }),
    ).toBeVisible({ timeout: 30_000 });
    await expect(
      driver.page.getByRole("button", { name: "Accept offer", exact: true }),
    ).toBeVisible();
    await activate(
      driver.page.getByRole("button", { name: "Accept offer", exact: true }),
    );
    await expect(
      driver.page.getByRole("button", { name: "Confirm pickup", exact: true }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Driver Assigned", { timeout: 45_000 });
    await activate(
      driver.page.getByRole("button", { name: "Confirm pickup", exact: true }),
    );
    await expect(
      driver.page.getByRole("button", { name: "Start transit", exact: true }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Picked Up", { timeout: 45_000 });
    await activate(
      driver.page.getByRole("button", { name: "Start transit", exact: true }),
    );
    await expect(
      driver.page.getByRole("button", {
        name: "Complete delivery",
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("In Transit", { timeout: 45_000 });
    await activate(
      driver.page.getByRole("button", {
        name: "Send next synthetic position",
        exact: true,
      }),
    );
    await expect(
      driver.page.getByText("2 of 5 synthetic positions confirmed."),
    ).toBeVisible();
    await expect(customer.page.getByText(/Position reported/)).toBeVisible();
    await activate(
      driver.page.getByRole("button", {
        name: "Complete delivery",
        exact: true,
      }),
    );
    await expect(
      driver.page.getByText("Delivered", { exact: true }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Delivered", { timeout: 45_000 });
    await expect(
      customer.page.getByText("Tracking ended", { exact: true }),
    ).toBeVisible();
    await expect.poll(() => customer.streamRequests.length).toBe(2);
    expect(customerStreamAttempts).toBe(2);
    await audit(customer.page);

    await customer.page.setViewportSize({ width: 390, height: 844 });
    expect(
      await customer.page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await customer.page.screenshot({
      path: path.join(evidence, "customer-delivered-mobile.png"),
      fullPage: true,
    });
    await customer.page.setViewportSize({ width: 1440, height: 1000 });
    await customer.page.screenshot({
      path: path.join(evidence, "customer-delivered-desktop.png"),
      fullPage: true,
    });
    await driver.page.setViewportSize({ width: 390, height: 844 });
    expect(
      await driver.page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await driver.page.screenshot({
      path: path.join(evidence, "driver-delivered-mobile.png"),
      fullPage: true,
    });
    await driver.page.setViewportSize({ width: 1440, height: 1000 });
    await driver.page.screenshot({
      path: path.join(evidence, "driver-delivered-desktop.png"),
      fullPage: true,
    });

    for (const session of sessions) allErrors.push(...session.errors);
    expect(allErrors).toEqual([]);
    expect(
      await driver.page.evaluate(() =>
        Reflect.get(window, "__fleetflowGpsCalls"),
      ),
    ).toBe(0);
    await activate(
      customer.page.getByRole("button", { name: "Sign out", exact: true }),
    );
    await expect(
      customer.page.getByRole("heading", { name: "You’re signed out" }),
    ).toBeVisible();

    await writeFile(
      path.join(evidence, "vertical-result.json"),
      JSON.stringify(
        {
          seedGeneration: seeded.generation,
          resetGeneration: reset.generation,
          seedResult: seeded.result,
          resetResult: reset.result,
          orderId,
          trackingStreamRequests: customer.streamRequests.length,
          interruptedOrderCreateConfirmed: true,
          keyboardActivationVerified: true,
          deviceGeolocationRequested: false,
          orderCreateAttempts: creationRequests.length,
          syntheticPositionsConfirmed: 2,
          finalCustomerStatus: "DELIVERED",
          logoutConfirmed: true,
        },
        null,
        2,
      ) + "\n",
      { encoding: "utf-8" },
    );
  } finally {
    await Promise.all(sessions.map(({ context }) => context.close()));
  }
});

async function prepareExistingDemo(browser: Browser) {
  const admin = await login(browser, "bootstrap", "/workspace/admin");
  try {
    await admin.page
      .getByRole("button", { name: "Prepare synthetic demo", exact: true })
      .click();
    await admin.page
      .getByLabel("Audit reason")
      .fill("Prepare additional Days 1–3 acceptance checks");
    await admin.page
      .getByRole("dialog")
      .getByRole("button", { name: "Prepare synthetic demo", exact: true })
      .click();
    await expect(
      admin.page.getByText("Synthetic demo prepared successfully."),
    ).toBeVisible();
  } finally {
    await admin.context.close();
  }
}

test("signed merchant edits boundaries by keyboard, recovers a lost create, and checks real coverage", async ({
  browser,
}) => {
  test.setTimeout(180_000);
  const branch = process.env.FLEETFLOW_E2E_BRANCH!;
  const evidence = process.env.FLEETFLOW_E2E_EVIDENCE_DIR!;
  await prepareExistingDemo(browser);
  const merchant = await login(
    browser,
    "merchant",
    `/zones?branchId=${branch}`,
    { failedTiles: true },
  );
  const page = merchant.page;
  try {
    await expect(
      page.getByRole("heading", { name: "Central Demo Zone", exact: true }),
    ).toBeVisible();
    await expect(page.getByText(/Map tiles are unavailable/)).toBeVisible();
    const creates: Array<{ body: string | null; key: string | null }> = [];
    await page.route(
      `**/api/v1/merchant-branches/${branch}/zones`,
      async (route) => {
        if (route.request().method() !== "POST") {
          await route.continue();
          return;
        }
        creates.push({
          body: route.request().postData(),
          key: await route.request().headerValue("Idempotency-Key"),
        });
        if (creates.length === 1) {
          expect((await route.fetch()).status()).toBe(201);
          await route.abort("failed");
        } else await route.continue();
      },
    );
    await page
      .getByRole("button", { name: "Create zone", exact: true })
      .click();
    await page.getByLabel("Zone name").fill("Days 1–3 keyboard boundary");
    for (const [index, [longitude, latitude]] of [
      [31.22, 30.02],
      [31.26, 30.02],
      [31.24, 30.06],
    ].entries()) {
      await page
        .getByRole("button", { name: "Add vertex", exact: true })
        .click();
      await page
        .getByLabel(`Longitude ${index + 1}`, { exact: true })
        .fill(String(longitude));
      await page
        .getByLabel(`Latitude ${index + 1}`, { exact: true })
        .fill(String(latitude));
    }
    await page.getByLabel("Latitude 3", { exact: true }).press("Tab");
    expect(await page.evaluate(() => document.activeElement?.tagName)).not.toBe(
      "BODY",
    );
    await audit(page);
    await mobileAcceptance(page, evidence, "zone-editor");
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Create zone", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "Connection unavailable" }),
    ).toBeVisible();
    await expect(page.getByLabel("Zone name")).toBeDisabled();
    await expect(
      page.getByRole("button", { name: "Close dialog" }),
    ).toBeDisabled();
    await page.getByRole("button", { name: "Try again", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    expect(creates).toHaveLength(2);
    expect(creates[1]).toEqual(creates[0]);
    const zoneButton = page
      .locator(".zone-item")
      .filter({ hasText: "Days 1–3 keyboard boundary" });
    await expect(zoneButton).toHaveCount(1);
    await zoneButton.click();

    await page.route("https://tile.openstreetmap.org/**", (route) =>
      route.fulfill({
        contentType: "image/png",
        body: Buffer.from(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jQ0YAAAAASUVORK5CYII=",
          "base64",
        ),
      }),
    );
    await page
      .getByRole("button", { name: "Retry map tiles", exact: true })
      .click();
    await expect(page.getByText(/Map tiles are unavailable/)).toHaveCount(0);
    await expect
      .poll(() => page.locator(".leaflet-tile-loaded").count())
      .toBeGreaterThan(0);

    await page.getByRole("button", { name: "Edit zone", exact: true }).click();
    await page
      .getByRole("button", { name: "Draw new boundary", exact: true })
      .click();
    const dialog = page.getByRole("dialog");
    await dialog.locator("path.leaflet-interactive").last().click();
    await expect(page.getByLabel("Longitude 1", { exact: true })).toBeVisible();
    for (const [index, [longitude, latitude]] of [
      [31.26, 30.02],
      [31.24, 30.06],
    ].entries()) {
      await page
        .getByRole("button", { name: "Add vertex", exact: true })
        .click();
      await page
        .getByLabel(`Longitude ${index + 2}`, { exact: true })
        .fill(String(longitude));
      await page
        .getByLabel(`Latitude ${index + 2}`, { exact: true })
        .fill(String(latitude));
    }
    await page
      .getByRole("button", { name: "Undo vertex", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Save replacement", exact: true })
      .click();
    await expect(
      page.getByText(/Incomplete drawings cannot be saved/),
    ).toBeVisible();
    await page.getByRole("button", { name: "Close dialog" }).click();
    await expect(
      page.getByRole("button", { name: "Edit zone", exact: true }),
    ).toBeFocused();

    let concurrentWrite = true;
    let zoneId = "";
    await page.route(
      `**/api/v1/merchant-branches/${branch}/zones/*`,
      async (route) => {
        if (route.request().method() === "PUT" && concurrentWrite) {
          concurrentWrite = false;
          zoneId = new URL(route.request().url()).pathname.split("/").at(-1)!;
          const body = route.request().postDataJSON() as Record<
            string,
            unknown
          >;
          expect(
            (
              await route.fetch({
                postData: JSON.stringify({
                  ...body,
                  name: "Concurrent boundary revision",
                }),
              })
            ).status(),
          ).toBe(200);
        }
        await route.continue();
      },
    );
    await page.getByRole("button", { name: "Edit zone", exact: true }).click();
    await page.getByLabel("Zone name").fill("Stale boundary edit");
    await page
      .getByRole("button", { name: "Save replacement", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "This information changed" }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Close dialog" }).click();
    await page.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(
      page.getByRole("heading", {
        name: "Concurrent boundary revision",
        exact: true,
      }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Edit zone", exact: true }).click();
    await page.getByLabel("Zone name").fill("Verified boundary revision");
    await page
      .getByRole("button", { name: "Save replacement", exact: true })
      .click();
    await expect(
      page.getByRole("heading", {
        name: "Verified boundary revision",
        exact: true,
      }),
    ).toBeVisible();

    const changePolicy = async (mode: string) => {
      await page
        .getByRole("button", { name: "Change policy", exact: true })
        .click();
      await page.getByLabel("Delivery coverage mode").selectOption(mode);
      await page
        .getByRole("button", { name: "Confirm policy change", exact: true })
        .click();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await expect(page.getByText("Branch policy updated.")).toBeVisible();
    };
    await changePolicy("ENFORCED");
    await page.getByLabel("Destination latitude").fill("30.04");
    await page.getByLabel("Destination longitude").fill("31.24");
    await page
      .getByRole("button", { name: "Check coverage", exact: true })
      .click();
    await expect(
      page.getByText("Destination is covered", { exact: true }),
    ).toBeVisible();
    await page.getByLabel("Destination latitude").fill("30.5");
    await page.getByLabel("Destination longitude").fill("31.5");
    await page
      .getByRole("button", { name: "Check coverage", exact: true })
      .click();
    await expect(
      page.getByText("Outside active coverage", { exact: true }),
    ).toBeVisible();
    await changePolicy("UNRESTRICTED");
    await expect(page.getByText(/Coverage policy changed/)).toBeVisible();
    await page
      .getByRole("button", { name: "Check coverage", exact: true })
      .click();
    await expect(
      page.getByText("Destination is covered", { exact: true }),
    ).toBeVisible();
    await audit(page);
    await mobileAcceptance(page, evidence, "zones");
    await page.evaluate(() => {
      history.pushState(
        {},
        "",
        "/zones?branchId=11111111-1111-4111-8111-111111111111",
      );
      dispatchEvent(new PopStateEvent("popstate"));
    });
    await expect(
      page.getByRole("heading", { name: "Information not found" }).first(),
    ).toBeVisible();
    expect(merchant.errors).toEqual([]);
    await writeFile(
      path.join(evidence, "zone-result.json"),
      JSON.stringify(
        {
          zoneId,
          createAttempts: creates.length,
          createSameBodyAndKey: true,
          keyboardEditing: true,
          mapDrawingInsideExistingBoundary: true,
          incompleteBoundaryRejected: true,
          concurrentUpdateDenied: true,
          finalVersion: 2,
          enforcedInsideCovered: true,
          enforcedOutsideRejected: true,
          unrestrictedOutsideCovered: true,
          foreignBranchDenied: true,
          foreignBranchDenialStatus: 404,
          tileFailureAndFixtureRecovery: true,
        },
        null,
        2,
      ) + "\n",
    );
  } finally {
    await merchant.context.close();
  }
});

test("signed failure states and dispatcher retry, driver rejection and cancellation recover correctly", async ({
  browser,
}) => {
  test.setTimeout(240_000);
  const branch = process.env.FLEETFLOW_E2E_BRANCH!;
  const evidence = process.env.FLEETFLOW_E2E_EVIDENCE_DIR!;
  await prepareExistingDemo(browser);
  const sessions: Awaited<ReturnType<typeof login>>[] = [];
  try {
    const customer = await login(browser, "customer", "/customer/orders");
    sessions.push(customer);
    await customer.page
      .getByRole("combobox", { name: /^Status/ })
      .selectOption("DELIVERY_FAILED");
    await expect(
      customer.page.getByRole("heading", { name: "No matching orders" }),
    ).toBeVisible();
    await customer.page
      .getByRole("button", { name: "Clear filters", exact: true })
      .click();
    let fault = 0;
    await customer.page.route("**/api/v1/orders?*", async (route) => {
      if (fault++ === 0)
        await route.fulfill({
          status: 429,
          headers: { "Retry-After": "2" },
          json: { code: "RATE_LIMITED" },
        });
      else if (fault === 2)
        await route.fulfill({
          status: 503,
          headers: { "Retry-After": "1" },
          json: { code: "DATABASE_UNAVAILABLE" },
        });
      else await route.continue();
    });
    await customer.page
      .getByRole("button", { name: "Refresh", exact: true })
      .click();
    await expect(
      customer.page.getByRole("button", { name: /Try again in/ }),
    ).toBeDisabled();
    await expect(
      customer.page.getByRole("button", { name: "Try again", exact: true }),
    ).toBeEnabled();
    await customer.page
      .getByRole("button", { name: "Try again", exact: true })
      .click();
    await expect(
      customer.page.getByRole("heading", { name: "Temporarily unavailable" }),
    ).toBeVisible();
    await expect(
      customer.page.getByRole("button", { name: /Try again in/ }),
    ).toBeDisabled();
    await customer.page
      .getByRole("button", { name: "Try again", exact: true })
      .click();
    await expect(customer.page.locator(".error-state")).toHaveCount(0);
    await customer.page.unroute("**/api/v1/orders?*");
    let expired = false;
    await customer.page.route("**/api/v1/orders?*", async (route) => {
      if (!expired) {
        expired = true;
        await route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } });
      } else await route.continue();
    });
    await customer.page
      .getByRole("button", { name: "Refresh", exact: true })
      .click();
    await expect(
      customer.page.getByRole("button", { name: "Continue with workspace SSO" }),
    ).toBeVisible();
    await customer.page
      .getByRole("button", { name: "Continue with workspace SSO" })
      .click();
    await expect(
      customer.page.getByRole("heading", { name: "Your orders" }),
    ).toBeVisible();
    await customer.page.unroute("**/api/v1/orders?*");
    await customer.page
      .getByRole("button", { name: "Create order", exact: true })
      .click();
    for (const [label, value] of [
      ["Merchant branch ID", branch],
      ["Delivery address", "25 Synthetic Recovery Street"],
      ["City", "Cairo"],
      ["Currency", "EGP"],
      ["Latitude", "30.0521"],
      ["Longitude", "31.2422"],
      ["Item description", "Recovery acceptance parcel"],
      ["Quantity", "1"],
      ["Unit amount", "245"],
    ])
      await customer.page.getByLabel(label!, { exact: true }).fill(value!);
    await customer.page
      .getByRole("button", { name: "Create local order", exact: true })
      .click();
    await expect(customer.page).toHaveURL(/\/customer\/orders\/[0-9a-f-]+$/);
    const orderId = new URL(customer.page.url()).pathname.split("/").at(-1)!;
    const driver = await login(browser, "driver", "/workspace/driver");
    sessions.push(driver);
    await expect(
      driver.page.getByRole("button", { name: /^Go (online|offline)$/ }),
    ).toBeVisible();
    if (
      await driver.page
        .getByRole("button", { name: "Go offline", exact: true })
        .isVisible()
    )
      await driver.page
        .getByRole("button", { name: "Go offline", exact: true })
        .click();
    await expect(
      driver.page.getByRole("button", { name: "Go online", exact: true }),
    ).toBeVisible();
    const merchant = await login(
      browser,
      "merchant",
      `/workspace/merchant?branchId=${branch}`,
    );
    sessions.push(merchant);
    await merchant.page
      .locator(".zone-item")
      .filter({ hasText: orderId.slice(0, 8) })
      .click();
    for (const label of [
      "Confirm order",
      "Start preparation",
      "Ready for pickup",
    ])
      await merchant.page
        .getByRole("button", { name: label, exact: true })
        .click();
    const dispatcher = await login(
      browser,
      "dispatcher",
      `/workspace/dispatcher?orderId=${orderId}`,
    );
    sessions.push(dispatcher);
    await dispatcher.page
      .getByRole("button", { name: "Start matching", exact: true })
      .click();
    await expect(
      dispatcher.page.getByText(/No (Nearby|Eligible) Candidate/, {
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Dispatch Failed", { timeout: 45_000 });
    await driver.page
      .getByRole("button", { name: "Go online", exact: true })
      .click();
    await driver.page
      .getByRole("button", { name: "Make available", exact: true })
      .click();
    await driver.page
      .getByRole("button", { name: "Send synthetic pickup", exact: true })
      .click();
    await expect(
      driver.page.getByText("1 of 5 synthetic positions confirmed."),
    ).toBeVisible();
    await dispatcher.page
      .getByRole("button", { name: "Retry matching", exact: true })
      .click();
    await dispatcher.page
      .getByLabel("Reason", { exact: true })
      .fill("Driver is available again after the controlled outage");
    await dispatcher.page
      .getByRole("button", { name: "Confirm action", exact: true })
      .click();
    await expect(
      dispatcher.page.getByText("Ranked", { exact: true }),
    ).toBeVisible();
    await expect(
      driver.page.getByRole("button", { name: "Reject offer", exact: true }),
    ).toBeVisible({ timeout: 45_000 });
    await driver.page
      .getByRole("button", { name: "Reject offer", exact: true })
      .click();
    await expect(
      driver.page.getByText("Offer rejected.", { exact: true }),
    ).toBeVisible();
    await dispatcher.page
      .getByRole("button", { name: "Refresh evidence", exact: true })
      .click();
    await expect(
      dispatcher.page.getByText("Rejected", { exact: true }),
    ).toBeVisible();
    await dispatcher.page
      .getByRole("button", { name: "Cancel before pickup", exact: true })
      .click();
    await dispatcher.page
      .getByLabel("Reason", { exact: true })
      .fill("Finish the controlled rejection and recovery exercise");
    await dispatcher.page
      .getByRole("button", { name: "Confirm action", exact: true })
      .click();
    await expect(
      customer.page.locator(".detail-heading .status-pill"),
    ).toHaveText("Cancelled", { timeout: 45_000 });
    await audit(dispatcher.page);
    await mobileAcceptance(dispatcher.page, evidence, "dispatcher-recovery");
    expect(sessions.flatMap((session) => session.errors)).toEqual([]);
    await writeFile(
      path.join(evidence, "recovery-result.json"),
      JSON.stringify(
        {
          orderId,
          finalStatus: "CANCELLED",
          emptyState: true,
          injectedClientFailureStates: [429, 503, 401],
          reauthenticatedAfter401: true,
          actualNoCandidates: true,
          actualRetryRanked: true,
          actualDriverRejection: true,
          actualDispatcherCancellation: true,
        },
        null,
        2,
      ) + "\n",
    );
  } finally {
    await Promise.all(sessions.map((session) => session.context.close()));
  }
});
