import { Type } from "typebox";
import type { AnyAgentTool } from "../agents/tools/common.js";
import type { PersonalConfig } from "./config.js";
import { buildRawEmail, googleRequest } from "./google-client.js";
import { guardEmailCall, type TurnOrigin } from "./guard.js";
import { optList, optStr, personalTool, reqStr, str, strList } from "./tool-kit.js";

const GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";
const CAL = "https://www.googleapis.com/calendar/v3";
const TASKS = "https://tasks.googleapis.com/tasks/v1";

type GmailHeader = { name: string; value: string };
type GmailMessage = {
  id: string;
  threadId: string;
  snippet?: string;
  payload?: { headers?: GmailHeader[]; body?: { data?: string }; parts?: GmailPart[] };
};
type GmailPart = { mimeType?: string; body?: { data?: string }; parts?: GmailPart[] };

function header(m: GmailMessage, name: string): string | undefined {
  return m.payload?.headers?.find((h) => h.name.toLowerCase() === name.toLowerCase())?.value;
}

function plainText(part: GmailPart | undefined): string {
  if (!part) {
    return "";
  }
  if (part.mimeType === "text/plain" && part.body?.data) {
    return Buffer.from(part.body.data, "base64url").toString("utf8");
  }
  return (part.parts ?? []).map(plainText).find(Boolean) ?? "";
}

