import { createHash, generateKeyPairSync, sign } from "node:crypto";
import type { Page } from "@playwright/test";

const authority = "http://127.0.0.1:8081/realms/fleetflow";
const { privateKey, publicKey } = generateKeyPairSync("rsa", {
  modulusLength: 2048,
});
const jwk = publicKey.export({ format: "jwk" });
const keyId = "fleetflow-e2e-transient";
let transaction: {
  nonce: string;
  verifierChallenge: string;
  redirectUri: string;
  state: string;
} | null = null;

function makeIdToken(nonce: string): string {
  const header = Buffer.from(
    JSON.stringify({ alg: "RS256", typ: "JWT", kid: keyId }),
  ).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(
    JSON.stringify({
      iss: authority,
      aud: "fleetflow-web",
      sub: "e2e-customer",
      iat: now,
      exp: now + 3600,
      nonce,
    }),
  ).toString("base64url");
  const signingInput = `${header}.${payload}`;
  const signature = sign(
    "RSA-SHA256",
    Buffer.from(signingInput),
    privateKey,
  ).toString("base64url");
  return `${signingInput}.${signature}`;
}

export async function installOidc(page: Page) {
  transaction = null;
  await page.route(`${authority}/.well-known/openid-configuration`, (route) =>
    route.fulfill({
      json: {
        issuer: authority,
        authorization_endpoint: `${authority}/protocol/openid-connect/auth`,
        token_endpoint: `${authority}/protocol/openid-connect/token`,
        jwks_uri: `${authority}/protocol/openid-connect/certs`,
        end_session_endpoint: `${authority}/protocol/openid-connect/logout`,
      },
    }),
  );
  await page.route(`${authority}/protocol/openid-connect/certs`, (route) =>
    route.fulfill({
      json: {
        keys: [{ ...jwk, kid: keyId, use: "sig", alg: "RS256" }],
      },
    }),
  );
  await page.route(
    `${authority}/protocol/openid-connect/auth**`,
    async (route) => {
      const auth = new URL(route.request().url());
      transaction = {
        nonce: auth.searchParams.get("nonce") ?? "",
        verifierChallenge: auth.searchParams.get("code_challenge") ?? "",
        redirectUri: auth.searchParams.get("redirect_uri") ?? "",
        state: auth.searchParams.get("state") ?? "",
      };
      const callback = new URL(transaction.redirectUri);
      callback.searchParams.set("code", "one-use-authorization-code");
      callback.searchParams.set("state", transaction.state);
      await route.fulfill({
        contentType: "text/html",
        body: `<script>location.replace(${JSON.stringify(callback.toString())})</script>`,
      });
    },
  );
  await page.route(
    `${authority}/protocol/openid-connect/token`,
    async (route) => {
      const values = new URLSearchParams(route.request().postData() ?? "");
      const verifier = values.get("code_verifier") ?? "";
      const computed = createHash("sha256")
        .update(verifier)
        .digest("base64url");
      if (!transaction || computed !== transaction.verifierChallenge) {
        await route.fulfill({ status: 400, json: { error: "invalid_grant" } });
        return;
      }
      await route.fulfill({
        json: {
          access_token: "e2e-memory-access-token",
          id_token: makeIdToken(transaction.nonce),
          token_type: "Bearer",
          expires_in: 3600,
          scope: "openid profile",
        },
      });
    },
  );
  await page.route(
    `${authority}/protocol/openid-connect/logout**`,
    async (route) => {
      const logout = new URL(route.request().url());
      const redirect =
        logout.searchParams.get("post_logout_redirect_uri") ??
        "/auth/signed-out";
      await route.fulfill({
        contentType: "text/html",
        body: `<script>location.replace(${JSON.stringify(redirect)})</script>`,
      });
    },
  );
}
