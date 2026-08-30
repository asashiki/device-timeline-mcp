import crypto from "node:crypto";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";

const ACCESS_TOKEN_TTL_SECONDS = 24 * 60 * 60;
const AUTHORIZATION_CODE_TTL_MS = 5 * 60 * 1000;

type OAuthClient = {
  redirectUris: string[];
  clientName: string;
  issuedAt: number;
};

type AuthorizationCode = {
  clientId: string;
  redirectUri: string;
  codeChallenge: string;
  resource: string;
  scope: string;
  expiresAt: number;
};

type AccessClaims = {
  iss: string;
  aud: string;
  client_id: string;
  scope: string;
  iat: number;
  exp: number;
  jti: string;
};

export interface OAuthOptions {
  baseUrl: string;
  resourceUrl: string;
  password: string;
  tokenSecret: string;
  scope: string;
  allowLegacyResourceOmission: boolean;
}

function randomToken(bytes = 24): string {
  return crypto.randomBytes(bytes).toString("base64url");
}

function htmlEscape(value: string): string {
  return value.replace(/[&<>"']/g, (character) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character] ?? character,
  );
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function validRedirectUri(value: string): boolean {
  if (value.length > 2048) return false;
  try {
    const url = new URL(value);
    if (url.hash || url.username || url.password) return false;
    if (url.protocol === "https:") return true;
    return url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  } catch {
    return false;
  }
}

function normalizeResource(value: string): string | null {
  try {
    const url = new URL(value);
    if (!(["http:", "https:"] as string[]).includes(url.protocol)) return null;
    if (url.username || url.password || url.search || url.hash) return null;
    return url.href.replace(/\/$/, "");
  } catch {
    return null;
  }
}

function protectedResourceMetadataPath(resourceUrl: string): string {
  const path = new URL(resourceUrl).pathname.replace(/\/$/, "");
  return `/.well-known/oauth-protected-resource${path === "" || path === "/" ? "" : path}`;
}

export function safeEqualText(actual: string, expected: string): boolean {
  const a = crypto.createHash("sha256").update(actual).digest();
  const b = crypto.createHash("sha256").update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

function verifyPkceS256(verifier: string, challenge: string): boolean {
  if (verifier.length < 43 || verifier.length > 128) return false;
  const computed = crypto.createHash("sha256").update(verifier).digest("base64url");
  return safeEqualText(computed, challenge);
}

function tokenKey(secret: string): Buffer {
  return crypto.createHash("sha256").update(`device-timeline-access-token\0${secret}`).digest();
}

function signPayload(payload: string, key: Buffer): string {
  return crypto.createHmac("sha256", key).update(payload).digest("base64url");
}

function issueAccessToken(
  options: OAuthOptions,
  key: Buffer,
  clientId: string,
  resource: string,
  scope: string,
): string {
  const now = Math.floor(Date.now() / 1000);
  const claims: AccessClaims = {
    iss: options.baseUrl,
    aud: resource,
    client_id: clientId,
    scope,
    iat: now,
    exp: now + ACCESS_TOKEN_TTL_SECONDS,
    jti: randomToken(16),
  };
  const payload = Buffer.from(JSON.stringify(claims), "utf8").toString("base64url");
  return `mcp.${payload}.${signPayload(payload, key)}`;
}

function verifyAccessToken(token: string, options: OAuthOptions, key: Buffer): AccessClaims | null {
  const parts = token.split(".");
  if (parts.length !== 3 || parts[0] !== "mcp") return null;
  const payload = parts[1];
  const signature = parts[2];
  if (!payload || !signature || !safeEqualText(signature, signPayload(payload, key))) return null;
  try {
    const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as Partial<AccessClaims>;
    const now = Math.floor(Date.now() / 1000);
    if (
      claims.iss !== options.baseUrl ||
      claims.aud !== options.resourceUrl ||
      typeof claims.client_id !== "string" ||
      typeof claims.scope !== "string" ||
      typeof claims.iat !== "number" ||
      typeof claims.exp !== "number" ||
      claims.exp <= now ||
      claims.iat > now + 60 ||
      typeof claims.jti !== "string"
    ) return null;
    const supported = new Set(options.scope.split(/\s+/).filter(Boolean));
    if (!claims.scope.split(/\s+/).filter(Boolean).every((scope) => supported.has(scope))) return null;
    return claims as AccessClaims;
  } catch {
    return null;
  }
}

function authorizationPage(fields: Record<string, string>, error?: string): string {
  const hidden = Object.entries(fields)
    .map(([name, value]) => `<input type="hidden" name="${htmlEscape(name)}" value="${htmlEscape(value)}">`)
    .join("");
  return `<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hibi 日々 · MCP 授权</title>
<style>*{box-sizing:border-box}body{min-height:100vh;display:grid;place-items:center;margin:0;padding:20px;background:#0b0d14;color:#e8e2d4;font-family:system-ui,-apple-system,"PingFang SC",sans-serif}.card{width:min(420px,100%);padding:30px;border:1px solid #2d303b;border-radius:16px;background:#171923;box-shadow:0 20px 60px #0008}h1{font-size:19px;margin:0 0 7px}p{color:#9a9ca5;font-size:13px;line-height:1.6}.scope{padding:10px;border-radius:8px;background:#22252f;color:#a9b8ef;font:12px ui-monospace,monospace;word-break:break-all}.err{padding:9px;border-radius:8px;background:#451f2b;color:#ffb8c9;font-size:13px}label{display:block;margin:18px 0 7px;font-size:13px}input[type=password]{width:100%;padding:11px;border:1px solid #3a3d49;border-radius:8px;background:#0f1119;color:#fff}button{width:100%;margin-top:14px;padding:11px;border:0;border-radius:8px;background:#d89870;color:#17100c;font-weight:700;cursor:pointer}</style>
</head><body><main class="card"><h1>授权 AI 读取 Hibi 时间线</h1><p>仅授予只读的设备活动与健康数据 MCP 权限。设备上报凭据和控制台 Token 不会共享给 AI 客户端。</p>${error ? `<div class="err">${htmlEscape(error)}</div>` : ""}<div class="scope">resource: ${htmlEscape(fields.resource ?? "")}<br>scope: ${htmlEscape(fields.scope ?? "")}</div><form method="POST" action="/oauth/authorize">${hidden}<label for="password">部署授权密码</label><input id="password" name="password" type="password" autocomplete="current-password" autofocus required><button type="submit">授权此连接</button></form></main></body></html>`;
}

function securePage(reply: FastifyReply): void {
  reply
    .type("text/html; charset=utf-8")
    .header("Cache-Control", "no-store")
    .header("Referrer-Policy", "no-referrer")
    .header("X-Frame-Options", "DENY")
    .header(
      "Content-Security-Policy",
      "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    );
}

export function registerOAuth(app: FastifyInstance, rawOptions: OAuthOptions) {
  const enabled = Boolean(rawOptions.password);
  const options: OAuthOptions = {
    ...rawOptions,
    baseUrl: rawOptions.baseUrl.replace(/\/$/, ""),
    resourceUrl: rawOptions.resourceUrl.replace(/\/$/, ""),
  };
  const metadataPath = protectedResourceMetadataPath(options.resourceUrl);
  if (!enabled) {
    return {
      enabled: false,
      authenticate: (_authorization: string | undefined) => false,
      challenge: `Bearer resource_metadata="${options.baseUrl}${metadataPath}"`,
    };
  }

  const canonicalResource = normalizeResource(options.resourceUrl);
  if (!canonicalResource || canonicalResource !== options.resourceUrl) {
    throw new Error("OAuth resourceUrl must be a canonical HTTP(S) URL without query or fragment.");
  }
  if (!options.tokenSecret) throw new Error("MCP_AUTH_TOKEN_SECRET is required when OAuth is enabled.");

  const clients = new Map<string, OAuthClient>();
  const codes = new Map<string, AuthorizationCode>();
  const failedAttempts = new Map<string, { count: number; resetAt: number }>();
  const key = tokenKey(options.tokenSecret);
  const supportedScopes = [...new Set(options.scope.split(/\s+/).filter(Boolean))];
  if (supportedScopes.length === 0) throw new Error("MCP_OAUTH_SCOPE must not be empty.");

  const resolveResource = (raw: string | undefined): string | null => {
    if (!raw) return options.allowLegacyResourceOmission ? options.resourceUrl : null;
    return normalizeResource(raw) === canonicalResource ? options.resourceUrl : null;
  };
  const resolveScope = (raw: string | undefined): string | null => {
    const requested = [...new Set((raw?.trim() || options.scope).split(/\s+/).filter(Boolean))];
    return requested.length > 0 && requested.every((scope) => supportedScopes.includes(scope))
      ? requested.join(" ")
      : null;
  };

  const protectedResourceMeta = {
    resource: options.resourceUrl,
    authorization_servers: [options.baseUrl],
    scopes_supported: supportedScopes,
    bearer_methods_supported: ["header"],
  };
  for (const path of new Set(["/.well-known/oauth-protected-resource", metadataPath])) {
    app.get(path, async () => protectedResourceMeta);
  }
  app.get("/.well-known/oauth-authorization-server", async () => ({
    issuer: options.baseUrl,
    authorization_endpoint: `${options.baseUrl}/oauth/authorize`,
    token_endpoint: `${options.baseUrl}/oauth/token`,
    registration_endpoint: `${options.baseUrl}/oauth/register`,
    response_types_supported: ["code"],
    grant_types_supported: ["authorization_code"],
    code_challenge_methods_supported: ["S256"],
    token_endpoint_auth_methods_supported: ["none"],
    scopes_supported: supportedScopes,
    authorization_response_iss_parameter_supported: true,
    resource_indicators_supported: true,
    client_id_metadata_document_supported: false,
  }));

  app.post("/oauth/register", async (request, reply) => {
    const body = (request.body ?? {}) as { redirect_uris?: unknown; client_name?: unknown };
    const redirectUris = Array.isArray(body.redirect_uris)
      ? body.redirect_uris.filter((uri): uri is string => typeof uri === "string")
      : [];
    if (redirectUris.length === 0 || redirectUris.length > 16 || !redirectUris.every(validRedirectUri)) {
      return reply.code(400).send({
        error: "invalid_client_metadata",
        error_description: "Use exact HTTPS redirect URIs, or HTTP only for localhost/loopback.",
      });
    }
    const clientName = typeof body.client_name === "string"
      ? body.client_name.trim().slice(0, 120) || "device-timeline-mcp"
      : "device-timeline-mcp";
    const clientId = `device-timeline-${randomToken(18)}`;
    const issuedAt = Math.floor(Date.now() / 1000);
    clients.set(clientId, { redirectUris, clientName, issuedAt });
    return reply.code(201).send({
      client_id: clientId,
      client_id_issued_at: issuedAt,
      client_secret_expires_at: 0,
      redirect_uris: redirectUris,
      grant_types: ["authorization_code"],
      response_types: ["code"],
      token_endpoint_auth_method: "none",
      client_name: clientName,
    });
  });

  const validateAuthorization = (input: Record<string, unknown>) => {
    const responseType = asString(input.response_type);
    const clientId = asString(input.client_id);
    const redirectUri = asString(input.redirect_uri);
    const codeChallenge = asString(input.code_challenge);
    const method = asString(input.code_challenge_method) ?? "S256";
    const resource = resolveResource(asString(input.resource));
    const scope = resolveScope(asString(input.scope));
    if (responseType !== "code") return { error: "unsupported_response_type" } as const;
    if (!clientId || !redirectUri || !codeChallenge) return { error: "invalid_request" } as const;
    const client = clients.get(clientId);
    if (!client || !client.redirectUris.includes(redirectUri)) return { error: "invalid_client" } as const;
    if (method !== "S256" || !/^[A-Za-z0-9_-]{43,128}$/.test(codeChallenge)) {
      return { error: "invalid_request" } as const;
    }
    if (!resource) return { error: asString(input.resource) ? "invalid_target" : "invalid_request" } as const;
    if (!scope) return { error: "invalid_scope" } as const;
    return { clientId, redirectUri, codeChallenge, resource, scope, state: asString(input.state) };
  };

  app.get("/oauth/authorize", async (request, reply) => {
    const validated = validateAuthorization(request.query as Record<string, unknown>);
    if ("error" in validated) return reply.code(400).send({ error: validated.error });
    const fields = {
      response_type: "code",
      client_id: validated.clientId,
      redirect_uri: validated.redirectUri,
      code_challenge: validated.codeChallenge,
      code_challenge_method: "S256",
      resource: validated.resource,
      scope: validated.scope,
      ...(validated.state ? { state: validated.state } : {}),
    };
    securePage(reply);
    return reply.send(authorizationPage(fields));
  });

  app.post("/oauth/authorize", async (request, reply) => {
    const body = (request.body ?? {}) as Record<string, unknown>;
    const validated = validateAuthorization(body);
    if ("error" in validated) return reply.code(400).send({ error: validated.error });

    const attemptKey = request.ip || "unknown";
    const attempt = failedAttempts.get(attemptKey);
    if (attempt && attempt.resetAt > Date.now() && attempt.count >= 10) {
      return reply
        .header("Retry-After", String(Math.ceil((attempt.resetAt - Date.now()) / 1000)))
        .code(429)
        .send("Too many failed authorization attempts.");
    }
    const password = asString(body.password) ?? "";
    if (!safeEqualText(password, options.password)) {
      const next = attempt && attempt.resetAt > Date.now()
        ? { count: attempt.count + 1, resetAt: attempt.resetAt }
        : { count: 1, resetAt: Date.now() + 10 * 60 * 1000 };
      failedAttempts.set(attemptKey, next);
      securePage(reply);
      return reply.code(403).send(authorizationPage({
        response_type: "code",
        client_id: validated.clientId,
        redirect_uri: validated.redirectUri,
        code_challenge: validated.codeChallenge,
        code_challenge_method: "S256",
        resource: validated.resource,
        scope: validated.scope,
        ...(validated.state ? { state: validated.state } : {}),
      }, "密码错误，请重试。"));
    }
    failedAttempts.delete(attemptKey);

    const code = randomToken(24);
    codes.set(code, {
      clientId: validated.clientId,
      redirectUri: validated.redirectUri,
      codeChallenge: validated.codeChallenge,
      resource: validated.resource,
      scope: validated.scope,
      expiresAt: Date.now() + AUTHORIZATION_CODE_TTL_MS,
    });
    const redirect = new URL(validated.redirectUri);
    redirect.searchParams.set("code", code);
    if (validated.state) redirect.searchParams.set("state", validated.state);
    redirect.searchParams.set("iss", options.baseUrl);
    return reply.redirect(redirect.toString());
  });

  app.post("/oauth/token", async (request, reply) => {
    reply.header("Cache-Control", "no-store");
    const body = (request.body ?? {}) as Record<string, unknown>;
    if (asString(body.grant_type) !== "authorization_code") {
      return reply.code(400).send({ error: "unsupported_grant_type" });
    }
    const code = asString(body.code);
    const clientId = asString(body.client_id);
    const redirectUri = asString(body.redirect_uri);
    const verifier = asString(body.code_verifier);
    if (!code || !clientId || !redirectUri || !verifier) {
      return reply.code(400).send({ error: "invalid_request" });
    }
    const record = codes.get(code);
    if (!record || record.expiresAt <= Date.now()) {
      if (record) codes.delete(code);
      return reply.code(400).send({ error: "invalid_grant" });
    }
    // One-time code: consume it before any other validation to prevent races/replay.
    codes.delete(code);
    const resource = resolveResource(asString(body.resource));
    if (!resource || resource !== record.resource) {
      return reply.code(400).send({ error: asString(body.resource) ? "invalid_target" : "invalid_request" });
    }
    if (
      clientId !== record.clientId ||
      redirectUri !== record.redirectUri ||
      !verifyPkceS256(verifier, record.codeChallenge)
    ) {
      return reply.code(400).send({ error: "invalid_grant" });
    }
    return reply.send({
      access_token: issueAccessToken(options, key, clientId, resource, record.scope),
      token_type: "Bearer",
      expires_in: ACCESS_TOKEN_TTL_SECONDS,
      scope: record.scope,
    });
  });

  return {
    enabled: true,
    authenticate(authorization: string | undefined): boolean {
      const token = authorization?.startsWith("Bearer ") ? authorization.slice(7) : "";
      return Boolean(token && verifyAccessToken(token, options, key));
    },
    challenge: `Bearer resource_metadata="${options.baseUrl}${metadataPath}", scope="${options.scope}"`,
  };
}

export function requestBearer(request: FastifyRequest): string | null {
  const authorization = request.headers.authorization;
  return authorization?.startsWith("Bearer ") ? authorization.slice(7) : null;
}
