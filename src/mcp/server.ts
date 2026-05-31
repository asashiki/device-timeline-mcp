import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createMcpServer } from "./tools.js";

// Stdio MCP entrypoint. Run it on the machine where your MCP client
// (Claude Desktop / Claude Code) lives; point it at wherever the collector is
// deployed via MCP_API_BASE. For claude.ai (remote/web), use the collector's
// built-in HTTP transport at `/mcp` instead — see the README.
const API_BASE = (process.env.MCP_API_BASE ?? "http://localhost:4200").replace(/\/$/, "");

const server = createMcpServer(API_BASE);
await server.connect(new StdioServerTransport());
console.error("[device-timeline-mcp] MCP stdio server ready, API base =", API_BASE);
