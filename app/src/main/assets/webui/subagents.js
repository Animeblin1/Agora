import { useLayoutEffect, useRef } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";
import { icon, ICON_CHECK, ICON_ERROR, ICON_STOP } from "./icons.js";
import { Spinner } from "./material/progress.js";

export function SubagentsBar({ state }) {
  const host = useRef(null);
  const agents = state.connected && state.openId ? state.subagents || [] : [];
  useLayoutEffect(() => {
    const node = host.current;
    if (!node) return;
    const owners = [node.closest(".chat"), node.closest(".composer-host")];
    const measure = () => owners.forEach(owner => owner?.style.setProperty("--chat-subagent-lift", `${node.offsetHeight}px`));
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    measure();
    return () => { observer.disconnect(); owners.forEach(owner => owner?.style.removeProperty("--chat-subagent-lift")); };
  }, [!!agents.length]);
  if (!agents.length) return null;
  return html`<div class="subagent-host" ref=${host}>
    <div class="subagent-row">
      ${agents.map(agent => html`<button key=${agent.id} type="button" class="subagent-capsule"
        aria-label=${`${agent.title}, ${t.subagentStatuses[agent.status] || t.subagentStatuses.idle}`}
        aria-current=${agent.id === state.openId ? "page" : null}
        onClick=${() => sync.open(agent.id)}>
        ${agent.status === "running" ? html`<${Spinner} size=${18} stroke=${2} />`
          : icon(agent.status === "completed" ? ICON_CHECK : agent.status === "failed" ? ICON_ERROR : ICON_STOP)}
        <span>${agent.title}</span>
      </button>`)}
    </div>
  </div>`;
}
