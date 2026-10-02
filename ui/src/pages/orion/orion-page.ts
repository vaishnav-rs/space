import { consume } from "@lit/context";
import { html, nothing, type TemplateResult } from "lit";
import { state } from "lit/decorators.js";
import { applicationContext, type ApplicationContext } from "../../app/context.ts";
import {
  renderSettingsEmpty,
  renderSettingsLoadingSkeleton,
  renderSettingsPage,
  renderSettingsPageHeader,
} from "../../components/settings-ui.ts";
import { renderSettingsWorkspace } from "../../components/settings-workspace.ts";
import { formatUiError } from "../../lib/format-error.ts";
import { GatewayPageController } from "../../lit/gateway-page-controller.ts";
import { OpenClawLightDomElement } from "../../lit/openclaw-element.ts";
import {
  ROLE_BLURB,
  ROLE_LABEL,
  type OrionConnector,
  type OrionOverview,
  type OrionRole,
} from "./types.ts";

type Ready = Extract<OrionOverview, { configured: true }>;
type Tab = "overview" | "people" | "connections" | "tasks" | "workspaces" | "activity";

const TAB_LABEL: Record<Tab, string> = {
  overview: "Overview",
  people: "People",
  connections: "Connections",
  tasks: "Tasks",
  workspaces: "Workspaces",
  activity: "Activity",
};

const FIELD_LABEL: Record<string, string> = {
  clientId: "OAuth client ID",
  clientSecret: "OAuth client secret",
  refreshToken: "Refresh token",
  token: "Access token",
  apiKey: "API key",
  from: "From address",
  account: "Account label (e.g. the number)",
};

const SECRET_FIELDS = new Set(["clientSecret", "refreshToken", "token", "apiKey"]);

const ago = (iso: string | undefined): string => {
  if (!iso) return "";
  const s = Math.max(0, (Date.now() - Date.parse(iso)) / 1000);
  return s < 90
    ? "just now"
    : s < 5400
      ? `${Math.round(s / 60)}m ago`
      : s < 129600
        ? `${Math.round(s / 3600)}h ago`
        : `${Math.round(s / 86400)}d ago`;
};

class OrionAdminPage extends OpenClawLightDomElement {
  @consume({ context: applicationContext, subscribe: true })
  private context!: ApplicationContext;

  @state() private data: OrionOverview | null = null;
  @state() private loading = false;
  @state() private busy = false;
  @state() private error = "";
  @state() private notice = "";
  @state() private tab: Tab = "overview";
  @state() private connect: {
    kind: string;
    scope: "org" | "user";
    values: Record<string, string>;
  } | null = null;
  @state() private expandedTask = "";
  @state() private newMember = {
    profileId: "",
    role: "member" as OrionRole,
    workspaces: "",
    githubLogin: "",
    addresses: "",
  };

  private generation = 0;

  private readonly gateway = new GatewayPageController(this, {
    getGateway: () => this.context?.gateway,
    invalidateRequests: () => {
      this.generation++;
      this.data = null;
    },
    ensureInitialData: () => void this.load(),
  });

  private async load() {
    const scope = this.gateway.capture();
    if (!scope) return;
    const generation = ++this.generation;
    this.loading = true;
    try {
      const result = await scope.client.request<OrionOverview>("orion.admin.overview", {});
      if (generation === this.generation && this.gateway.isCurrent(scope)) {
        this.data = result;
        this.error = "";
      }
    } catch (e) {
      if (generation === this.generation && this.gateway.isCurrent(scope))
        this.error = formatUiError(e);
    } finally {
      if (generation === this.generation) this.loading = false;
    }
  }

  private async apply(op: Record<string, unknown>, done: string) {
    const scope = this.gateway.capture();
    if (!scope || this.busy) return false;
    this.busy = true;
    this.error = "";
    this.notice = "";
    try {
      await scope.client.request("orion.admin.apply", op);
      if (!this.gateway.isCurrent(scope)) return false;
      this.notice = done;
      await this.load();
      return true;
    } catch (e) {
      if (this.gateway.isCurrent(scope)) this.error = formatUiError(e);
      return false;
    } finally {
      this.busy = false;
    }
  }

  private can(d: Ready, permission: string): boolean {
    return d.me.permissions.includes(permission);
  }

  private tabs(d: Ready): Tab[] {
    const t: Tab[] = ["overview", "connections", "tasks"];
    if (this.can(d, "members.manage")) t.splice(1, 0, "people");
    if (this.can(d, "connectors.audit")) t.push("workspaces", "activity");
    return t;
  }

