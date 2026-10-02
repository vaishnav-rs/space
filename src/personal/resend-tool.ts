import { Type } from "typebox";
import type { AnyAgentTool } from "../agents/tools/common.js";
import type { PersonalConfig } from "./config.js";
import { guardEmailCall, type TurnOrigin } from "./guard.js";
import { optList, optStr, personalTool, reqStr, str, strList } from "./tool-kit.js";

export function createResendTool(cfg: PersonalConfig, getOrigin: () => TurnOrigin): AnyAgentTool {
  return personalTool({
    name: "resend_email",
    label: "Email via Resend",
    description:
      "Send an email through Resend. Omit `to` to email the owner (briefings, alerts, digests). Other recipients only work when the owner asked for it in the current turn.",
    parameters: Type.Object({
      to: strList("Recipients; defaults to the owner."),
      subject: str("Subject."),
      text: str("Plain-text body."),
      html: optStr("Optional HTML body."),
    }),
    run: async (p, signal) => {
      if (!cfg.resend.apiKey || !cfg.resend.from) {
        throw new Error("Resend is not configured. Set RESEND_API_KEY and RESEND_FROM.");
      }
      const to = optList(p, "to");
      const recipients = to.length > 0 ? to : cfg.ownerEmails;
      if (recipients.length === 0) {
        throw new Error("No recipient: set PERSONAL_OWNER_EMAILS or pass `to`.");
      }
      const decision = guardEmailCall({ to: recipients }, cfg.ownerEmails, getOrigin());
      if (!decision.allow) {
        throw new Error(decision.reason);
      }
      const res = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: {
          authorization: `Bearer ${cfg.resend.apiKey}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({
          from: cfg.resend.from,
          to: recipients,
          subject: reqStr(p, "subject"),
          text: reqStr(p, "text"),
          ...(typeof p.html === "string" ? { html: p.html } : {}),
        }),
        signal,
      });
      if (!res.ok) {
        throw new Error(`Resend ${res.status}: ${(await res.text()).slice(0, 300)}`);
      }
      return { status: "sent", ...((await res.json()) as object) };
    },
  });
}