export function createGoogleTools(
  cfg: PersonalConfig,
  getOrigin: () => TurnOrigin,
): AnyAgentTool[] {
  return [
    personalTool({
      name: "gmail_search",
      label: "Gmail search",
      description:
        "Search Gmail with Gmail query syntax (e.g. 'is:unread newer_than:2d'). Returns sender, subject, date, snippet.",
      parameters: Type.Object({
        query: str("Gmail search query."),
        max: Type.Optional(Type.Integer({ minimum: 1, maximum: 25 })),
      }),
      run: async (p, signal) => {
        const max = typeof p.max === "number" ? p.max : 10;
        const list = await googleRequest<{ messages?: { id: string }[] }>(
          cfg.google,
          `${GMAIL}/messages?${new URLSearchParams({ q: reqStr(p, "query"), maxResults: String(max) })}`,
          { signal },
        );
        const msgs = await Promise.all(
          (list.messages ?? []).map((m) =>
            googleRequest<GmailMessage>(
              cfg.google,
              `${GMAIL}/messages/${m.id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date`,
              { signal },
            ),
          ),
        );
        return msgs.map((m) => ({
          id: m.id,
          threadId: m.threadId,
          from: header(m, "From"),
          subject: header(m, "Subject"),
          date: header(m, "Date"),
          snippet: m.snippet,
        }));
      },
    }),
    personalTool({
      name: "gmail_read",
      label: "Gmail read",
      description: "Read the plain-text body of one Gmail message by id.",
      parameters: Type.Object({ id: str("Message id from gmail_search.") }),
      run: async (p, signal) => {
        const m = await googleRequest<GmailMessage>(
          cfg.google,
          `${GMAIL}/messages/${reqStr(p, "id")}?format=full`,
          { signal },
        );
        return {
          id: m.id,
          from: header(m, "From"),
          to: header(m, "To"),
          subject: header(m, "Subject"),
          date: header(m, "Date"),
          body: (plainText(m.payload) || m.snippet || "").slice(0, 20_000),
        };
      },
    }),
    personalTool({
      name: "gmail_draft",
      label: "Gmail draft",
      description:
        "Create a Gmail draft. Nothing is sent; use this when drafting on the owner's behalf.",
      parameters: Type.Object({
        to: Type.Array(Type.String(), { minItems: 1 }),
        cc: strList("CC addresses."),
        subject: str("Subject."),
        body: str("Plain-text body."),
      }),
      run: async (p, signal) => {
        const raw = buildRawEmail({
          to: optList(p, "to"),
          cc: optList(p, "cc"),
          subject: reqStr(p, "subject"),
          body: reqStr(p, "body"),
        });
        const d = await googleRequest<{ id: string }>(cfg.google, `${GMAIL}/drafts`, {
          method: "POST",
          body: { message: { raw } },
          signal,
        });
        return { draftId: d.id, status: "drafted" };
      },
    }),
    personalTool({
      name: "gmail_send",
      label: "Gmail send",
      description:
        "Send an email from the owner's Gmail. To anyone other than the owner this only works when the owner asked for it in the current turn.",
      parameters: Type.Object({
        to: Type.Array(Type.String(), { minItems: 1 }),
        cc: strList("CC addresses."),
        subject: str("Subject."),
        body: str("Plain-text body."),
      }),
      run: async (p, signal) => {
        const decision = guardEmailCall(p, cfg.ownerEmails, getOrigin());
        if (!decision.allow) {
          throw new Error(decision.reason);
        }
        const raw = buildRawEmail({
          to: optList(p, "to"),
          cc: optList(p, "cc"),
          subject: reqStr(p, "subject"),
          body: reqStr(p, "body"),
        });
        const sent = await googleRequest<{ id: string }>(cfg.google, `${GMAIL}/messages/send`, {
          method: "POST",
          body: { raw },
          signal,
        });
        return { messageId: sent.id, status: "sent" };
      },
    }),
    personalTool({
      name: "calendar_list",
      label: "Calendar list",
      description: "List upcoming Google Calendar events in a time window (ISO timestamps).",
      parameters: Type.Object({
        from: optStr("ISO start, default now."),
        to: optStr("ISO end, default +7 days."),
      }),
      run: async (p, signal) => {
        const from = typeof p.from === "string" ? p.from : new Date().toISOString();
        const to = typeof p.to === "string" ? p.to : new Date(Date.now() + 7 * 864e5).toISOString();
        const res = await googleRequest<{
          items?: {
            id: string;
            summary?: string;
            start?: unknown;
            end?: unknown;
            location?: string;
          }[];
        }>(
          cfg.google,
          `${CAL}/calendars/primary/events?${new URLSearchParams({ timeMin: from, timeMax: to, singleEvents: "true", orderBy: "startTime", maxResults: "50" })}`,
          { signal },
        );
        return (res.items ?? []).map((e) => ({
          id: e.id,
          summary: e.summary,
          start: e.start,
          end: e.end,
          location: e.location,
        }));
      },
    }),
    personalTool({
      name: "calendar_create",
      label: "Calendar create",
      description:
        "Create a Google Calendar event (e.g. a focus block or reminder) on the owner's primary calendar.",
      parameters: Type.Object({
        summary: str("Title."),
        start: str("ISO start."),
        end: str("ISO end."),
        description: optStr("Details."),
      }),
      run: async (p, signal) => {
        const e = await googleRequest<{ id: string; htmlLink: string }>(
          cfg.google,
          `${CAL}/calendars/primary/events`,
          {
            method: "POST",
            body: {
              summary: reqStr(p, "summary"),
              description: typeof p.description === "string" ? p.description : undefined,
              start: { dateTime: reqStr(p, "start") },
              end: { dateTime: reqStr(p, "end") },
            },
            signal,
          },
        );
        return { id: e.id, link: e.htmlLink };
      },
    }),
    personalTool({
      name: "tasks_list",
      label: "Google Tasks list",
      description: "List open Google Tasks from the default list.",
      parameters: Type.Object({}),
      run: async (_p, signal) => {
        const res = await googleRequest<{
          items?: { id: string; title: string; due?: string; notes?: string }[];
        }>(cfg.google, `${TASKS}/lists/@default/tasks?showCompleted=false&maxResults=100`, {
          signal,
        });
        return res.items ?? [];
      },
    }),
    personalTool({
      name: "tasks_add",
      label: "Google Tasks add",
      description: "Add a task to the default Google Tasks list.",
      parameters: Type.Object({
        title: str("Task title."),
        notes: optStr("Notes."),
        due: optStr("RFC 3339 due date."),
      }),
      run: async (p, signal) => {
        const t = await googleRequest<{ id: string }>(cfg.google, `${TASKS}/lists/@default/tasks`, {
          method: "POST",
          body: {
            title: reqStr(p, "title"),
            notes: typeof p.notes === "string" ? p.notes : undefined,
            due: typeof p.due === "string" ? p.due : undefined,
          },
          signal,
        });
        return { id: t.id, status: "added" };
      },
    }),
    personalTool({
      name: "tasks_complete",
      label: "Google Tasks complete",
      description: "Mark a Google Task completed.",
      parameters: Type.Object({ id: str("Task id from tasks_list.") }),
      run: async (p, signal) => {
        await googleRequest(cfg.google, `${TASKS}/lists/@default/tasks/${reqStr(p, "id")}`, {
          method: "PATCH",
          body: { status: "completed" },
          signal,
        });
        return { status: "completed" };
      },
    }),
  ];
}