  private pill(text: string, tone: "ok" | "warn" | "bad" | "muted" | "accent" = "muted") {
    return html`<span class="oa-pill oa-pill--${tone}">${text}</span>`;
  }

  private statusTone(status: string): "ok" | "warn" | "bad" | "accent" | "muted" {
    if (status === "READY_FOR_HUMAN" || status === "COMPLETED") return "ok";
    if (status === "FAILED" || status === "BLOCKED") return "bad";
    if (status.startsWith("NEEDS")) return "warn";
    return "accent";
  }

  private renderHealth(d: Ready) {
    const item = (ok: boolean, label: string, hint: string) =>
      html`<div class="oa-health__item ${ok ? "is-ok" : "is-off"}" title=${hint}>
        <span class="oa-dot"></span>${label}
      </div>`;
    return html`<div class="oa-health">
      ${item(d.vaultAvailable, "Credential vault", "Set ORION_VAULT_KEY to enable encrypted connections")}
      ${item(d.health.githubToken, "GitHub identity", "Set ORION_GITHUB_TOKEN for the engineering agent")}
      ${item(d.health.webhookSecret, "Webhook secret", "Set ORION_GITHUB_WEBHOOK_SECRET so issue mentions are accepted")}
      ${item(d.health.agentBound, "Engineering runtime", "Set ORION_STATE_DIR and ORION_WORKSPACES_DIR")}
    </div>`;
  }

  private renderOverview(d: Ready) {
    const active = d.tasks.filter((t) => !["COMPLETED", "FAILED"].includes(t.status));
    const waiting = d.tasks.filter(
      (t) =>
        t.status.startsWith("NEEDS") || t.status === "BLOCKED" || t.status === "READY_FOR_HUMAN",
    );
    const stat = (n: number | string, label: string) =>
      html`<div class="oa-stat">
        <div class="oa-stat__n">${n}</div>
        <div class="oa-stat__l">${label}</div>
      </div>`;
    const connected = d.connectors.filter((c) => c.org.connected || c.mine.connected).length;
    return html`
      <div class="oa-stats">
        ${stat(d.members.length, this.can(d, "members.manage") ? "People" : "You")}
        ${stat(`${connected}/${d.connectors.length}`, "Connections live")}
        ${stat(active.length, "Active tasks")} ${stat(waiting.length, "Waiting on a person")}
      </div>
      <h3 class="oa-h">What your role can do</h3>
      <div class="oa-card">
        <div class="oa-row">
          <div>
            <strong>${ROLE_LABEL[d.me.role]}</strong>
            <div class="oa-muted">${ROLE_BLURB[d.me.role]}</div>
          </div>
        </div>
        <div class="oa-chips">
          ${d.me.permissions.map((p) => html`<span class="oa-chip">${p}</span>`)}
        </div>
      </div>
      <h3 class="oa-h">Roles</h3>
      <div class="oa-grid">
        ${d.roles.map(
          (r) =>
            html`<div class="oa-card">
              <div class="oa-row"><strong>${ROLE_LABEL[r.role]}</strong></div>
              <div class="oa-muted">${ROLE_BLURB[r.role]}</div>
            </div>`,
        )}
      </div>
      ${
        waiting.length
          ? html`<h3 class="oa-h">Needs attention</h3>
              <div class="oa-card">
                ${waiting.slice(0, 5).map(
                  (t) =>
                    html`<div class="oa-row">
                      <div>
                        <div>${t.title}</div>
                        <div class="oa-muted">${t.workspace} · ${t.source}</div>
                      </div>
                      ${this.pill(t.status.replaceAll("_", " ").toLowerCase(), this.statusTone(t.status))}
                    </div>`,
                )}
              </div>`
          : nothing
      }
    `;
  }

