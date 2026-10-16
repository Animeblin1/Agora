/**
 * One floating scrollbar owns paint and pointer input for its scrollable host.
 * Native bars are hidden without a layout gutter; wheel and keyboard remain with the host.
 */

/** Draws one overlay and returns the detach function owned by the presentation. */
export function attachScrollbar(host, suppliedBar, onScrollInput) {
  const bar = suppliedBar ?? document.createElement("div");
  if (!suppliedBar) {
    bar.className = "scrollbar-overlay";
    bar.setAttribute("aria-hidden", "true");
    bar.append(document.createElement("span"));
    host.parentElement.append(bar);
  }
  const hadHostClass = host.classList.contains("scrollbar-host");
  host.classList.add("scrollbar-host");
  const controller = new AbortController();
  const thumb = bar.firstElementChild;
  const place = () => {
    const box = host.getBoundingClientRect();
    const parentNode = bar.offsetParent;
    const parent = parentNode?.getBoundingClientRect() ?? box;
    const scaleX = parentNode?.offsetWidth ? parent.width / parentNode.offsetWidth : 1;
    const scaleY = parentNode?.offsetHeight ? parent.height / parentNode.offsetHeight : 1;
    bar.style.top = `${(box.top - parent.top) / (scaleY || 1)}px`;
    bar.style.height = `${box.height / (scaleY || 1)}px`;
    bar.style.left = `${(box.right - parent.left) / (scaleX || 1) - bar.offsetWidth}px`;
    bar.style.right = "auto";
  };
  const paint = () => {
    const scrollable = host.scrollHeight - host.clientHeight;
    bar.classList.toggle("scrollable", scrollable > 1);
    if (scrollable <= 1) return;
    const height = Math.min(host.clientHeight, Math.max(28, host.clientHeight ** 2 / host.scrollHeight));
    thumb.style.top = `${(host.scrollTop / scrollable) * (host.clientHeight - height)}px`;
    thumb.style.height = `${height}px`;
  };
  let drag = null;
  const pointerDown = (event) => {
    if (event.button !== 0 || !bar.classList.contains("scrollable") || host.closest("[inert]")) return;
    event.preventDefault();
    event.stopPropagation();
    host.dispatchEvent(new Event("pointerdown"));
    onScrollInput?.(0);
    bar.setPointerCapture(event.pointerId);
    const box = thumb.getBoundingClientRect();
    const onThumb = event.clientY >= box.top - 8 && event.clientY <= box.bottom + 8;
    if (!onThumb) {
      const travel = host.clientHeight - thumb.offsetHeight;
      if (travel > 0) {
        const target = ((event.clientY - bar.getBoundingClientRect().top - box.height / 2) / travel) *
          (host.scrollHeight - host.clientHeight);
        onScrollInput?.(Math.sign(target - host.scrollTop));
        host.scrollTop = target;
      }
    }
    drag = { id: event.pointerId, y: event.clientY, top: host.scrollTop };
    bar.classList.add("dragging");
  };
  const pointerMove = (event) => {
    if (drag?.id !== event.pointerId) return;
    const scrollable = host.scrollHeight - host.clientHeight;
    const travel = host.clientHeight - thumb.offsetHeight;
    if (scrollable <= 0 || travel <= 0) return;
    const target = drag.top + ((event.clientY - drag.y) / travel) * scrollable;
    onScrollInput?.(Math.sign(target - host.scrollTop));
    host.scrollTop = target;
  };
  const pointerUp = (event) => {
    if (drag?.id !== event.pointerId) return;
    drag = null;
    bar.classList.remove("dragging");
    if (bar.hasPointerCapture(event.pointerId)) bar.releasePointerCapture(event.pointerId);
  };
  const entered = (event) => { if (event.pointerType === "mouse") bar.classList.add("hover"); };
  const touched = (event) => { if (event.pointerType === "touch") bar.classList.remove("hover"); };
  const left = (event) => {
    if (event.relatedTarget instanceof Node && (host.contains(event.relatedTarget) || bar.contains(event.relatedTarget))) return;
    bar.classList.remove("hover");
  };
  for (const [type, listener] of [["pointerdown", pointerDown], ["pointermove", pointerMove],
    ["pointerup", pointerUp], ["pointercancel", pointerUp], ["lostpointercapture", pointerUp],
    ["pointerenter", entered], ["pointerleave", left]]) {
    bar.addEventListener(type, listener, { signal: controller.signal });
  }
  bar.addEventListener("wheel", (event) => {
    event.preventDefault();
    const forwarded = new WheelEvent("wheel", { deltaY: event.deltaY, deltaX: event.deltaX,
      deltaMode: event.deltaMode, bubbles: true, cancelable: true });
    if (!host.dispatchEvent(forwarded)) return;
    const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? host.clientHeight : 1;
    host.scrollTop += event.deltaY * unit;
  }, { passive: false, signal: controller.signal });
  host.addEventListener("scroll", paint, { passive: true, signal: controller.signal });
  host.addEventListener("input", paint, { signal: controller.signal });
  host.addEventListener("pointerenter", entered, { signal: controller.signal });
  host.addEventListener("pointerdown", touched, { signal: controller.signal });
  host.addEventListener("pointerleave", left, { signal: controller.signal });
  const refresh = () => { place(); paint(); };
  document.addEventListener("scroll", refresh, { capture: true, passive: true, signal: controller.signal });
  window.addEventListener("resize", refresh, { signal: controller.signal });
  const observer = new ResizeObserver(refresh);
  observer.observe(host);
  let content = new Set();
  const watchContent = () => {
    const next = new Set(host.children);
    for (const node of content) if (!next.has(node)) observer.unobserve(node);
    for (const node of next) if (!content.has(node)) observer.observe(node);
    content = next;
    refresh();
  };
  const children = new MutationObserver(watchContent);
  children.observe(host, { childList: true, subtree: true, characterData: true });
  watchContent();
  if (matchMedia("(hover: hover) and (pointer: fine)").matches && host.matches(":hover")) bar.classList.add("hover");
  return () => {
    controller.abort();
    children.disconnect();
    observer.disconnect();
    if (!hadHostClass) host.classList.remove("scrollbar-host");
    if (!suppliedBar) bar.remove();
  };
}
