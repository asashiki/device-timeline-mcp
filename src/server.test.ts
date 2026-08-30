import assert from "node:assert/strict";
import http from "node:http";
import { resolve } from "node:path";
import test from "node:test";
import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";
import type { AppConfig } from "./config.js";
import { createTimelineApp } from "./server.js";

const DEVICE_TOKEN = "device-secret-token";
const READ_TOKEN = "read-secret-token";
const MCP_TOKEN = "mcp-secret-token";

function config(overrides: Partial<AppConfig> = {}): AppConfig {
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
    mcpHttpToken: MCP_TOKEN,
    readApiToken: READ_TOKEN,
    allowUnauthenticatedReadApi: false,
    mcpAllowUnauthenticated: false,
    mcpAuthPassword: "",
    mcpAuthTokenSecret: "",
    mcpOAuthScope: "timeline:read health:read",
    mcpOAuthAllowLegacyResourceOmission: false,
    deviceTokens: [{
      token: DEVICE_TOKEN,
      deviceId: "test-phone",
      deviceName: "Test Phone",
      platform: "android",
    }],
    ...overrides,
  };
}

async function start(overrides: Partial<AppConfig> = {}) {
  const app = await createTimelineApp(config(overrides), { logger: false });
  await app.listen({ port: 0, host: "127.0.0.1" });
  const address = app.server.address();
  assert.ok(address && typeof address === "object");
  return { app, baseUrl: `http://127.0.0.1:${address.port}` };
}

test("device ingest, REST read, and MCP use credentials from separate trust domains", async () => {
  const { app, baseUrl } = await start();
  try {
    const noReadToken = await fetch(`${baseUrl}/api/devices/current`);
    assert.equal(noReadToken.status, 401);

    const deviceCannotRead = await fetch(`${baseUrl}/api/devices/current`, {
      headers: { authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(deviceCannotRead.status, 401);

    const mcpCannotRead = await fetch(`${baseUrl}/api/devices/current`, {
      headers: { authorization: `Bearer ${MCP_TOKEN}` },
    });
    assert.equal(mcpCannotRead.status, 401);

    const readCannotReport = await fetch(`${baseUrl}/api/devices/report`, {
      method: "POST",
      headers: { authorization: `Bearer ${READ_TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify({ appId: "com.example.read-token" }),
    });
    assert.equal(readCannotReport.status, 401);

    const report = await fetch(`${baseUrl}/api/devices/report`, {
      method: "POST",
      headers: { authorization: `Bearer ${DEVICE_TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify({ appId: "com.example.music", windowTitle: "Now playing" }),
    });
    assert.equal(report.status, 202);

    const read = await fetch(`${baseUrl}/api/devices/current`, {
      headers: { authorization: `Bearer ${READ_TOKEN}` },
    });
    assert.equal(read.status, 200);
    const current = await read.json() as { devices: Array<{ deviceId: string; appId: string }> };
    assert.deepEqual(current.devices.map((device) => device.deviceId), ["test-phone"]);
    assert.equal(current.devices[0]?.appId, "com.example.music");

    const unauthorizedMcp = await fetch(`${baseUrl}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
    });
    assert.equal(unauthorizedMcp.status, 401);
  } finally {
    await app.close();
  }
});

test("one authenticated endpoint serves MCP 2026 and legacy clients", async () => {
  const { app, baseUrl } = await start();
  const authProvider = { token: async () => MCP_TOKEN };
  const modern = new Client(
    { name: "timeline-modern-test", version: "0.0.0" },
    { versionNegotiation: { mode: "auto" } },
  );
  try {
    await modern.connect(new StreamableHTTPClientTransport(new URL(`${baseUrl}/mcp`), { authProvider }));
    assert.equal(modern.getNegotiatedProtocolVersion(), "2026-07-28");
    const tools = await modern.listTools();
    assert.deepEqual(
      tools.tools.map((tool) => tool.name).sort(),
      ["device_activity_summary", "device_status", "device_timeline", "health_records", "health_summary"],
    );
    for (const tool of tools.tools) {
      assert.equal(tool.annotations?.readOnlyHint, true);
      assert.equal(tool.annotations?.destructiveHint, false);
      assert.equal(tool.annotations?.idempotentHint, true);
      assert.equal(tool.annotations?.openWorldHint, false);
    }

    const legacy = new Client({ name: "timeline-legacy-test", version: "0.0.0" });
    try {
      await legacy.connect(new StreamableHTTPClientTransport(new URL(`${baseUrl}/mcp`), { authProvider }));
      assert.notEqual(legacy.getNegotiatedProtocolVersion(), "2026-07-28");
      assert.equal((await legacy.listTools()).tools.length, 5);
    } finally {
      await legacy.close();
    }
  } finally {
    await modern.close();
    await app.close();
  }
});

test("MCP transport rejects an unexpected Host before bearer processing", async () => {
  const { app, baseUrl } = await start();
  try {
    const status = await new Promise<number>((resolveStatus, reject) => {
      const request = http.request(`${baseUrl}/mcp`, {
        method: "POST",
        headers: {
          Host: "evil.example",
          Authorization: `Bearer ${MCP_TOKEN}`,
          "Content-Type": "application/json",
        },
      }, (response) => {
        response.resume();
        response.on("end", () => resolveStatus(response.statusCode ?? 0));
      });
      request.on("error", reject);
      request.end(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }));
    });
    assert.equal(status, 403);
  } finally {
    await app.close();
  }
});
