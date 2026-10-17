import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { DetailSheet } from "./detail-sheet.js";
import { icon, ICON_CHECK } from "./icons.js";

const CHECKLIST = "M22 7h-9V5h9v2zm0 8h-9v-2h9v2zm0 6h-9v-2h9v2zM10.54 3 6.99 6.54 5.58 5.13 4.17 6.54l2.82 2.83 4.96-4.96L10.54 3zm0 8-3.55 3.54-1.41-1.41-1.41 1.41 2.82 2.83 4.96-4.96L10.54 11z";
const PENDING = "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z";
const PROGRESS = "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67V7z";

/** Room owns the list. This component keeps only the selected sheet's presentation. */
export function TodoBar({ state }) {
  const items = state.connected ? state.todo || [] : [];
  const host = useRef(null), button = useRef(null);
  const [opened, setOpened] = useState(false);
  const owner = `${state.connectionId || ""}:${state.openId || ""}:${state.composer?.seq ?? ""}`;
  const selected = useRef(owner);
  const visible = items.length > 0;
  const completed = items.filter(item => item.status === "completed").length;
  const progress = t.todoProgress(completed, items.length);
  useLayoutEffect(() => {
    if (selected.current !== owner || !visible) setOpened(false);
    selected.current = owner;
  }, [owner, visible]);
  useLayoutEffect(() => {
    const node = host.current;
    if (!node) return;
    const chat = node.closest(".chat"), composer = node.closest(".composer-host");
    const measure = () => {
      const height = node.getBoundingClientRect().height;
      chat?.style.setProperty("--chat-todo-lift", `${height}px`);
      composer?.style.setProperty("--chat-todo-lift", `${height}px`);
    };
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    measure();
    return () => {
      observer.disconnect();
      chat?.style.removeProperty("--chat-todo-lift");
      composer?.style.removeProperty("--chat-todo-lift");
    };
  }, [visible]);
  if (!visible) return null;
  return html`<div class="todo-host" ref=${host}>
    <button type="button" class="todo-capsule" ref=${button} aria-expanded=${opened}
      aria-label=${`${t.todoTitle}, ${progress}`} onClick=${() => setOpened(true)}>
      ${icon(CHECKLIST)}<strong>${t.todoTitle}</strong><span>${progress}</span>
    </button>
    ${opened && selected.current === owner && html`<${DetailSheet} title=${`${t.todoTitle} ${progress}`}
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