  private renderPeople(d: Ready) {
    const roles = d.roles.map((r) => r.role);
    const form = this.newMember;
    const set = (patch: Partial<typeof form>) => (this.newMember = { ...form, ...patch });
    const csv = (v: string) =>
      v
        .split(",")
        .map((x) => x.trim())
        .filter(Boolean);
    return html` <div class="oa-card oa-table">
        <div class="oa-thead">
          <span>Person</span><span>Role</span><span>Workspaces</span><span>GitHub</span
          ><span></span>
        </div>
        ${d.members.map(
          (m) => html`<div class="oa-trow">
            <span class="oa-mono"
              >${m.profileId}${m.profileId === d.me.profileId ? html` ${this.pill("you", "accent")}` : nothing}</span
            >
            <span>
              <select
                class="oa-input"
                .value=${m.role}
                ?disabled=${this.busy}
                @change=${(e: Event) => void this.apply({ op: "member.set", profileId: m.profileId, role: (e.target as HTMLSelectElement).value }, `Updated ${m.profileId}`)}
              >
                ${roles.map((r) => html`<option value=${r} ?selected=${r === m.role}>${ROLE_LABEL[r]}</option>`)}
              </select>
            </span>
            <span class="oa-chips"
              >${m.workspaces.length ? m.workspaces.map((w) => html`<span class="oa-chip">${w}</span>`) : html`<span class="oa-muted">none</span>`}</span
            >
            <span class="oa-muted">${m.githubLogin ?? "—"}</span>
            <span
              ><button
                class="btn btn--sm btn--ghost"
                ?disabled=${this.busy}
                @click=${() => confirm(`Remove ${m.profileId}?`) && void this.apply({ op: "member.remove", profileId: m.profileId }, `Removed ${m.profileId}`)}
              >
                Remove
              </button></span
            >
          </div>`,
        )}
      </div>
      <h3 class="oa-h">Add or update a person</h3>
      <div class="oa-card oa-form">
        <label
          >Profile ID<input
            class="oa-input"
            .value=${form.profileId}
            @input=${(e: Event) => set({ profileId: (e.target as HTMLInputElement).value })}
            placeholder="their gateway profile id"
        /></label>
        <label
          >Role<select
            class="oa-input"
            .value=${form.role}
            @change=${(e: Event) => set({ role: (e.target as HTMLSelectElement).value as OrionRole })}
          >
            ${roles.map((r) => html`<option value=${r}>${ROLE_LABEL[r]}</option>`)}
          </select></label
        >
        <label
          >Workspaces<input
            class="oa-input"
            .value=${form.workspaces}
            @input=${(e: Event) => set({ workspaces: (e.target as HTMLInputElement).value })}
            placeholder="hewar, other"
        /></label>
        <label
          >GitHub login<input
            class="oa-input"
            .value=${form.githubLogin}
            @input=${(e: Event) => set({ githubLogin: (e.target as HTMLInputElement).value })}
        /></label>
        <label class="oa-form__wide"
          >Their own emails / numbers (Orion may contact only these on its own)<input
            class="oa-input"
            .value=${form.addresses}
            @input=${(e: Event) => set({ addresses: (e.target as HTMLInputElement).value })}
            placeholder="me@acme.com, +15550001111"
        /></label>
        <div class="oa-form__actions">
          <button
            class="btn primary"
            ?disabled=${this.busy || !form.profileId.trim()}
            @click=${async () => {
              const ok = await this.apply(
                {
                  op: "member.set",
                  profileId: form.profileId.trim(),
                  role: form.role,
                  workspaces: csv(form.workspaces),
                  ...(form.githubLogin.trim() ? { githubLogin: form.githubLogin.trim() } : {}),
                  addresses: csv(form.addresses),
                },
                `Saved ${form.profileId.trim()}`,
              );
              if (ok)
                this.newMember = {
                  profileId: "",
                  role: "member",
                  workspaces: "",
                  githubLogin: "",
                  addresses: "",
                };
            }}
          >
            Save person
          </button>
        </div>
      </div>`;
  }

  private renderConnectForm(c: OrionConnector) {
    const f = this.connect;
    if (!f || f.kind !== c.kind) return nothing;
    return html`<form
      class="oa-form oa-connect"
      autocomplete="off"
      @submit=${(e: Event) => {
        e.preventDefault();
        void this.apply(
          { op: "connector.connect", connector: c.kind, scope: f.scope, fields: f.values },
          `${c.label} connected (${f.scope === "org" ? "organization" : "personal"})`,
        ).then((ok) => ok && (this.connect = null));
      }}
    >
      <div class="oa-muted oa-form__wide">
        Credentials go straight to the gateway's encrypted vault. They are never shown again.
      </div>
      ${c.fields.map((name) => html`<label>${FIELD_LABEL[name] ?? name}<input class="oa-input" type=${SECRET_FIELDS.has(name) ? "password" : "text"} autocomplete="off" .value=${f.values[name] ?? ""} @input=${(e: Event) => (this.connect = { ...f, values: { ...f.values, [name]: (e.target as HTMLInputElement).value } })} /></label>`)}
      <div class="oa-form__actions">
        <button type="button" class="btn btn--ghost" @click=${() => (this.connect = null)}>
          Cancel</button
        ><button
          type="submit"
          class="btn primary"
          ?disabled=${this.busy || c.fields.some((n) => !f.values[n]?.trim())}
        >
          Save securely
        </button>
      </div>
    </form>`;
  }

