// Material 3 press feedback for every clickable control.
//
// Compose screens get this for free from the platform's default Material 3 indication; the WebUI
// has no Compose here, so it paints the same thing itself. Nothing is inserted into a control: the
// state layer and the press wave live in the control's own background (see the ripple section of
// material.css), so no control needs a wrapper, a stacking context, a new containing block or
// overflow clipping.
//
// The timing and the shape come from androidx.compose.material.ripple, which is what material3's
// LocalIndication delegates to:
//
//   RippleAnimation.kt  FadeInDuration = 75ms linear for the wave's alpha,
//                       RadiusDuration = 225ms FastOutSlowIn for its radius, the same 225ms linear
//                       for its centre as it drifts from the touch point to the control's centre,
//                       FadeOutDuration = 150ms linear once the press ends,
//                       start radius = max(width, height) * 0.3,
//                       end radius = half the diagonal + 10dp while bounded.
//   Ripple.kt           a Press adds a wave and a Release or Cancel is what fades it out, so a held
//                       press holds the wave; the state layer only ever carries hover, focus and
//                       drag, and never a press.
//
// The wave's play state is a data attribute and never a class. Every control here renders its own
// `class` from component state, so a control whose class changes the moment it is selected - a
// conversation row gaining `selected`, for instance - would rewrite the attribute Preact owns and
// erase a wave still running underneath it. A wave can therefore only end two ways, exactly as in
// Compose: a new press on that same control, or that control's own release.
//
// The selector here and the one in material.css's ripple section are the same list: a control
// either has the layer and the wave, or it is not clickable and has neither.
const HOSTS = [
  "button:not(:disabled)",
  "[role=\"button\"]:not([aria-disabled=\"true\"])",
  "[role=\"menuitem\"]:not([aria-disabled=\"true\"])",
  ".conversation-row",
  ".info-item.sheet-item",
  ".info-group-header",
  ".queued-message",
  ".tool-search-result",
  ".interaction-option", ".prompt-option", ".interaction-trust",
].join(", ");
/** A press that is already inside a dead control must not light anything up. */
const DEAD = "[disabled], [aria-disabled=\"true\"]";
/** RippleAnimation.kt's extra reach for a bounded wave, in dp (= px here). */
const BOUNDED_EXTRA_RADIUS = 10;
/** The wave's play state: `in` while the press holds it, `out` while it fades away. */
const WAVE = "data-md-wave";
// App Reduce Motion: the state layer stays and changes instantly, the wave does not run at all.
const reduced = () => document.querySelector(".shell")?.dataset.reduceMotion === "true";

/**
 * The controls whose wave is in one state.
 *
 * Read straight off the DOM instead of a module-level set: a wave whose host is unmounted mid-exit
 * never reports its own animation end, so a set here would only ever grow.
 */
const waving = (state) => [...document.querySelectorAll(`[${WAVE}=\"${state}\"]`)];

/** Grows a wave from the press point and holds it there until the press ends. */
function press(host, x, y) {
  if (reduced()) return;
  const box = host.getBoundingClientRect();
  if (!box.width || !box.height) return;
  const style = host.style;
  // Compose starts the wave at max(side, side) * 0.3 and ends it half a diagonal away, plus 10dp.
  const start = Math.max(box.width, box.height) * 0.3;
  const end = Math.hypot(box.width, box.height) / 2 + BOUNDED_EXTRA_RADIUS;
  style.setProperty("--md-wave-r0", `${start}px`);
  style.setProperty("--md-wave-r1", `${end}px`);
  style.setProperty("--md-wave-ox", `${x - box.left}px`);
  style.setProperty("--md-wave-oy", `${y - box.top}px`);
  style.setProperty("--md-wave-cx", `${box.width / 2}px`);
  style.setProperty("--md-wave-cy", `${box.height / 2}px`);
  // A press on a control that is still waving is a new wave and restarts from the new press point;
  // every other control keeps the wave it is already running.
  host.removeAttribute(WAVE);
  void host.offsetWidth;
  host.setAttribute(WAVE, "in");
}
/** The release: stop growing and fade whatever is left over RippleAnimation's own 150ms. */
function release() {
  for (const host of waving("in")) host.setAttribute(WAVE, "out");
}
function settled(host) {
  host.removeAttribute(WAVE);
  for (const property of ["--md-wave-r0", "--md-wave-r1", "--md-wave-ox", "--md-wave-oy", "--md-wave-cx", "--md-wave-cy"]) {
    host.style.removeProperty(property);
  }
}
/** One delegated listener per interaction keeps every list, menu and dialog covered without wiring. */
export function installTouchFeedback() {
  addEventListener("pointerdown", (event) => {
    const host = event.target.closest?.(HOSTS);
    if (!host || host.matches(DEAD) || host.querySelector("input:disabled") || event.target.closest(DEAD)) return;
    press(host, event.clientX, event.clientY);
  }, { passive: true, capture: true });
  // A Release or a Cancel is what ends a wave. A touch that turns into a scroll is cancelled by the
  // browser, so a fling over a list fades its wave out exactly like Compose drops the press.
  addEventListener("pointerup", release, { passive: true, capture: true });
  addEventListener("pointercancel", release, { passive: true, capture: true });
  // Keyboard activation is a press too, so it grows from the middle and holds until the key is up.
  addEventListener("keydown", (event) => {
    if (event.repeat || (event.key !== " " && event.key !== "Enter")) return;
    const host = event.target.closest?.(HOSTS);
    if (!host || host.matches(DEAD) || host.querySelector("input:disabled") || event.target.closest(DEAD)) return;
    const box = host.getBoundingClientRect();
    press(host, box.left + box.width / 2, box.top + box.height / 2);
  }, { passive: true, capture: true });
  addEventListener("keyup", release, { passive: true, capture: true });
  addEventListener("blur", (event) => {
    if (event.target === window) release();
  }, { passive: true, capture: true });
  addEventListener("animationend", (event) => {
    if (event.animationName === "md-wave-out") settled(event.target);
  }, { passive: true, capture: true });
}
