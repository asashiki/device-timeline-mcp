import crypto from "node:crypto";

function safeEqual(actual: string, expected: string): boolean {
  const a = crypto.createHash("sha256").update(actual).digest();
  const b = crypto.createHash("sha256").update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

function bearerToken(authorization: string | undefined): string | null {
  if (!authorization) return null;
  const match = /^Bearer\s+(.+)$/i.exec(authorization.trim());
  return match?.[1]?.trim() || null;
}

export class ReadAuth {
  constructor(
    private readonly token: string | null,
    private readonly allowUnauthenticated: boolean,
  ) {}

  resolve(authorization: string | undefined): boolean {
    if (this.allowUnauthenticated && !this.token) return true;
    const provided = bearerToken(authorization);
    return Boolean(this.token && provided && safeEqual(provided, this.token));
  }
}
