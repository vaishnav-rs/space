/**
 * Outbound guard: the agent reaches other people only when the owner asked for it in that turn.
 * Pings to the owner are always fine. Scheduled turns (heartbeat, cron) never contact third
 * parties, and unknown provenance fails closed.
 */
export type TurnOrigin = {
  /** True only when the owner started this turn with a message. */
  ownerInitiated: boolean;
};

export type GuardDecision = { allow: true } | { allow: false; reason: string };

const OUTBOUND_MESSAGE_ACTIONS = new Set([
  "send",
  "sendWithEffect",
  "sendAttachment",
  "broadcast",
  "poll",
  "thread-create",
  "thread-reply",
]);

export function normalizeAddress(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/^(whatsapp|tel|mailto):/, "")
    .replace(/[\s()-]/g, "");
}

function strings(value: unknown): string[] {
  if (typeof value === "string") {
    return [value];
  }
  return Array.isArray(value) ? value.flatMap(strings) : [];
}

export function allOwned(recipients: string[], owned: string[]): boolean {
  const set = new Set(owned.map(normalizeAddress));
  return recipients.length > 0 && recipients.every((r) => set.has(normalizeAddress(r)));
}

function decide(recipients: string[], owned: string[], origin: TurnOrigin): GuardDecision {
  if (allOwned(recipients, owned) || origin.ownerInitiated) {
    return { allow: true };
  }
  return {
    allow: false,
    reason:
      `Blocked: contacting ${recipients.join(", ")} requires the owner to ask for it in this ` +
      `turn. Draft it and message the owner instead.`,
  };
}

export function guardMessageCall(
  params: Record<string, unknown>,
  ownerTargets: string[],
  origin: TurnOrigin,
): GuardDecision {
  const action = typeof params.action === "string" ? params.action : "";
  if (!OUTBOUND_MESSAGE_ACTIONS.has(action)) {
    return { allow: true };
  }
  const recipients = [params.target, params.targets, params.to].flatMap(strings);
  if (recipients.length === 0) {
    // No explicit target: the message goes to the conversation that started this turn.
    return { allow: true };
  }
  return decide(recipients, ownerTargets, origin);
}

export function guardEmailCall(
  params: Record<string, unknown>,
  ownerEmails: string[],
  origin: TurnOrigin,
): GuardDecision {
  const recipients = [params.to, params.cc, params.bcc].flatMap(strings);
  if (recipients.length === 0) {
    return { allow: true };
  }
  return decide(recipients, ownerEmails, origin);
}