  private renderConnection(d: Ready, c: OrionConnector) {
    const admin = this.can(d, "connectors.org.manage");
    const orgOnly = !c.scopes.includes("user");
    const start = (scope: "org" | "user") => (this.connect = { kind: c.kind, scope, values: {} });
    return html`<div class="oa-card oa-conn">
      <div class="oa-row">
        <div>
          <strong>${c.label}</strong
          >${orgOnly ? html` ${this.pill("organization only", "accent")}` : nothing}
        </div>
      </div>
      ${
        c.scopes.includes("org")
          ? html`<div class="oa-row">
              <div>
                <div>Organization</div>
                <div class="oa-muted">
                  ${c.org.connected ? `Connected ${ago(c.org.updatedAt)}${c.org.by ? ` by ${c.org.by}` : ""}` : "Not connected"}
                </div>
              </div>
              <div class="oa-actions">
                ${c.org.connected ? this.pill("connected", "ok") : this.pill("not connected", "muted")}
                ${admin ? html`<button class="btn btn--sm" ?disabled=${this.busy} @click=${() => start("org")}>${c.org.connected ? "Replace" : "Connect"}</button>${c.org.connected ? html`<button class="btn btn--sm btn--ghost" ?disabled=${this.busy} @click=${() => confirm(`Disconnect organization ${c.label}?`) && void this.apply({ op: "connector.disconnect", connector: c.kind, scope: "org" }, `${c.label} disconnected`)}>Disconnect</button>` : nothing}` : nothing}
              </div>
            </div>`
          : nothing
      }
      ${
        c.scopes.includes("user")
          ? html`<div class="oa-row">
              <div>
                <div>You</div>
                <div class="oa-muted">
                  ${!c.policy.userScope ? "Personal connections are turned off by your admin" : c.mine.connected ? `Connected ${ago(c.mine.updatedAt)}` : c.policy.orgFallback && c.org.connected ? "Not connected — using the organization's" : "Not connected"}
                </div>
              </div>
              <div class="oa-actions">
                ${c.mine.connected ? this.pill("yours", "ok") : nothing}
                ${c.policy.userScope && this.can(d, "connectors.user.manage") ? html`<button class="btn btn--sm" ?disabled=${this.busy} @click=${() => start("user")}>${c.mine.connected ? "Replace" : "Connect mine"}</button>${c.mine.connected ? html`<button class="btn btn--sm btn--ghost" ?disabled=${this.busy} @click=${() => void this.apply({ op: "connector.disconnect", connector: c.kind, scope: "user" }, `Your ${c.label} was disconnected`)}>Disconnect</button>` : nothing}` : nothing}
              </div>
            </div>`
          : nothing
      }
      ${this.renderConnectForm(c)}
      ${
        admin && c.scopes.includes("user")
          ? html`<div class="oa-policy">
              <label class="oa-switch"
                ><input
                  type="checkbox"
                  .checked=${c.policy.userScope}
                  ?disabled=${this.busy}
                  @change=${(e: Event) => void this.apply({ op: "connector.policy", connector: c.kind, userScope: (e.target as HTMLInputElement).checked }, "Policy updated")}
                /><span>Let people connect their own</span></label
              >
              <label class="oa-switch"
                ><input
                  type="checkbox"
                  .checked=${c.policy.orgFallback}
                  ?disabled=${this.busy}
                  @change=${(e: Event) => void this.apply({ op: "connector.policy", connector: c.kind, orgFallback: (e.target as HTMLInputElement).checked }, "Policy updated")}
                /><span>People without their own may use the organization's</span></label
              >
            </div>`
          : nothing
      }
      ${
        c.people?.length
          ? html`<div class="oa-people">
              <div class="oa-muted">Personal connections</div>
              ${c.people.map((p) => html`<div class="oa-row"><span class="oa-mono">${p.userId}</span><span class="oa-muted">${ago(p.updatedAt)}</span><button class="btn btn--sm btn--ghost" ?disabled=${this.busy} @click=${() => confirm(`Revoke ${p.userId}'s ${c.label}?`) && void this.apply({ op: "connector.disconnect", connector: c.kind, scope: "user", userId: p.userId }, `Revoked ${p.userId}'s ${c.label}`)}>Revoke</button></div>`)}
            </div>`
          : nothing
      }
    </div>`;
  }

