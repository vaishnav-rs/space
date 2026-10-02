import type { PersonalConfig } from "./config.js";

const TOKEN_URL = "https://oauth2.googleapis.com/token";

type Token = { value: string; expiresAt: number };
let cached: (Token & { key: string }) | undefined;

export class GoogleNotConfiguredError extends Error {
  constructor() {
    super(
      "Google is not connected. Set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and GOOGLE_REFRESH_TOKEN (see personal/README.md).",
    );
  }
}

async function accessToken(cfg: PersonalConfig["google"], signal?: AbortSignal): Promise<string> {
  if (!cfg.clientId || !cfg.clientSecret || !cfg.refreshToken) {
    throw new GoogleNotConfiguredError();
  }
  const key = `${cfg.clientId}:${cfg.refreshToken}`;
  if (cached && cached.key === key && cached.expiresAt - Date.now() > 60_000) {
    return cached.value;
  }
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: cfg.clientId,
      client_secret: cfg.clientSecret,
      refresh_token: cfg.refreshToken,
      grant_type: "refresh_token",
    }),
    signal,
  });
  if (!res.ok) {
    throw new Error(`Google token refresh failed (${res.status}).`);
  }
  const body = (await res.json()) as { access_token: string; expires_in: number };
  cached = { key, value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
  return body.access_token;
}

export async function googleRequest<T>(
  cfg: PersonalConfig["google"],
  url: string,
  init: { method?: string; body?: unknown; signal?: AbortSignal } = {},
): Promise<T> {
  const token = await accessToken(cfg, init.signal);
  const res = await fetch(url, {
    method: init.method ?? "GET",
    headers: {
      authorization: `Bearer ${token}`,
      ...(init.body === undefined ? {} : { "content-type": "application/json" }),
    },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    signal: init.signal,
  });
  if (!res.ok) {
    throw new Error(`Google API ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export function buildRawEmail(msg: {
  to: string[];
  cc?: string[];
  subject: string;
  body: string;
  from?: string;
}): string {
  const header = (name: string, v: string) => `${name}: ${v.replace(/[\r\n]+/g, " ")}`;
  const lines = [
    ...(msg.from ? [header("From", msg.from)] : []),
    header("To", msg.to.join(", ")),
    ...(msg.cc?.length ? [header("Cc", msg.cc.join(", "))] : []),
    header("Subject", msg.subject),
    "MIME-Version: 1.0",
    'Content-Type: text/plain; charset="UTF-8"',
    "",
    msg.body,
  ];
  return Buffer.from(lines.join("\r\n")).toString("base64url");
}
