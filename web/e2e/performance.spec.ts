import { expect, test } from "@playwright/test";
import fs from "node:fs/promises";
import path from "node:path";
import { productFixture } from "./fixtures/product";
test("production frontend profile, lazy maps and reduced motion", async ({
  page,
}) => {
  await productFixture(page, ["CUSTOMER"]);
  await page.addInitScript(() => {
    const stats = { cls: 0, lcp: 0, longTasks: [] as number[] };
    Object.assign(window, { fleetflowPerformance: stats });
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        const shift = entry as PerformanceEntry & {
          hadRecentInput: boolean;
          value: number;
        };
        if (!shift.hadRecentInput) stats.cls += shift.value;
      }
    }).observe({ type: "layout-shift", buffered: true });
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) stats.lcp = entry.startTime;
    }).observe({ type: "largest-contentful-paint", buffered: true });
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries())
        stats.longTasks.push(entry.duration);
    }).observe({ type: "longtask", buffered: true });
  });
  const devtools = await page.context().newCDPSession(page);
  await devtools.send("Performance.enable");
  await page.goto("/login");
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  await expect(
    page.getByRole("heading", { name: "Command Center" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Downtown Branch/ }),
  ).toBeVisible();
  await expect(page.locator(".skeleton-row")).toHaveCount(0);
  const first = await page.evaluate(() => ({
    stats: (
      window as unknown as {
        fleetflowPerformance: { cls: number; lcp: number; longTasks: number[] };
      }
    ).fleetflowPerformance,
    resources: performance.getEntriesByType("resource").map((e) => e.name),
  }));
  expect(first.resources.some((r) => r.includes("MapRenderer"))).toBe(false);
  expect(first.stats.cls).toBeLessThan(0.1);
  const { metrics } = await devtools.send("Performance.getMetrics");
  await page.getByRole("link", { name: "Tracking", exact: true }).click();
  await expect(page.locator(".map-canvas")).toBeVisible();
  const mapLoaded = await page.evaluate(() =>
    performance
      .getEntriesByType("resource")
      .some((e) => e.name.includes("MapRenderer")),
  );
  expect(mapLoaded).toBe(true);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.getByRole("link", { name: "Orders", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Your orders" }),
  ).toBeVisible();
  expect(
    await page
      .locator(".page-wrap")
      .evaluate((e) => getComputedStyle(e).animationName),
  ).toBe("none");
  const report = {
    environment:
      "Chromium / local production Vite preview / intercepted synthetic API and neutral tile fixtures; one sample, not backend capacity",
    ...first.stats,
    metrics: metrics.filter((m: { name: string }) =>
      [
        "JSHeapUsedSize",
        "TaskDuration",
        "ScriptDuration",
        "LayoutCount",
        "RecalcStyleCount",
      ].includes(m.name),
    ),
    initialMapLoaded: false,
    mapLoadedAfterNavigation: mapLoaded,
    reducedMotion: "PASS",
  };
  const out = path.resolve(
    "..",
    "docs/design/evidence/implementation-v2/performance.json",
  );
  await fs.mkdir(path.dirname(out), { recursive: true });
  await fs.writeFile(out, JSON.stringify(report, null, 2));
});
