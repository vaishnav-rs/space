/** Personal-assistant settings, read from the environment so no config-schema change is needed. */
export type PersonalConfig = {
  ownerEmails: string[];
  ownerTargets: string[];
  confirmOwnerRequestedSends: boolean;
  google: { clientId?: string; clientSecret?: string; refreshToken?: string };
  resend: { apiKey?: string; from?: string };
  devRepos: string[];
  dataDir: string;
};

function list(value: string | undefined): string[] {
  return (value ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

export function readPersonalConfig(env: NodeJS.ProcessEnv = process.env): PersonalConfig {
  const resendFrom = env.RESEND_FROM?.trim();
  return {
    ownerEmails: list(env.PERSONAL_OWNER_EMAILS),
    ownerTargets: list(env.PERSONAL_OWNER_TARGETS),
    confirmOwnerRequestedSends: env.PERSONAL_CONFIRM_OUTBOUND === "1",
    google: {
      clientId: env.GOOGLE_CLIENT_ID?.trim(),
      clientSecret: env.GOOGLE_CLIENT_SECRET?.trim(),
      refreshToken: env.GOOGLE_REFRESH_TOKEN?.trim(),
    },
    resend: { apiKey: env.RESEND_API_KEY?.trim(), ...(resendFrom ? { from: resendFrom } : {}) },
    devRepos: list(env.PERSONAL_DEV_REPOS),
    dataDir: env.PERSONAL_DATA_DIR?.trim() || `${env.HOME ?? "."}/.openclaw/personal`,
  };
}
