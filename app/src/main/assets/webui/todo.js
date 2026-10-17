import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { DetailSheet } from "./detail-sheet.js";
import { icon, ICON_CHECK } from "./icons.js";

const CHECKLIST = "M22 7h-9V5h9v2zm0 8h-9v-2h9v2zm0 6h-9v-2h9v2zM10.54 3 6.99 6.54 5.58 5.13 4.17 6.54l2.82 2.83 4.96-4.96L10.54 3zm0 8-3.55 3.54-1.41-1.41-1.41 1.41 2.82 2.83 4.96-4.96L10.54 11z";
const PENDING = "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z";
const PROGRESS = "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67V7z";

/** Room owns the list; only the outgoing capsule retains an invalidated owner's payload. */
export function TodoBar({ state }) {
  const owner = `${state.connectionId || ""}:${state.openId || ""}:${state.composer?.seq ?? ""}`;
  const items = state.connected && state.openId ? state.todo || [] : [];
  const desired = useRef(null);
  desired.current = items.length ? { owner, items } : null;
  const [shown, setShown] = useState(desired.current);
  const [opened, setOpened] = useState(false);
  const selected = useRef(owner);
  const host = useRef(null), button = useRef(null);
  const motion = useRef({ progress: 0, frame: 0 });
  const reduced = !!state.display?.reduceMotion;
  const active = !!items.length && shown?.owner === owner;
  const displayed = active ? items : shown?.items || [];
  const completed = displayed.filter(item => item.status === "completed").length;
  const progress = t.todoProgress(completed, displayed.length);
  useLayoutEffect(() => {
    if (selected.current !== owner || !active) setOpened(false);
    selected.current = owner;
    if (active && shown.items !== items) setShown({ owner, items });
    else if (!shown && desired.current) setShown(desired.current);
  }, [owner, active, items, shown]);
  useLayoutEffect(() => {
    const node = host.current, capsule = button.current;
    if (!node || !capsule) return;
    const values = motion.current;
    const chat = node.closest(".chat"), composer = node.closest(".composer-host");
    const from = values.progress, to = active ? 1 : 0, started = performance.now();
    const draw = () => {
      const lift = (capsule.offsetHeight + 8) * (reduced ? active ? 1 : 0 : values.progress);
      chat?.style.setProperty("--chat-todo-lift", `${lift}px`);
      composer?.style.setProperty("--chat-todo-lift", `${lift}px`);
      capsule.style.opacity = String(values.progress);
      capsule.style.transform = `scale(${reduced ? 1 : .96 + .04 * values.progress})`;
    };
    const observer = new ResizeObserver(draw);
    observer.observe(capsule);
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
    const chat = node.closest(".chat"), composer = node.closest(".composer-host");
    return () => {
      chat?.style.removeProperty("--chat-todo-lift");
      composer?.style.removeProperty("--chat-todo-lift");
    };
  }, [!!shown]);
  if (!shown) return null;
  return html`<div class="todo-host" ref=${host} data-owner=${shown.owner}>
    <button type="button" class="todo-capsule" ref=${button} disabled=${!active}
      aria-hidden=${!active ? "true" : null} aria-expanded=${opened && active}
      aria-label=${`${t.todoTitle}, ${progress}`} onClick=${() => { if (active) setOpened(true); }}>
      ${icon(CHECKLIST)}<strong>${t.todoTitle}</strong><span>${progress}</span>
    </button>
    ${opened && active && selected.current === owner && html`<${DetailSheet} title=${`${t.todoTitle} ${progress}`}
      display=${state.display} focusReturn=${button} onClose=${() => setOpened(false)}>
      <div class="todo-list" role="list">
        ${items.map((item, index) => html`<div class="todo-item" role="listitem" key=${index} data-status=${item.status}>
          <span class="todo-status" role="img" aria-label=${t.todoStatuses[item.status]}>
            ${icon(item.status === "completed" ? ICON_CHECK : item.status === "in_progress" ? PROGRESS : PENDING)}
          </span><span class="todo-content">${item.content}</span>
        </div>`)}
      </div>
    </${DetailSheet}>`}
  </div>`;
}
