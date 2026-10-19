/* global document, getComputedStyle, console, process */
import { chromium } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { createServer } from "vite";

const output = path.resolve("../docs/design/evidence/login-redesign");
await mkdir(output, { recursive: true });
const vite = await createServer({ server: { host: "127.0.0.1", port: 0 }, logLevel: "error" });
await vite.listen();
const address = vite.httpServer?.address();
if (!address || typeof address === "string") throw new Error("Visual test server did not start");
const loginUrl = `http://127.0.0.1:${address.port}/login`;
const browser = await chromium.launch({ headless: true });
let failed = false;
const sizes = [
  [1440, 900], [1366, 768], [1280, 800], [1073, 640], [1024, 768],
  [768, 1024], [390, 844], [360, 800],
];

for (const [width, height] of sizes) {
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => {
    if (message.type() === "error") errors.push(message.text());
  });
  await page.goto(loginUrl, { waitUntil: "networkidle" });
  const metrics = await page.evaluate(() => {
    const box = selector => {
      const rect = document.querySelector(selector).getBoundingClientRect();
      return { x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height) };
    };
    const heading = document.querySelector(".login-story h1");
    return {
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
      scrollHeight: document.documentElement.scrollHeight,
      clientHeight: document.documentElement.clientHeight,
      font: getComputedStyle(heading).fontFamily,
      heading: box(".login-story h1"),
      story: box(".login-story"),
      panel: box(".login-panel"),
      button: box(".login-submit"),
    };
  });
  const violations = width === 1440 || width === 390
    ? (await new AxeBuilder({ page }).analyze()).violations.map(({ id, nodes }) => ({
      id,
      targets: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })),
    }))
    : [];
  const screenshot = path.join(output, `login-${width}x${height}.png`);
  await page.screenshot({ path: screenshot, fullPage: true });
  const valid = errors.length === 0
    && violations.length === 0
    && metrics.scrollWidth === metrics.clientWidth
    && metrics.scrollHeight <= metrics.clientHeight + 1
    && metrics.button.y + metrics.button.height <= height;
  if (!valid) failed = true;
  console.log(JSON.stringify({ size: `${width}x${height}`, screenshot, valid, metrics, violations, errors }));
  await context.close();
}
await browser.close();
await vite.close();
if (failed) process.exitCode = 1;
