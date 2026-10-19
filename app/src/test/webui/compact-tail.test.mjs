import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../../main/assets/webui/messages.js", import.meta.url), "utf8");
const start = source.indexOf("  const tailSpacer = useRef(null);");
const end = source.indexOf("  useLayoutEffect(() => {", source.indexOf("return () => observer.disconnect();", start));
assert.ok(start >= 0 && end > start, "Exercise the production tail measurement owner");
const tailCode = source.slice(start, end);
const entry = (id, participant = "MODEL") => ({ id, participant });
const body = (id, compact = false, status = "SUCCESS") => ({ id, compact, status });

function measureTail({ path, bodies = [], streaming = null, rows, viewport = 800, obstruction = 200 }) {
  const observers = [];
  const spacer = {
    style: { height: "0px" },
    getBoundingClientRect: () => ({ top: rows.at(-1).bottom }),
  };
  const column = {
    querySelectorAll: () => rows.map(row => ({ dataset: { id: row.id },
      getBoundingClientRect: () => ({ top: row.top, bottom: row.bottom }) })),
  };
  spacer.parentElement = column;
  let cleanup;
  const context = vm.createContext({
    pagePath: path, state: { bodies: new Map(bodies.map(value => [value.id, value])), streaming },
    ids: path.map(value => value.id).join(","),
    scroller: { current: { clientHeight: viewport } },
    useRef: () => ({ current: spacer }),
    useLayoutEffect: effect => { cleanup = effect(); },
    getComputedStyle: () => ({ paddingTop: "140px", paddingBottom: `${obstruction + 8}px` }),
    ResizeObserver: class {
      constructor(callback) { this.callback = callback; this.observed = []; observers.push(this); }
      observe(node) { this.observed.push(node); }
      disconnect() { this.observed.length = 0; }
    },
  });
  vm.runInContext(tailCode, context);
  return { spacer, observers, cleanup, anchor: vm.runInContext("tailAnchorId", context) };
}

const ordinaryRows = [
  { id: "old-user", top: 140, bottom: 210 },
  { id: "old-reply", top: 210, bottom: 610 },
  { id: "latest", top: 610, bottom: 680 },
];
const compactRows = ordinaryRows.map(row => row.id === "latest" ? { ...row, bottom: 668 } : row);

for (const status of ["SENDING", "STREAMING", "SUCCESS", "ERROR", "STOPPED"]) {
  for (const location of ["streaming", "cached"]) {
    test(`Compact has ordinary USER tail clearance: ${status}, ${location}`, () => {
      const compact = body("latest", true, status);
      const ordinary = measureTail({
        path: [entry("old-user", "USER"), entry("old-reply"), entry("latest", "USER")],
        rows: ordinaryRows,
      });
      const result = measureTail({
        path: [entry("old-user", "USER"), entry("old-reply"), entry("latest")],
        bodies: location === "cached" ? [compact] : [],
        streaming: location === "streaming" ? compact : null,
        rows: compactRows,
      });
      assert.equal(result.anchor, "latest");
      // Native calculateTailMinHeightPx: viewport - 140px - (composer obstruction + 8px).
      const minimum = 800 - 140 - 208;
      assert.equal(Number.parseFloat(ordinary.spacer.style.height) + 70, minimum);
      assert.equal(Number.parseFloat(result.spacer.style.height) + 58, minimum);
      result.cleanup();
      ordinary.cleanup();
      assert.equal(result.observers[0].observed.length, 0);
    });
  }
}

test("the rendered live Compact overrides an older cached body for the same row", () => {
  const result = measureTail({
    path: [entry("old-user", "USER"), entry("old-reply"), entry("latest")],
    bodies: [body("latest", false)], streaming: body("latest", true, "STREAMING"), rows: compactRows,
  });
  assert.equal(result.anchor, "latest");
  assert.equal(result.spacer.style.height, "394px");
  result.cleanup();
});

test("a later USER supersedes Compact while following assistant rows consume its tail minimum", () => {
  const result = measureTail({
    path: [entry("compact"), entry("user", "USER"), entry("reply")],
    bodies: [body("compact", true)],
    rows: [{ id: "compact", top: 140, bottom: 198 }, { id: "user", top: 198, bottom: 268 },
      { id: "reply", top: 268, bottom: 500 }],
  });
  assert.equal(result.anchor, "user");
  assert.equal(result.spacer.style.height, "150px");
  result.cleanup();
});

test("the existing measurement tracks continuous tools clearance without writing scroll offsets", () => {
  for (const obstruction of [200, 228, 256]) {
    const rows = [{ id: "compact", top: 140, bottom: 198 }];
    const result = measureTail({ path: [entry("compact")], streaming: body("compact", true, "STREAMING"),
      rows, obstruction });
    assert.equal(Number.parseFloat(result.spacer.style.height) + 58, 800 - 140 - obstruction - 8);
    rows[0].bottom += 10;
    result.observers[0].callback();
    assert.equal(Number.parseFloat(result.spacer.style.height) + 68, 800 - 140 - obstruction - 8);
    result.cleanup();
  }
});
