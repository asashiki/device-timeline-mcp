import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { randomUUID } from "node:crypto";
import { z } from "zod";

export function createWristMcp(base: string, token: string) {
  const server = new McpServer({ name: "hibi-wrist", version: "1.0.0" });
  async function call(path: string, body?: Record<string, unknown>) {
    const response = await fetch(base + "/api/agent/wrist" + path, {
      method: body ? "POST" : "GET",
      redirect: "error",
      headers: { authorization: "Bearer " + token, "content-type": "application/json" },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(8000),
    });
    const result = await response.json() as Record<string, unknown>;
    return { content: [{ type: "text" as const, text: JSON.stringify(result) }], isError: !response.ok };
  }
  server.tool("band_status", "Read the band's connection, latest battery sample and supported command catalogue.",
    {}, {readOnlyHint:true}, () => call(""));
  server.tool("band_receipt", "Check a queued reminder or command. Sent means dispatched by the phone; confirmed requires a reply from the band.",
    {id:z.string().uuid()}, {readOnlyHint:true}, ({id})=>call("/"+id));
  const common = { at:z.string().datetime({offset:true}).optional().describe("Optional scheduled time with timezone"), requestId:z.string().uuid().optional().describe("Reuse on retries") };
  server.tool("band_buzz", "Briefly vibrate the owner's paired band, now or at a specified time.",
    {...common, seconds:z.number().int().min(1).max(6).default(1)}, {readOnlyHint:false,destructiveHint:false},
    ({at,requestId,seconds})=>call("",{action:"buzz",args:{seconds},requestId:requestId??randomUUID(),deliverAt:at}));
  server.tool("band_notify","Display a short reminder on the owner's band, now or at a specified time.",
    {...common,title:z.string().max(64).default("Hibi"),text:z.string().min(1).max(240)}, {readOnlyHint:false,destructiveHint:false},
    ({at,requestId,title,text})=>call("",{action:"notify",args:{title,text},requestId:requestId??randomUUID(),deliverAt:at}));
  server.tool("band_wake_at", "Set a native one-time wrist alarm. at is the time to wake, including offset, at whole-minute precision. Check band_receipt: confirmed means the alarm was read back from the band; queued is not armed yet.",
    {at:z.string().datetime({offset:true}), requestId:common.requestId}, {readOnlyHint:false,destructiveHint:false},
    ({at,requestId})=>call("",{action:"wake-at",args:{at},requestId:requestId??randomUUID()}));
  return server;
}
