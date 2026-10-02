import { createHmac } from "node:crypto";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { buildContext, extractTerms } from "./context-engine.js";
import { createFileKnowledge, createGraphKnowledge } from "./knowledge-adapters.js";
import { canRead } from "./memory-scope.js";
import { createSpaceRuntime } from "./runtime.js";
import { createHewarFixture } from "./testing/hewar-fixture.js";
import { createSpaceTaskTool } from "./tool.js";
import { routeRequest } from "./workspace-routing.js";
import { createWorkspaceRegistry, parseWorkspaceManifest } from "./workspace.js";

const tmp = () => mkdtempSync(join(tmpdir(), "space-"));

describe("workspace routing and memory scopes", () => {
  const fx = createHewarFixture();
  const personal = parseWorkspaceManifest({
    id: "personal",
    name: "Personal",
    kind: "personal",
    policy: { grant: [], requireApproval: [] },
  });
  const registry = createWorkspaceRegistry([personal, fx.manifest]);

  it("keeps ordinary assistant requests personal and activates Hewar only when relevant", () => {
    expect(routeRequest("Remind me tomorrow at 8", registry).workspaceId).toBe("personal");
    expect(
      routeRequest("Check Hewar's production logs for the upload failure", registry).workspaceId,
    ).toBe("hewar");
    expect(routeRequest("see https://github.com/acme/hewar/issues/184", registry).workspaceId).toBe(
      "hewar",
    );
    expect(routeRequest("anything", registry, "hewar").reason).toBe("selected explicitly");
  });

  it("does not let project work read personal memory or other projects", () => {
    expect(canRead({ workspaceId: "hewar" }, "personal:global")).toBe(false);
    expect(canRead({ workspaceId: "hewar", allowPersonal: true }, "personal:global")).toBe(true);
    expect(canRead({ workspaceId: "personal" }, "personal:global")).toBe(true);
    expect(canRead({ workspaceId: "personal" }, "project:hewar")).toBe(false);
    expect(canRead({ workspaceId: "hewar", taskId: "a" }, "task:b")).toBe(false);
    expect(canRead({ workspaceId: "hewar", taskId: "a" }, "task:a")).toBe(true);
  });
});

describe("knowledge and context", () => {
  it("retrieves from real files, reports unavailable sources, and detects doc/wiki contradictions", async () => {
    const dir = tmp();
    mkdirSync(join(dir, "wiki"));
    mkdirSync(join(dir, "docs"));
    writeFileSync(
      join(dir, "wiki/uploads.md"),
      "# Uploads\nAvatar uploads go through the upload service.\nclaim: avatar upload | max size | 5MB\n",
    );
    writeFileSync(
      join(dir, "docs/profile.md"),
      "# Profile\nUsers upload avatar images.\nclaim: avatar upload | max size | 100KB\n",
    );
    writeFileSync(
      join(dir, "graph.json"),
      JSON.stringify({
        nodes: [
          { id: "n1", label: "UploadService", type: "service", file: "src/upload.js" },
          { id: "n2", label: "Storage" },
        ],
        edges: [{ source: "n1", target: "n2", relation: "writes to" }],
      }),
    );
    const ctx = await buildContext({
      report: "Client says profile avatar upload fails",
      budgetChars: 8000,
      sources: [
        createGraphKnowledge({ id: "graph", graphFile: join(dir, "graph.json") }),
        createFileKnowledge({ id: "wiki", kind: "wiki", root: join(dir, "wiki") }),
        createFileKnowledge({ id: "docs", kind: "product-docs", root: join(dir, "docs") }),
        createFileKnowledge({ id: "missing", kind: "wiki", root: join(dir, "nope") }),
        createGraphKnowledge({ id: "badgraph", graphFile: join(dir, "nope.json") }),
      ],
    });
    expect(ctx.items.map((i) => i.source).sort()).toEqual(["docs", "graph", "wiki"]);
    expect(
      ctx.trace
        .filter((t) => t.status === "unavailable")
        .map((t) => t.source)
        .sort(),
    ).toEqual(["badgraph", "missing"]);
    expect(ctx.contradictions).toHaveLength(1);
    expect(ctx.contradictions[0]?.values.map((v) => v.value).sort()).toEqual(["100KB", "5MB"]);
    expect(ctx.items.every((i) => i.wrapped.includes("EXTERNAL_UNTRUSTED_CONTENT"))).toBe(true);
    expect(ctx.explanation).toContain("Contradictions: avatar upload max size");
    expect(extractTerms("Client says the profile upload is broken")).toEqual(["profile", "upload"]);
  });

  it("respects the context budget", async () => {
    const dir = tmp();
    writeFileSync(join(dir, "a.md"), `upload ${"x".repeat(2000)}`);
    const ctx = await buildContext({
      report: "upload",
      budgetChars: 100,
      sources: [createFileKnowledge({ id: "w", kind: "wiki", root: dir })],
    });
    expect(ctx.items).toHaveLength(0);
  });
});

