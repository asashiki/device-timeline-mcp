import type { DeviceToken } from "./config.js";

export class DeviceAuth {
  private readonly byToken = new Map<string, DeviceToken>();

  constructor(tokens: DeviceToken[]) {
    for (const t of tokens) this.byToken.set(t.token, t);
  }

  // Resolves an `Authorization: Bearer <token>` header to a device identity.
  resolve(authorization: string | undefined): DeviceToken | null {
    if (!authorization) return null;
    const match = /^Bearer\s+(.+)$/i.exec(authorization.trim());
    const token = match ? match[1]!.trim() : authorization.trim();
    return this.byToken.get(token) ?? null;
  }
}
