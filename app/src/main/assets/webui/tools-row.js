import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { InteractionBar, tweenProgress } from "./interaction.js";
import { TodoBar } from "./todo.js";
import { SubagentsBar } from "./subagents.js";
import { attachScrollbar } from "./material/scrollbar.js";

const DURATION = 180;
const INSET = 12;
const PADDING = INSET * 2;

/** One horizontal owner preserves the visible anchor across keyed insertions and removals. */
export function attachToolsRow(viewport, reduced, onSettled = () => {}) {
  const track = viewport.firstElementChild, range = track.lastElementChild;
  const chat = viewport.closest(".chat");
  let previous = null, frame = 0, queued = 0, layout = null, advance = null;
  let travel = false, dragging = false, interrupted = false, reveal = null;
  let selection = viewport.dataset?.toolsOwner, family = viewport.dataset?.toolsFamily;
  let replacement = viewport.toolsReplacement || null;
  let replacing = !!replacement || viewport.dataset?.toolsEnter === "false";
  const observed = new Set();
  const items = () => [...track.children].filter(node => node.hasAttribute("data-tools-key"));
  const paintEdges = () => {
    const maximum = Math.max(0, viewport.scrollWidth - viewport.clientWidth);
    const rtl = getComputedStyle(viewport).direction === "rtl";
    const left = rtl ? maximum + viewport.scrollLeft : viewport.scrollLeft;
    const right = rtl ? -viewport.scrollLeft : maximum - viewport.scrollLeft;
    const overflowing = viewport.clientWidth > 0 && items().length > 0 && maximum > 1;
    viewport.style.setProperty("--tools-edge-left", overflowing && left > 2 ? "1" : "0");
    viewport.style.setProperty("--tools-edge-right", overflowing && right > 2 ? "1" : "0");
  };
  const snapshot = () => {
    paintEdges();
    const bounds = viewport.getBoundingClientRect();
    return {
      scroll: viewport.scrollLeft,
      extent: viewport.scrollWidth,
      width: track.offsetWidth,
      nodes: new Map(items().map(node => {
        const rect = node.getBoundingClientRect();
        return [node.dataset.toolsKey, { node, owner: node.dataset.owner,
          width: node.offsetWidth, translate: node.style.translate,
          x: rect.left - bounds.left + viewport.scrollLeft,
          left: rect.left - bounds.left, right: rect.right - bounds.left }];
      })),
    };
  };
  const clearPlacement = () => items().forEach(node => { node.style.translate = ""; });
  const freeze = () => {
    const before = previous || snapshot();
    replacement = { before, geometry: null, styles: new Map(),
      trackWidth: track.style.width, trackMinWidth: track.style.minWidth };
    viewport.toolsReplacement = replacement;
    track.style.width = `${before.width}px`;
    track.style.minWidth = `${before.width}px`;
    range.style.width = `${Math.max(before.extent - INSET, before.width)}px`;
    hold();
  };
  const hold = () => {
    const { before, styles } = replacement;
    items().forEach(node => {
      if (!styles.has(node)) styles.set(node, { width: node.style.width });
      const old = before.nodes.get(node.dataset.toolsKey);
      if (old?.node === node && old.owner === node.dataset.owner) node.style.width = `${old.width}px`;
    });
    items().forEach(node => {
      const old = before.nodes.get(node.dataset.toolsKey);
      if (old?.node !== node || old.owner !== node.dataset.owner) return;
      node.style.translate = old.translate;
      const left = node.getBoundingClientRect().left - viewport.getBoundingClientRect().left;
      node.style.translate = `${(parseFloat(old.translate) || 0) + old.left - left}px 0`;
    });
    viewport.scrollLeft = before.scroll;
  };
  const release = () => {
    replacement.styles.forEach((style, node) => {
      node.style.width = style.width;
      node.style.translate = "";
    });
    track.style.width = replacement.trackWidth;
    track.style.minWidth = replacement.trackMinWidth;
    clearPlacement();
    range.style.width = "0px";
    viewport.scrollLeft = 0;
  };
  const measure = () => {
    cancelAnimationFrame(queued);
    queued = 0;
    const nodes = items(), width = Math.max(0, viewport.clientWidth - PADDING);
    const owner = viewport.dataset?.toolsOwner;
    if (owner !== selection) {
      const nextFamily = viewport.dataset?.toolsFamily;
      const sameFamily = !!family && nextFamily === family;
      selection = owner;
      family = nextFamily;
      if (replacing || !sameFamily) {
        replacing = true;
        if (!replacement) freeze();
        replacement.geometry = null;
        cancelAnimationFrame(frame);
        frame = 0;
        advance = null;
        reveal = null;
        travel = false;
        interrupted = false;
      }
    }
    if (replacing && !replacement) freeze();
    const request = nodes.find(node => node.dataset.toolsReveal !== undefined);
    if (request) {
      const owner = request.dataset.toolsReveal;
      delete request.dataset.toolsReveal;
      if (request.dataset.toolsKey === "interaction" && owner === request.dataset.owner && !dragging) {
        reveal = { owner, from: viewport.scrollLeft, started: performance.now() };
        interrupted = false;
      }
    }
    if (reveal && !nodes.some(node => node.dataset.toolsKey === "interaction" && node.dataset.owner === reveal.owner)) {
      reveal = null;
      interrupted = true;
    }
    viewport.style.setProperty("--tools-viewport-width", `${width}px`);
    const paintHeight = Math.max(0, ...nodes.map(node => node.firstElementChild.offsetHeight));
    const dimensions = nodes.map(node => {
      const natural = node.firstElementChild.offsetHeight;
      const progress = Math.max(0, Math.min(1, Number(node.dataset.toolsProgress) || 0));
      return { height: natural * progress, inset: (natural + 8) * progress };
    });
    const lift = `${Math.max(0, ...dimensions.map(item => item.height))}px`;
    const inset = `${Math.max(0, ...dimensions.map(item => item.inset))}px`;
    const changed = chat?.style.getPropertyValue("--chat-tools-lift") !== lift ||
      chat?.style.getPropertyValue("--chat-tools-message-inset") !== inset;
    chat?.style.setProperty("--chat-tools-lift", lift);
    chat?.style.setProperty("--chat-tools-message-inset", inset);
    viewport.style.height = `${paintHeight ? paintHeight + 16 : 0}px`;
    if (changed) viewport.dispatchEvent(new Event("toolsresize"));
    paintEdges();
    const nextLayout = JSON.stringify([viewport.clientWidth, track.offsetWidth,
      nodes.map(node => [node.dataset.toolsKey, node.dataset.owner, node.offsetLeft, node.offsetWidth])]);
    // Cross-family replacement keeps the last painted track until every exit completes.
    if (replacing) {
      cancelAnimationFrame(frame);
      frame = 0;
      advance = null;
      reveal = null;
      const outgoing = nodes.some(node => node.dataset.toolsExiting === "true");
      if (outgoing) {
        replacement.geometry = null;
        hold();
        return;
      }
      release();
      const geometry = JSON.stringify([viewport.clientWidth, track.offsetWidth,
        nodes.map(node => [node.dataset.toolsKey, node.dataset.owner, node.offsetLeft, node.offsetWidth])]);
      range.style.width = `${track.offsetWidth}px`;
      viewport.scrollLeft = 0;
      layout = geometry;
      previous = snapshot();
      if (replacement.geometry !== geometry) {
        replacement.geometry = geometry;
        queued = requestAnimationFrame(measure);
        return;
      }
      replacing = false;
      replacement = null;
      delete viewport.toolsReplacement;
      interrupted = false;
      onSettled(selection);
      return;
    }
    // Revealing an interaction owns scroll until completion, even while fold/entry geometry changes.
    if (reveal) {
      cancelAnimationFrame(frame);
      frame = 0;
      advance = null;
      clearPlacement();
      layout = nextLayout;
      range.style.width = `${Math.max(viewport.scrollWidth - INSET, track.offsetWidth)}px`;
      const current = reveal;
      travel = true;
      function tick(now) {
        const time = reduced ? 1 : Math.min(1, (now - current.started) / 350);
        viewport.scrollLeft = time === 1 ? 0 : current.from * (1 - tweenProgress(time));
        previous = snapshot();
        if (time < 1) frame = requestAnimationFrame(tick);
        else {
          frame = 0;
          advance = null;
          reveal = null;
          travel = false;
          range.style.width = `${track.offsetWidth}px`;
          previous = snapshot();
        }
      }
      advance = tick;
      tick(performance.now());
      return;
    }
    // Height fades must not restart an otherwise unchanged horizontal animation.
    if (nextLayout === layout) {
      if (advance) advance(performance.now());
      return;
    }
    layout = nextLayout;
    cancelAnimationFrame(frame);
    frame = 0;
    advance = null;
    const before = previous;
    clearPlacement();
    const after = snapshot();
    const maximum = Math.max(0, track.offsetWidth + PADDING - viewport.clientWidth);
    const rtl = getComputedStyle(viewport).direction === "rtl";
    const clamp = value => rtl ? Math.max(-maximum, Math.min(0, value)) : Math.max(0, Math.min(maximum, value));
    if (!before || !before.nodes.size || !after.nodes.size) {
      range.style.width = `${track.offsetWidth}px`;
      previous = snapshot();
      travel = false;
      interrupted = false;
      return;
    }
    const anchor = [...before.nodes].find(([key, rect]) => after.nodes.has(key) &&
      rect.right > INSET && rect.left < viewport.clientWidth - INSET);
    const delta = anchor ? after.nodes.get(anchor[0]).x - anchor[1].x : 0;
    const target = clamp(before.scroll === 0 ? 0 : before.scroll + delta);
    const offsets = nodes.map(node => {
      const key = node.dataset.toolsKey;
      return [node, before.nodes.has(key) ? before.nodes.get(key).x - after.nodes.get(key).x : 0];
    });
    if (reduced || dragging || interrupted) {
      range.style.width = `${track.offsetWidth}px`;
      if (reduced && !dragging && !interrupted) viewport.scrollLeft = target;
      previous = snapshot();
      travel = false;
      interrupted = false;
      return;
    }
    const moving = offsets.some(([, x]) => Math.abs(x) > .5);
    if (!moving && Math.abs(before.scroll - target) < .5) {
      range.style.width = `${track.offsetWidth}px`;
      previous = snapshot();
      travel = false;
      return;
    }
    // Keep the old scroll range through shrinkage so the browser cannot clamp before the tween.
    range.style.width = `${Math.max(before.extent - INSET, track.offsetWidth)}px`;
    viewport.scrollLeft = before.scroll;
    const from = viewport.scrollLeft, started = performance.now();
    travel = true;
    function tick(now) {
      const time = Math.min(1, (now - started) / DURATION);
      const progress = 1 - (1 - time) ** 3;
      const desiredScroll = from + (target - from) * progress;
      if (travel) viewport.scrollLeft = desiredScroll;
      // Browser scroll offsets can round to whole pixels; placement uses the accepted offset.
      const rounding = travel ? viewport.scrollLeft - desiredScroll : 0;
      offsets.forEach(([node, x]) => { node.style.translate = `${x * (1 - progress) + rounding}px 0`; });
      previous = snapshot();
      if (time < 1) frame = requestAnimationFrame(tick);
      else {
        frame = 0;
        advance = null;
        clearPlacement();
        range.style.width = `${track.offsetWidth}px`;
        previous = snapshot();
        travel = false;
        interrupted = false;
      }
    }
    advance = tick;
    tick(started);
  };
  const queue = () => {
    if (queued) return;
    // Measure new layout before an old tween can replace the last painted anchor snapshot.
    cancelAnimationFrame(frame);
    frame = 0;
    queued = requestAnimationFrame(measure);
  };
  // Fold geometry already advances each frame; reserve its paint before that frame is displayed.
  const geometry = new ResizeObserver(() => {
    if (replacing) { queue(); return; }
    cancelAnimationFrame(frame);
    frame = 0;
    measure();
  });
  const observeItems = (records = []) => {
    const current = new Set(items());
    observed.forEach(node => { if (!current.has(node)) { geometry.unobserve(node); observed.delete(node); } });
    current.forEach(node => { if (!observed.has(node)) { geometry.observe(node); observed.add(node); } });
    // DOM disposal changes flex positions without resizing any retained capsule.
    // Measure in this mutation checkpoint so the next paint cannot expose those positions.
    if (replacing || !records.some(record => record.type === "childList" || record.type === "characterData")) queue();
    else {
      cancelAnimationFrame(frame);
      frame = 0;
      measure();
    }
  };
  const mutations = new MutationObserver(observeItems);
  mutations.observe(viewport, { childList: true, subtree: true, characterData: true,
    attributes: true, attributeFilter: ["data-tools-progress", "data-tools-reveal", "data-owner",
      "data-tools-owner", "data-tools-family", "data-tools-exiting"] });
  geometry.observe(viewport);
  observeItems();
  measure();
  const cancelTravel = () => {
    travel = false;
    interrupted = !!advance || items().some(node => node.dataset.toolsReveal !== undefined);
    if (reveal) {
      reveal = null;
      cancelAnimationFrame(frame);
      frame = 0;
      advance = null;
    }
    items().forEach(node => { delete node.dataset.toolsReveal; });
    previous = snapshot();
  };
  const pointerDown = () => { dragging = true; cancelTravel(); };
  const pointerUp = () => { dragging = false; previous = snapshot(); };
  const scrolled = () => { paintEdges(); if (!travel) previous = snapshot(); };
  viewport.addEventListener("pointerdown", pointerDown, { passive: true });
  window.addEventListener("pointerup", pointerUp, { passive: true });
  window.addEventListener("pointercancel", pointerUp, { passive: true });
  viewport.addEventListener("wheel", cancelTravel, { passive: true });
  viewport.addEventListener("keydown", cancelTravel);
  viewport.addEventListener("scroll", scrolled, { passive: true });
  return () => {
    cancelAnimationFrame(frame);
    cancelAnimationFrame(queued);
    geometry.disconnect();
    mutations.disconnect();
    if (!replacing) {
      clearPlacement();
      range.style.width = "0px";
    }
    items().forEach(node => { delete node.dataset.toolsReveal; });
    viewport.removeEventListener("pointerdown", pointerDown);
    window.removeEventListener("pointerup", pointerUp);
    window.removeEventListener("pointercancel", pointerUp);
    viewport.removeEventListener("wheel", cancelTravel);
    viewport.removeEventListener("keydown", cancelTravel);
    viewport.removeEventListener("scroll", scrolled);
    viewport.style.removeProperty("--tools-edge-left");
    viewport.style.removeProperty("--tools-edge-right");
    chat?.style.removeProperty("--chat-tools-lift");
    chat?.style.removeProperty("--chat-tools-message-inset");
    viewport.dispatchEvent(new Event("toolsresize"));
  };
}