describe("runtime: webhook in, status out, resume after restart", () => {
  it("creates a task from a signed mention, rejects forged ones, and resumes tasks on a new runtime", async () => {
    const fx = createHewarFixture();
    const stateDir = tmp();
    const wsDir = tmp();
    writeFileSync(join(wsDir, "hewar.json"), JSON.stringify(fx.manifest));
    const env = { SPACE_GITHUB_WEBHOOK_SECRET: "s3cret" } as NodeJS.ProcessEnv;
    const body = JSON.stringify({
      action: "created",
      repository: { full_name: "acme/hewar" },
      sender: { login: "vaishnav", type: "User" },
      issue: { number: 184, title: "Upload fails", html_url: "u" },
      comment: { id: 1, body: "@hewar-agent investigate" },
    });
    const sig = `sha256=${createHmac("sha256", "s3cret").update(body).digest("hex")}`;

    const rt = createSpaceRuntime({ stateDir, workspacesDir: wsDir, env });
    expect(
      rt.webhook(body, { "x-github-event": "issue_comment", "x-hub-signature-256": "sha256=00" }),
    ).toMatchObject({ status: 401 });
    const ok = rt.webhook(body, {
      "x-github-event": "issue_comment",
      "x-hub-signature-256": sig,
      "x-github-delivery": "d-1",
    });
    expect(ok).toMatchObject({ status: 202, outcome: { kind: "task-created" } });
    expect(rt.describe("184")).toContain("Issue #184");
    rt.close();

    // "Restart": a fresh runtime over the same state directory sees the task.
    const rt2 = createSpaceRuntime({ stateDir, workspacesDir: wsDir, env });
    expect(rt2.findTask("#184")?.status).toBe("REPORTED");
    // Without a bound agent the tick blocks the task with an exact reason instead of faking progress.
    const ticked = await rt2.tick();
    expect(ticked[0]?.status).toBe("BLOCKED");
    expect(rt2.describe("184")).toMatch(/agent unavailable/);
    expect(rt2.timeline("184")).toContain("Not posted (GitHub unavailable): Investigating");
    rt2.close();
  });

  it("space_task: reads freely, mutates only on the owner's request", async () => {
    const fx = createHewarFixture();
    const wsDir = tmp();
    writeFileSync(join(wsDir, "hewar.json"), JSON.stringify(fx.manifest));
    const rt = createSpaceRuntime({ stateDir: tmp(), workspacesDir: wsDir, env: {} });
    rt.tasks.create({
      workspaceId: "hewar",
      source: { kind: "github-issue", repo: "acme/hewar", issueNumber: 5, url: "u" },
      reporter: {},
      report: "x",
    });
    const call = (owner: boolean, p: Record<string, unknown>) =>
      createSpaceTaskTool(
        () => rt,
        () => owner,
      ).execute("1", p);
    await expect(call(false, { action: "status", task: "5" })).resolves.toBeDefined();
    await expect(call(false, { action: "stop", task: "5" })).rejects.toThrow(/ask for it directly/);
    await expect(call(true, { action: "stop", task: "5" })).resolves.toBeDefined();
    expect(rt.findTask("5")?.status).toBe("BLOCKED");
  });
});
