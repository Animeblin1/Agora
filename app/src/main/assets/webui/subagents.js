import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";
import { icon, ICON_CHECK, ICON_ERROR, ICON_STOP } from "./icons.js";
import { Spinner } from "./material/progress.js";

/** Ordinary selection owns navigation; only the exiting row retains an old owner's paint. */
export function SubagentsBar({ state }) {
  const owner = `${state.connectionId || ""}:${state.openId || ""}:${state.composer?.seq ?? ""}`;
  const agents = state.connected && state.openId ? state.subagents || [] : [];
  const desired = useRef(null);
  desired.current = agents.length ? { owner, agents, conversationId: state.openId } : null;
  const [shown, setShown] = useState(desired.current);
  const host = useRef(null), row = useRef(null);
  const motion = useRef({ progress: 0, frame: 0 });
  const reduced = !!state.display?.reduceMotion;
  const active = !!agents.length && shown?.owner === owner;
  const displayed = active ? agents : shown?.agents || [];
  useLayoutEffect(() => {
    if (active && shown.agents !== agents) setShown(desired.current);
    else if (!shown && desired.current) setShown(desired.current);
  }, [owner, active, agents, shown]);
  useLayoutEffect(() => {
    const node = host.current, content = row.current;
    if (!node || !content) return;
    const values = motion.current;
    const owners = [node.closest(".chat"), node.closest(".composer-host")];
    const from = values.progress, to = active ? 1 : 0, started = performance.now();
    const draw = () => {
      const lift = node.offsetHeight * (reduced ? active ? 1 : 0 : values.progress);
      owners.forEach(owner => owner?.style.setProperty("--chat-subagent-lift", `${lift}px`));
      content.style.opacity = String(values.progress);
      content.style.transform = `scale(${reduced ? 1 : .96 + .04 * values.progress})`;
    };
    const observer = new ResizeObserver(draw);
    observer.observe(node);
    function tick(now) {
      const elapsed = Math.min(1, (now - started) / 180);
      values.progress = from + (to - from) * elapsed;
      draw();
      if (elapsed < 1 && from !== to) values.frame = requestAnimationFrame(tick);
      else if (!active) setShown(desired.current);
    }
    tick(started);
    return () => { cancelAnimationFrame(values.frame); observer.disconnect(); };
  }, [shown?.owner, active, reduced]);
  useLayoutEffect(() => {
    const node = host.current;
    if (!node) return;
    const owners = [node.closest(".chat"), node.closest(".composer-host")];
    return () => owners.forEach(owner => owner?.style.removeProperty("--chat-subagent-lift"));
  }, [!!shown]);
  if (!shown) return null;
  return html`<div class="subagent-host" ref=${host} data-owner=${shown.owner}
    inert=${!active} aria-hidden=${!active ? "true" : null}>
    <div class="subagent-row" ref=${row}>
      ${displayed.map(agent => html`<button key=${agent.id} type="button" class="subagent-capsule"
        disabled=${!active} aria-label=${`${agent.title}, ${t.subagentStatuses[agent.status] || t.subagentStatuses.idle}`}
        aria-current=${agent.id === shown.conversationId ? "page" : null}
        onClick=${() => { if (active) sync.open(agent.id); }}>
        ${agent.status === "running" ? html`<${Spinner} size=${18} stroke=${2} />`
          : icon(agent.status === "completed" ? ICON_CHECK : agent.status === "failed" ? ICON_ERROR : ICON_STOP)}
        <span>${agent.title}</span>
      </button>`)}
    </div>
  </div>`;
}
