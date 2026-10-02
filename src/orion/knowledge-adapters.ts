import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { extname, join, relative, resolve } from "node:path";
import type { KnowledgeHit, KnowledgePort, KnowledgeSourceKind } from "./ports.js";

const SKIP_DIRS = new Set([".git", "node_modules", "dist", "build", ".next", "coverage"]);
const MAX_FILE_BYTES = 512 * 1024;
const MAX_FILES = 5000;

function* walk(root: string, exts: ReadonlySet<string>): Generator<string> {
  let count = 0;
  const stack = [root];
  while (stack.length && count < MAX_FILES) {
    const dir = stack.pop() as string;
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isSymbolicLink()) continue; // never follow links out of the source root
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!SKIP_DIRS.has(entry.name)) stack.push(full);
      } else if (exts.has(extname(entry.name).toLowerCase())) {
        count++;
        yield full;
      }
    }
  }
}

const CLAIM = /^\s*[-*]?\s*claim:\s*(.+?)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*$/gim;

/**
 * Real keyword retrieval over a directory of text files (wiki pages, product docs, source).
 * Documents may state checkable facts as `claim: subject | predicate | value` lines; those feed
 * contradiction detection. Unstructured prose is retrieved but not compared.
 */
export function createFileKnowledge(opts: {
  id: string;
  kind: KnowledgeSourceKind;
  root: string;
  extensions?: string[];
}): KnowledgePort {
  const root = resolve(opts.root);
  const exts = new Set((opts.extensions ?? [".md", ".mdx", ".txt"]).map((e) => e.toLowerCase()));
  if (!existsSync(root)) {
    return {
      integration: "unavailable",
      name: opts.id,
      id: opts.id,
      kind: opts.kind,
      query: async () => [],
    };
  }
  return {
    integration: "real",
    name: opts.id,
    id: opts.id,
    kind: opts.kind,
    async query({ terms, limit }) {
      const hits: KnowledgeHit[] = [];
      for (const file of walk(root, exts)) {
        if (statSync(file).size > MAX_FILE_BYTES) continue;
        const text = readFileSync(file, "utf8");
        const lower = text.toLowerCase();
        const rel = relative(root, file);
        let score = 0;
        let first = -1;
        for (const t of terms) {
          const at = lower.indexOf(t);
          if (at >= 0) {
            score += 1 + (rel.toLowerCase().includes(t) ? 2 : 0);
            if (first < 0 || at < first) first = at;
          }
        }
        if (score === 0) continue;
        const claims = [...text.matchAll(CLAIM)].map((m) => ({
          subject: m[1] ?? "",
          predicate: m[2] ?? "",
          value: m[3] ?? "",
        }));
        hits.push({
          source: opts.id,
          kind: opts.kind,
          ref: rel,
          text: text.slice(Math.max(0, first - 200), first + 800),
          score: score / (terms.length || 1),
          ...(claims.length ? { claims } : {}),
        });
      }
      return hits.sort((a, b) => b.score - a.score).slice(0, limit);
    },
  };
}

type GraphJson = {
  nodes: { id: string; label?: string; type?: string; file?: string }[];
  edges: { source: string; target: string; relation?: string }[];
};

/**
 * Graph source over an exported graph file (`{nodes:[{id,label,type,file}], edges:[{source,target,relation}]}`).
 * This format is an assumption about the Graphify export; adjust here if the real export differs.
 * A missing or unparsable file makes the source `unavailable`, never an empty success.
 */
export function createGraphKnowledge(opts: { id: string; graphFile: string }): KnowledgePort {
  let graph: GraphJson | undefined;
  try {
    const parsed = JSON.parse(readFileSync(opts.graphFile, "utf8")) as GraphJson;
    if (Array.isArray(parsed.nodes) && Array.isArray(parsed.edges)) graph = parsed;
  } catch {
    graph = undefined;
  }
  if (!graph) {
    return {
      integration: "unavailable",
      name: opts.id,
      id: opts.id,
      kind: "graphify",
      query: async () => [],
    };
  }
  const g = graph;
  return {
    integration: "real",
    name: opts.id,
    id: opts.id,
    kind: "graphify",
    async query({ terms, limit }) {
      const label = (n: GraphJson["nodes"][number]) =>
        `${n.id} ${n.label ?? ""} ${n.file ?? ""}`.toLowerCase();
      const byId = new Map(g.nodes.map((n) => [n.id, n]));
      const scored = g.nodes
        .map((n) => ({ n, s: terms.filter((t) => label(n).includes(t)).length }))
        .filter((x) => x.s > 0)
        .sort((a, b) => b.s - a.s)
        .slice(0, limit);
      return scored.map(({ n, s }) => {
        const rels = g.edges
          .filter((e) => e.source === n.id || e.target === n.id)
          .slice(0, 12)
          .map((e) => {
            const other = byId.get(e.source === n.id ? e.target : e.source);
            return `${e.source === n.id ? "→" : "←"} ${e.relation ?? "related"} ${other?.label ?? other?.id ?? "?"}`;
          });
        return {
          source: opts.id,
          kind: "graphify" as const,
          ref: n.file ?? n.id,
          text: `${n.label ?? n.id} (${n.type ?? "entity"})\n${rels.join("\n")}`,
          score: s / (terms.length || 1),
        };
      });
    },
  };
}
