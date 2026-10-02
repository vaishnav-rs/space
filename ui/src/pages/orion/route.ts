import { definePage } from "@openclaw/uirouter";
import { html } from "lit";
import { routePageSpec } from "../../app-route-paths.ts";

export const page = definePage({
  ...routePageSpec("orion"),
  component: () =>
    import("./orion-page.ts").then(() => ({
      header: true,
      render: () => html`<orion-admin-page></orion-admin-page>`,
    })),
});