  private renderTasks(d: Ready) {
    if (!d.tasks.length)
      return html`<div class="oa-empty">
        No tasks yet. Ask Orion to investigate a bug, or mention it on a GitHub issue.
      </div>`;
    const canSteer = this.can(d, "tasks.steer");
    const canApprove = this.can(d, "tasks.approve");
    return html`<div class="oa-card oa-tasks">
      ${d.tasks.map((t) => {
        const open = this.expandedTask === t.id;
        return html`<div class="oa-task">
          <div class="oa-row oa-task__head" @click=${() => (this.expandedTask = open ? "" : t.id)}>
            <div>
              <div>${t.title || "(no title)"}</div>
              <div class="oa-muted">
                ${t.workspace} · ${t.source}${t.owner ? ` · ${t.owner}` : ""} ·
                ${ago(t.updatedAt)}${t.sharedWith.length ? ` · shared with ${t.sharedWith.join(", ")}` : ""}
              </div>
            </div>
            ${this.pill(t.status.replaceAll("_", " ").toLowerCase(), this.statusTone(t.status))}
          </div>
          ${
            open
              ? html`<pre class="oa-pre">${t.summary}</pre>
                  <div class="oa-actions">
                    ${t.pullRequest ? html`<a class="btn btn--sm" href=${t.pullRequest} target="_blank" rel="noreferrer noopener">Open PR</a>` : nothing}
                    ${canSteer ? html`<button class="btn btn--sm" ?disabled=${this.busy} @click=${() => void this.apply({ op: "task.retry", taskId: t.id }, "Retrying")}>Retry</button><button class="btn btn--sm btn--ghost" ?disabled=${this.busy} @click=${() => void this.apply({ op: "task.stop", taskId: t.id }, "Stopped")}>Stop</button>` : nothing}
                    ${
                      canApprove && t.status === "NEEDS_APPROVAL"
                        ? html`<button
                            class="btn btn--sm primary"
                            ?disabled=${this.busy}
                            @click=${() => {
                              const cap = prompt("Capability to approve (e.g. prod.restart)");
                              if (cap)
                                void this.apply(
                                  { op: "task.approve", taskId: t.id, capability: cap },
                                  `Approved ${cap}`,
                                );
                            }}
                          >
                            Approve…
                          </button>`
                        : nothing
                    }
                    ${
                      t.canShare
                        ? html`<button
                            class="btn btn--sm"
                            ?disabled=${this.busy}
                            @click=${() => {
                              const who = prompt("Share with (profile id)");
                              if (who)
                                void this.apply(
                                  { op: "task.share", taskId: t.id, with: who },
                                  `Shared with ${who}`,
                                );
                            }}
                          >
                            Share…
                          </button>`
                        : nothing
                    }
                  </div>`
              : nothing
          }
        </div>`;
      })}
    </div>`;
  }

  private renderWorkspaces(d: Ready) {
    if (!d.workspaces.length)
      return html`<div class="oa-empty">
        No workspaces configured. Add manifests to ORION_WORKSPACES_DIR.
      </div>`;
    return html`<div class="oa-grid">
      ${d.workspaces.map(
        (w) => html`<div class="oa-card">
          <div class="oa-row">
            <div>
              <strong>${w.name}</strong>
              <div class="oa-muted">${w.kind}${w.repo ? ` · ${w.repo}` : ""}</div>
            </div>
            ${w.production ? this.pill("production access", "warn") : nothing}
          </div>
          <div class="oa-muted">Allowed without asking</div>
          <div class="oa-chips">
            ${w.grants.map((g) => html`<span class="oa-chip">${g}</span>`)}
          </div>
          ${
            w.requireApproval.length
              ? html`<div class="oa-muted">Needs human approval</div>
                  <div class="oa-chips">
                    ${w.requireApproval.map((g) => html`<span class="oa-chip oa-chip--warn">${g}</span>`)}
                  </div>`
              : nothing
          }
          <div class="oa-muted">People with access</div>
          <div class="oa-chips">
            ${w.members.map((m) => html`<span class="oa-chip">${m}</span>`)}
          </div>
        </div>`,
      )}
    </div>`;
  }

