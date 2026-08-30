import assert from "node:assert/strict";
import crypto from "node:crypto";
import { resolve } from "node:path";
import test from "node:test";
import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";
import type { AppConfig } from "./config.js";
import { createTimelineApp } from "./server.js";

const RESOURCE = "http://127.0.0.1/mcp";
const REDIRECT_URI = "https://client.example/callback";
const VERIFIER = "timeline-oauth-verifier-that-is-long-enough-for-pkce-s256-123456789";
const CHALLENGE = crypto.createHash("sha256").update(VERIFIER).digest("base64url");

function config(): AppConfig {
  return {
    port: 0,
    host: "127.0.0.1",
    dbPath: ":memory:",
    labelsPath: resolve("config/app-labels.json"),
    publicBaseUrl: "http://127.0.0.1",
    allowedOrigins: [],
    allowedHosts: ["127.0.0.1"],
    activityGraceSeconds: 120,
    retentionDays: 0,
    mcpHttpEnabled: true,
    mcpHttpPath: "/mcp",
    mcpHttpToken: null,
    readApiToken: "read-token-for-oauth-test",
    allowUnauthenticatedReadApi: false,
    mcpAllowUnauthenticated: false,
    mcpAuthPassword: "oauth-password",
    mcpAuthTokenSecret: "oauth-signing-secret",
    mcpOAuthScope: "timeline:read health:read",
    mcpOAuthAllowLegacyResourceOmission: false,
    deviceTokens: [{
      token: "oauth-test-device-token",
      deviceId: "oauth-phone",
      deviceName: "OAuth Phone",
      platform: "android",
    }],
  };
}

async function start() {
  const app = await createTimelineApp(config(), { logger: false });
  await app.listen({ port: 0, host: "127.0.0.1" });
  const address = app.server.address();
  assert.ok(address && typeof address === "object");
  return { app, baseUrl: `http://127.0.0.1:${address.port}` };
}

async function registerClient(baseUrl: string): Promise<string> {
  const response = await fetch(`${baseUrl}/oauth/register`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ redirect_uris: [REDIRECT_URI], client_name: "Timeline test client" }),
  });
  assert.equal(response.status, 201);
  const body = await response.json() as { client_id: string };
  return body.client_id;
}

async function authorize(baseUrl: string, clientId: string): Promise<string> {
  const fields = {
    response_type: "code",
    client_id: clientId,
    redirect_uri: REDIRECT_URI,
    code_challenge: CHALLENGE,
    code_challenge_method: "S256",
    resource: RESOURCE,
    scope: "timeline:read health:read",
    state: "state-123",
  };
  const page = await fetch(`${baseUrl}/oauth/authorize?${new URLSearchParams(fields)}`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /授权 AI 读取 Hibi 时间线/);

  const response = await fetch(`${baseUrl}/oauth/authorize`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ ...fields, password: "oauth-password" }),
    redirect: "manual",
  });
  assert.equal(response.status, 302);
  const location = response.headers.get("location");
  assert.ok(location);
  const redirect = new URL(location);
  assert.equal(redirect.origin + redirect.pathname, REDIRECT_URI);
  assert.equal(redirect.searchParams.get("state"), "state-123");
  assert.equal(redirect.searchParams.get("iss"), "http://127.0.0.1");
  const code = redirect.searchParams.get("code");
  assert.ok(code);
  return code;
}

async function exchange(baseUrl: string, clientId: string, code: string, resource = RESOURCE) {
  return fetch(`${baseUrl}/oauth/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: clientId,
      redirect_uri: REDIRECT_URI,
      code_verifier: VERIFIER,
      resource,
      code,
    }),
  });
}

test("OAuth binds exact redirect, S256 PKCE, resource audience, and one-time codes", async () => {
  const { app, baseUrl } = await start();
  try {
    const invalidRegistration = await fetch(`${baseUrl}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ redirect_uris: ["http://evil.example/callback"] }),
    });
    assert.equal(invalidRegistration.status, 400);

    const clientId = await registerClient(baseUrl);
    const wrongRedirect = await fetch(`${baseUrl}/oauth/authorize?${new URLSearchParams({
      response_type: "code",
      client_id: clientId,
      redirect_uri: "https://client.example/not-registered",
      code_challenge: CHALLENGE,
      code_challenge_method: "S256",
      resource: RESOURCE,
      scope: "timeline:read",
    })}`);
    assert.equal(wrongRedirect.status, 400);

    const code = await authorize(baseUrl, clientId);
    const tokenResponse = await exchange(baseUrl, clientId, code);
    assert.equal(tokenResponse.status, 200);
    const tokenBody = await tokenResponse.json() as { access_token: string; scope: string };
    assert.equal(tokenBody.scope, "timeline:read health:read");
    const payload = tokenBody.access_token.split(".")[1];
    assert.ok(payload);
    const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as {
      aud: string; client_id: string; scope: string;
    };
    assert.equal(claims.aud, RESOURCE);
    assert.equal(claims.client_id, clientId);

    const replay = await exchange(baseUrl, clientId, code);
    assert.equal(replay.status, 400);
    assert.equal((await replay.json() as { error: string }).error, "invalid_grant");

    const client = new Client(
      { name: "timeline-oauth-client", version: "0.0.0" },
      { versionNegotiation: { mode: "auto" } },
    );
    try {
      await client.connect(new StreamableHTTPClientTransport(new URL(`${baseUrl}/mcp`), {
        authProvider: { token: async () => tokenBody.access_token },
      }));
      assert.equal(client.getNegotiatedProtocolVersion(), "2026-07-28");
      assert.equal((await client.listTools()).tools.length, 5);
    } finally {
      await client.close();
    }

    const consumedCode = await authorize(baseUrl, clientId);
    const wrongResource = await exchange(baseUrl, clientId, consumedCode, "https://other.example/mcp");
    assert.equal(wrongResource.status, 400);
    assert.equal((await wrongResource.json() as { error: string }).error, "invalid_target");
    const cannotRetry = await exchange(baseUrl, clientId, consumedCode);
    assert.equal((await cannotRetry.json() as { error: string }).error, "invalid_grant");
  } finally {
    await app.close();
  }
});