export function ChatToolsRow({ state }) {
  const host = useRef(null), bar = useRef(null), reduced = !!state.display?.reduceMotion;
  const owner = `${state.connectionId || ""}:${state.openId || ""}:${state.composer?.seq ?? ""}`;
  const family = state.connected && state.openId
    ? `${state.connectionId || ""}:${state.parentConversationId || state.openId}` : owner;
  const [settled, setSettled] = useState({ family });
  const current = useRef({ owner, family }), closed = useRef(false);
  if (current.current.family !== family) closed.current = true;
  current.current = { owner, family };
  const enter = settled.family === family && !closed.current &&
    (state.initialToolsReady === undefined || state.openId == null || state.initialToolsReady === 7);
  useLayoutEffect(() => attachToolsRow(host.current, reduced, selected => {
    if (current.current.owner !== selected) return;
    closed.current = false;
    setSettled({ family: current.current.family });
  }), [reduced]);
  useLayoutEffect(() => attachScrollbar(host.current, bar.current, undefined, "horizontal"), []);
  return html`<div class="tools-row"><div class="tools-scroll" ref=${host}
    data-tools-owner=${owner} data-tools-family=${family} data-tools-enter=${String(enter)}>
    <div class="tools-track">
      <${InteractionBar} state=${state} enter=${enter} />
      <${TodoBar} state=${state} enter=${enter} />
      <${SubagentsBar} state=${state} enter=${enter} />
      <div class="tools-scroll-range" aria-hidden="true"></div>
    </div>
  </div><div class="scrollbar-overlay tools-scrollbar" ref=${bar} aria-hidden="true"><span></span></div></div>`;
}
