import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import path from "node:path";
import fs from "node:fs/promises";
import { ids, productFixture } from "./fixtures/product";
const evidence = path.resolve("test-results", "fixture", "evidence");
async function capture(page: Page, name: string) {
  await expect(page.locator(".skeleton-row")).toHaveCount(0);
  await fs.mkdir(evidence, { recursive: true });
  await page.screenshot({
    path: path.join(evidence, name + ".png"),
    fullPage: true,
  });
}
async function destination(page: Page, name: string) {
  await page
    .getByRole("navigation", { name: "Main navigation" })
    .getByRole("link", { name, exact: true })
    .click();
  const headings: Record<string, string> = {
    Orders: "Your orders",
    Tracking: "Live tracking",
    Merchant: "Merchant workspace",
    "Delivery zones": "Delivery zones",
    "Driver workspace": "Driver workspace",
    Dispatch: "Dispatch",
    Activity: "Activity",
    Operations: "Operations",
  };
  await expect(
    page.getByRole("heading", { name: headings[name], exact: true, level: 1 }),
  ).toBeVisible();
}
async function context(page: Page, label: string, id: string) {
  await page.getByLabel(label, { exact: true }).fill(id);
  await page.getByRole("button", { name: "Open workspace" }).click();
}
test("complete product navigation, real-contract actions, responsive layouts and accessibility", async ({
  page,
}) => {
  test.setTimeout(120000);
  page.setDefaultTimeout(10000);
  const fixture = await productFixture(page);
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto("/login");
  await capture(page, "login-desktop");
  await page.getByRole("button", { name: "Continue with workspace SSO" }).click();
  await expect(
    page.getByRole("heading", { name: "Command Center" }),
  ).toBeVisible();
  await capture(page, "command-center-desktop");
  const audits: Array<{ screen: string; violations: unknown[] }> = [];
  const audit = async (screen: string) => {
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    audits.push({ screen, violations: results.violations });
  };
  await audit("command-center");
  await page.keyboard.press("Control+k");
  await expect(page.getByRole("dialog")).toBeVisible();
  await page
    .getByRole("textbox", { name: "Search pages or enter an order ID" })
    .fill("orders");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Orders", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Your orders" }),
  ).toBeVisible();
  await page.getByRole("combobox", { name: /^Status/ }).selectOption("CREATED");
  await expect(
    page.getByRole("link", { name: /Zamalek Dispatch/ }),
  ).toBeVisible();
  expect(fixture.requests.some((r) => r.path.includes("status=CREATED"))).toBe(
    true,
  );
  await page.getByRole("combobox", { name: /^Status/ }).selectOption("");
  await page.getByLabel("Search this page").fill("Downtown");
  await expect(page.getByRole("link", { name: /Maadi Parcel/ })).toHaveCount(0);
  await page.getByLabel("Search this page").fill("");
  await capture(page, "orders-desktop");
  await audit("orders");
  await page.getByRole("link", { name: /Zamalek Dispatch/ }).click();
  await expect(
    page.getByRole("heading", { name: "Order received" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Cancel order", exact: true }).click();
  await page
    .getByLabel("Reason for cancellation")
    .fill("Synthetic review cancellation");
  await page.getByRole("button", { name: "Confirm cancellation" }).click();
  await expect(page.locator(".detail-heading .status-pill")).toHaveText(
    "Cancelled",
  );
  expect(
    fixture.requests.find((r) => r.path.endsWith("/cancel"))?.body,
  ).toEqual({ reason: "Synthetic review cancellation" });
  await destination(page, "Orders");
  await page.getByRole("link", { name: /Downtown Branch/ }).click();
  await expect(
    page.getByRole("heading", { name: "Downtown Branch" }),
  ).toBeVisible();
  await capture(page, "order-detail-desktop");
  await audit("detail");
  await page.getByRole("button", { name: "Order technical details" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Order technical details" }),
  ).toBeFocused();
  await destination(page, "Tracking");
  await expect(
    page.getByRole("heading", { name: "Live tracking" }),
  ).toBeVisible();
  await capture(page, "tracking-desktop");
  await audit("tracking");
  await destination(page, "Dispatch");
  await context(page, "Order ID", ids.order);
  await expect(
    page.getByText("Rank 1 · Score 0.860", { exact: false }),
  ).toBeVisible();
  await capture(page, "dispatch-desktop");
  await page
    .getByRole("button", { name: "Send offer", exact: true })
    .first()
    .click();
  await expect(page.getByText("Offer awaiting response")).toBeVisible();
  await audit("dispatch");
  await destination(page, "Driver workspace");
  await expect(
    page.getByRole("button", { name: "Accept offer" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Make available" }).click();
  await page.getByRole("button", { name: "Accept offer" }).click();
  await page.getByRole("button", { name: "Confirm pickup" }).click();
  await expect(
    page.getByRole("button", { name: "Start transit" }),
  ).toBeVisible();
  await capture(page, "driver-desktop");
  await audit("driver");
  await destination(page, "Merchant");
  await context(page, "Merchant branch ID", ids.branch);
  await expect(
    page.getByRole("heading", { name: "Branch orders" }),
  ).toBeVisible();
  await capture(page, "merchant-desktop");
  await audit("merchant");
  await destination(page, "Delivery zones");
  await context(page, "Merchant branch ID", ids.branch);
  await expect(
    page.getByRole("heading", { name: "Central delivery zone" }),
  ).toBeVisible();
  await capture(page, "zones-desktop");
  await page.getByRole("button", { name: "Edit zone", exact: true }).click();
  await page.getByLabel("Zone name").fill("Central zone updated");
  await page.getByRole("button", { name: "Save replacement" }).click();
  await expect(
    page.getByRole("heading", { name: "Central zone updated" }),
  ).toBeVisible();
  const replacement = fixture.requests.find(
    (r) => r.method === "PUT" && r.path.includes("/zones/"),
  );
  expect(replacement?.body).toMatchObject({
    expectedVersion: 12,
    name: "Central zone updated",
  });
  await audit("zones");
  await destination(page, "Activity");
  await expect(
    page.getByRole("heading", { name: "Your notifications" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Pause updates" }).click();
  await expect(page.getByText("Updates paused")).toBeVisible();
  await page.getByRole("button", { name: "Event details" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await capture(page, "activity-desktop");
  await audit("activity");
  await destination(page, "Operations");
  await page.getByRole("button", { name: "Inspect and replay" }).click();
  await page.getByLabel("Audit reason").fill("Synthetic operator recovery");
  await page.getByRole("button", { name: "Confirm event replay" }).click();
  await expect(
    page.getByText("Replay accepted. Publication continues asynchronously."),
  ).toBeVisible();
  const replay = fixture.requests.find((r) => r.path.endsWith("/replay"));
  expect(replay?.key).toBeTruthy();
  await capture(page, "operations-desktop");
  await audit("operations");
  for (const [name, link] of [
    ["orders", "Orders"],
    ["tracking", "Tracking"],
    ["dispatch", "Dispatch"],
    ["zones", "Delivery zones"],
    ["activity", "Activity"],
    ["operations", "Operations"],
  ] as const) {
    await destination(page, link);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await capture(page, name + "-mobile");
    await page.setViewportSize({ width: 768, height: 1024 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await capture(page, name + "-tablet");
    await page.setViewportSize({ width: 1440, height: 960 });
  }
  await fs.writeFile(
    path.join(evidence, "accessibility.json"),
    JSON.stringify(audits, null, 2),
  );
  expect(audits.flatMap((a) => a.violations)).toEqual([]);
  expect(errors).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "You’re signed out" }),
  ).toBeVisible();
});
test("customer role cannot open dispatcher workspace", async ({ page }) => {
  const fixture = await productFixture(page, ["CUSTOMER"]);
  await page.goto("/login");
  await page.getByRole("button", { name: "Continue with workspace SSO" }).click();
  await expect(
    page.getByRole("heading", { name: "Command Center" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Dispatch", exact: true }),
  ).toHaveCount(0);
  await page.evaluate(() => {
    history.pushState({}, "", "/workspace/dispatcher");
    dispatchEvent(new PopStateEvent("popstate"));
  });
  await expect(
    page.getByRole("heading", { name: "Access unavailable" }),
  ).toBeVisible();
  expect(fixture.requests.some((r) => r.path.includes("/dispatch/"))).toBe(
    false,
  );
});
