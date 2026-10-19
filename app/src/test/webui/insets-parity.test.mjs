import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { Fragment, h } from "../../main/assets/webui/vendor/preact.mjs";
import htm from "../../main/assets/webui/vendor/htm.mjs";

const root = new URL("../../main/assets/webui/", import.meta.url);
const source = name => fs.readFileSync(new URL(name, root), "utf8");

test("the production shell consumes the continuous message inset through its single measurement owner", () => {
  const code = source("shell.js");
  const start = code.indexOf("  useLayoutEffect(() => {\n    const chat = shell.current.querySelector(\".chat\");");
  assert.ok(start >= 0);
  const end = code.indexOf("  }, []);", start) + "  }, []);".length;
  const styles = new Map(), handlers = new Map(), observed = [];
  let cleanup, resize, composerTop = 600;
  const tools = {
    addEventListener: (type, listener) => handlers.set(type, listener),
    removeEventListener: type => handlers.delete(type),
  };
  const composer = { querySelector: () => tools,
    getBoundingClientRect: () => ({ top: composerTop }) };
  const capsule = { getBoundingClientRect: () => ({ bottom: 60 }) };
  const chat = {
    querySelector: () => composer, querySelectorAll: () => [capsule],
    getBoundingClientRect: () => ({ top: 0, bottom: 800 }),
    style: { getPropertyValue: key => styles.get(key) || "",
      setProperty: (key, value) => styles.set(key, value) },
  };
  vm.runInNewContext(code.slice(start, end), {
    shell: { current: { querySelector: () => chat } },
    useLayoutEffect: effect => { cleanup = effect(); },
    ResizeObserver: class {
      constructor(callback) { resize = callback; }
      observe(node) { observed.push(node); }
      disconnect() { observed.length = 0; }
    },
  });
  assert.equal(styles.get("--chat-top-inset"), "68px");
  assert.equal(styles.get("--chat-bottom-inset"), "200px");
  assert.deepEqual(observed, [chat, composer, tools, capsule]);
  styles.set("--chat-tools-lift", "999px");
  for (const inset of [56, 28, .056]) {
    styles.set("--chat-tools-message-inset", `${inset}px`);
    handlers.get("toolsresize")();
    assert.equal(styles.get("--chat-bottom-inset"), `${200 + inset}px`);
  }
  composerTop = 550;
  resize();
  assert.equal(styles.get("--chat-bottom-inset"), "250.056px");
  styles.set("--chat-tools-message-inset", "0px");
  handlers.get("toolsresize")();
  assert.equal(styles.get("--chat-bottom-inset"), "250px");
  styles.delete("--chat-tools-message-inset");
  resize();
  assert.equal(styles.get("--chat-bottom-inset"), "250px");
  cleanup();
  assert.equal(handlers.size, 0);
  assert.equal(observed.length, 0);
});

test("Interaction resize uses live viewport geometry before the row publishes its new width", () => {
  const code = source("interaction.js");
  const start = code.indexOf("  useLayoutEffect(() => {\n    const container = card.current, content = full.current, label = capsule.current;");
  assert.ok(start >= 0);
  const end = code.indexOf("  }, [shown?.owner, memory?.folded, reduced]);", start) +
    "  }, [shown?.owner, memory?.folded, reduced]);".length;
  const viewport = { clientWidth: 832 };
  const host = { clientWidth: 816, closest: () => viewport };
  const content = { style: {}, offsetHeight: 189 };
  const container = { style: {} }, label = { style: {}, offsetWidth: 144 };
  const motion = { current: { fold: null } };
  let resize, cleanup;
  const context = vm.createContext({
    card: { current: container }, full: { current: content }, capsule: { current: label },
    host: { current: host }, motion, memory: { folded: false }, shown: { owner: "synthetic:root" },
    reduced: false, performance: { now: () => 0 }, tweenProgress: time => time,
    requestAnimationFrame: () => { throw new Error("Unchanged fold scheduled an animation"); },
    cancelAnimationFrame: () => {}, useLayoutEffect: effect => { cleanup = effect(); },
    getComputedStyle: () => ({ getPropertyValue: () => "816px" }),
    ResizeObserver: class { constructor(callback) { resize = callback; } observe() {} disconnect() {} },
  });
  vm.runInContext(code.slice(start, end), context);
  for (const width of [382, 832, 382]) {
    viewport.clientWidth = width;
    resize();
    assert.equal(content.style.width, `${width - 16}px`);
    assert.equal(container.style.width, `${width - 16}px`);
    assert.equal(container.style.height, "189px");
  }
  motion.current.fold.progress = 1;
  resize();
  assert.equal(content.style.width, "366px");
  assert.equal(container.style.width, "144px");
  assert.equal(container.style.height, "48px");
  cleanup();
});

test("Todo keeps the shared detail bottom safe-area spacing and literal selectable content", () => {
  const css = source("style.css"), todo = source("todo.js");
  assert.match(css, /\.todo-list\s*\{\s*padding:\s*4px 24px calc\(32px \+ env\(safe-area-inset-bottom\)\);\s*\}/);
  assert.match(css, /\.todo-content\s*\{[^}]*user-select:\s*text/);
  assert.match(todo, /role="listitem"/);
  assert.match(todo, /aria-label=\$\{t\.todoStatuses\[item.status\]\}/);
  assert.match(todo, /<span class="todo-content">\$\{item.content\}<\/span>/);
  assert.doesNotMatch(todo, /type="checkbox"|contenteditable|sync\.(?:setting|send|submit|update)/);
});

test("Todo renders a real Preact Fragment through the production HTM parser", () => {
  const context = vm.createContext({
    Fragment, html: htm.bind(h),
    useRef: current => ({ current }),
    useState: initial => [initial, () => {}],
    useLayoutEffect: () => {},
    ToolItem: () => null, DetailSheet: () => null, icon: () => null, ICON_CHECK: "check",
    t: { todoTitle: "Todo", todoProgress: (done, total) => `${done}/${total}` },
  });
  vm.runInContext(source("todo.js").replace(/^import .*;\r?\n/gm, "").replace(/export /g, ""), context);
  const tree = context.TodoBar({ state: { connectionId: "synthetic", openId: "root", connected: true,
    composer: { seq: 1 }, todo: [{ status: "pending", content: "Literal <text>" }], display: {} } });
  assert.equal(tree.type, Fragment);
  function check(node) {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) { node.forEach(check); return; }
    if ("type" in node) assert.notEqual(node.type, "");
    check(node.props?.children);
  }
  check(tree);
});
