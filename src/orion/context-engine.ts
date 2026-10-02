import type { KnowledgeHit, KnowledgePort } from "./ports.js";
import { wrapUntrusted } from "./untrusted.js";

const STOP = new Set([
  "the",
  "a",
  "an",
  "is",
  "are",
  "was",
  "were",
  "to",
  "of",
  "and",
  "or",
  "in",
  "on",
  "it",
  "this",
  "that",
  "with",
  "for",
  "says",
  "client",
  "user",
  "not",
  "doesn't",
  "does",
  "broken",
  "fails",
  "failing",
  "work",
  "works",
  "working",
  "please",
  "when",
  "after",
  "before",
]);

export function extractTerms(text: string, max = 12): string[] {
  const counts = new Map<string, number>();
  for (const raw of text.toLowerCase().split(/[^a-z0-9_]+/)) {
    if (raw.length > 2 && !STOP.has(raw)) {
      counts.set(raw, (counts.get(raw) ?? 0) + 1);
    }
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, max)
    .map(([t]) => t);
}

export type SourceTrace = {
  source: string;
  kind: string;
  status: "ok" | "unavailable" | "error";
  integration: string;
  returned: number;
  selected: number;
  note?: string;
};

export type Contradiction = {
  subject: string;
  predicate: string;
  values: { source: string; kind: string; value: string; ref: string }[];
};

export type ContextItem = {
  source: string;
  kind: string;
  ref: string;
  score: number;
  wrapped: string;
};

export type ContextResult = {
  items: ContextItem[];
  trace: SourceTrace[];
  contradictions: Contradiction[];
  suspicious: { ref: string; patterns: string[] }[];
  /** Plain-language account of what was retrieved and why, for the task timeline. */
  explanation: string;
};

const norm = (s: string) => s.trim().toLowerCase().replace(/\s+/g, " ");

export function findContradictions(hits: KnowledgeHit[]): Contradiction[] {
  const groups = new Map<string, Contradiction["values"] & { subject?: string }>();
  const meta = new Map<string, { subject: string; predicate: string }>();
  for (const h of hits) {
    for (const c of h.claims ?? []) {
      const key = `${norm(c.subject)}|${norm(c.predicate)}`;
      meta.set(key, { subject: c.subject, predicate: c.predicate });
      const list = groups.get(key) ?? [];
      list.push({ source: h.source, kind: h.kind, value: c.value, ref: h.ref });
      groups.set(key, list);
    }
  }
  const out: Contradiction[] = [];
  for (const [key, values] of groups) {
    if (
      new Set(values.map((v) => norm(v.value))).size > 1 &&
      new Set(values.map((v) => v.source)).size > 1
    ) {
      const m = meta.get(key);
      if (m) {
        out.push({ ...m, values });
      }
    }
  }
  return out;
}

/**
 * Staged retrieval: graph first (what exists), then the other sources with terms widened by
 * what the graph found. Failing or unavailable sources are reported, never fatal, never faked.
 */
export async function buildContext(input: {
  report: string;
  sources: KnowledgePort[];
  budgetChars: number;
  perSourceLimit?: number;
}): Promise<ContextResult> {
  const limit = input.perSourceLimit ?? 5;
  const terms = extractTerms(input.report);
  const trace: SourceTrace[] = [];
  const all: KnowledgeHit[] = [];

  const query = async (src: KnowledgePort, qterms: string[]) => {
    if (src.integration === "unavailable") {
      trace.push({
        source: src.id,
        kind: src.kind,
        integration: src.integration,
        status: "unavailable",
        returned: 0,
        selected: 0,
        note: "source not available",
      });
      return [];
    }
    try {
      const hits = await src.query({ text: input.report, terms: qterms, limit });
      trace.push({
        source: src.id,
        kind: src.kind,
        integration: src.integration,
        status: "ok",
        returned: hits.length,
        selected: 0,
      });
      return hits;
    } catch (err) {
      trace.push({
        source: src.id,
        kind: src.kind,
        integration: src.integration,
        status: "error",
        returned: 0,
        selected: 0,
        note: err instanceof Error ? err.message : String(err),
      });
      return [];
    }
  };

  const graph = input.sources.filter((s) => s.kind === "graphify");
  const rest = input.sources.filter((s) => s.kind !== "graphify");
  const graphHits = (await Promise.all(graph.map((s) => query(s, terms)))).flat();
  all.push(...graphHits);
  const widened = [
    ...new Set([
      ...terms,
      ...extractTerms(graphHits.map((h) => `${h.ref} ${h.text}`).join(" "), 8),
    ]),
  ];
  all.push(...(await Promise.all(rest.map((s) => query(s, widened)))).flat());

  const ranked = [...all].sort((a, b) => b.score - a.score);
  const items: ContextItem[] = [];
  const suspicious: ContextResult["suspicious"] = [];
  let used = 0;
  for (const hit of ranked) {
    const wrapped = wrapUntrusted(
      hit.kind === "code" ? "code" : hit.kind === "git" ? "git" : "knowledge",
      `${hit.source}:${hit.ref}`,
      hit.text,
    );
    if (used + wrapped.text.length > input.budgetChars) {
      continue;
    }
    used += wrapped.text.length;
    items.push({
      source: hit.source,
      kind: hit.kind,
      ref: hit.ref,
      score: hit.score,
      wrapped: wrapped.text,
    });
    if (wrapped.suspicious.length) {
      suspicious.push({ ref: `${hit.source}:${hit.ref}`, patterns: wrapped.suspicious });
    }
    const t = trace.find((x) => x.source === hit.source);
    if (t) {
      t.selected += 1;
    }
  }

  const contradictions = findContradictions(all);
  const explanation = [
    `Terms: ${widened.join(", ") || "(none)"}`,
    ...trace.map(
      (t) =>
        `${t.source} (${t.kind}, ${t.integration}): ${t.status}${t.note ? ` – ${t.note}` : ""}, ${t.selected}/${t.returned} used`,
    ),
    contradictions.length
      ? `Contradictions: ${contradictions.map((c) => `${c.subject} ${c.predicate}`).join("; ")}`
      : "No contradictions detected",
    `Budget: ${used}/${input.budgetChars} chars`,
  ].join("\n");
  return { items, trace, contradictions, suspicious, explanation };
}