  private renderActivity(d: Ready) {
    const rows = d.audit ?? [];
    if (!rows.length) return html`<div class="oa-empty">No activity yet.</div>`;
    return html`<div class="oa-card oa-table">
      <div class="oa-thead oa-thead--audit">
        <span>When</span><span>Who</span><span>What</span><span>Connector</span>
      </div>
      ${rows.map((r) => html`<div class="oa-trow oa-trow--audit"><span class="oa-muted">${ago(r.at)}</span><span class="oa-mono">${r.actor}</span><span>${r.action}</span><span>${r.connector ?? ""}${r.scope && r.scope !== "org:" ? ` · ${r.scope.replace("user:", "")}` : r.scope === "org:" ? " · org" : ""}</span></div>`)}
    </div>`;
  }

  private renderBody(d: Ready): TemplateResult {
    switch (this.tab) {
      case "people":
        return this.renderPeople(d);
      case "connections":
        return html`<div class="oa-grid oa-grid--wide">
          ${d.connectors.map((c) => this.renderConnection(d, c))}
        </div>`;
      case "tasks":
        return this.renderTasks(d);
      case "workspaces":
        return this.renderWorkspaces(d);
      case "activity":
        return this.renderActivity(d);
      default:
        return this.renderOverview(d);
    }
  }

  override render() {
    if (!this.context) return nothing;
    const d = this.data;
    let body: unknown;
    if (!this.gateway.connected)
      body = renderSettingsEmpty("Connect to the gateway to manage Orion.");
    else if (!d)
      body = this.error
        ? html`<div role="alert" class="callout danger">
            ${this.error}
            <button class="btn btn--sm" @click=${() => void this.load()}>Retry</button>
          </div>`
        : renderSettingsLoadingSkeleton({ rows: 4 });
    else if (!d.configured)
      body = html`<div class="oa-card oa-setup">
        <h3 class="oa-h">Set up Orion access control</h3>
        <p class="oa-muted">${d.reason}</p>
        <pre class="oa-pre">
ORION_STATE_DIR=~/.orion
ORION_VAULT_KEY=$(openssl rand -base64 32)</pre>
      </div>`;
    else if (d.needsBootstrap)
      body = html`<div class="oa-card oa-setup">
        <h3 class="oa-h">Claim ownership</h3>
        <p class="oa-muted">
          No owner exists yet. The first person to claim it becomes the Orion owner and can add
          everyone else.
        </p>
        <button
          class="btn primary"
          ?disabled=${this.busy}
          @click=${() => void this.apply({ op: "bootstrap" }, "You are the owner")}
        >
          I'm the owner
        </button>
      </div>`;
    else {
      const tabs = this.tabs(d);
      const tab = tabs.includes(this.tab) ? this.tab : "overview";
      if (tab !== this.tab) this.tab = tab;
      body = html` <div class="oa-top">
          <div class="oa-who">
            <span class="oa-avatar">${d.me.profileId.slice(0, 1).toUpperCase()}</span>
            <div>
              <div class="oa-mono">${d.me.profileId}</div>
              <div class="oa-muted">${ROLE_LABEL[d.me.role]}</div>
            </div>
          </div>
          ${this.renderHealth(d)}
        </div>
        <div class="oa-tabs" role="tablist">
          ${tabs.map((x) => html`<button role="tab" aria-selected=${x === tab} class="oa-tab ${x === tab ? "is-active" : ""}" @click=${() => (this.tab = x)}>${TAB_LABEL[x]}${x === "tasks" && d.tasks.length ? html`<span class="oa-count">${d.tasks.length}</span>` : nothing}</button>`)}
        </div>
        ${this.error ? html`<div role="alert" class="callout danger">${this.error}</div>` : nothing}
        ${this.notice ? html`<div role="status" class="callout oa-notice">${this.notice}</div>` : nothing}
        ${this.renderBody(d)}`;
    }
    return renderSettingsWorkspace(
      renderSettingsPage(
        html`${renderSettingsPageHeader({ title: "Orion admin", subtitle: "People, roles, connections and engineering tasks." })}
          <div class="oa ${this.loading ? "is-loading" : ""}">${body}</div>`,
        { wide: true },
      ),
    );
  }
}

if (!customElements.get("orion-admin-page"))
  customElements.define("orion-admin-page", OrionAdminPage);
