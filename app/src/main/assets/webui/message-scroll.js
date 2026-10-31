// MessageList's existing scroll effects stay together as one programmatic motion owner.
import { useLayoutEffect } from "./vendor/preact-hooks.mjs";
import { sync } from "./sync.js";
import { coalescedScrollStep } from "./tail-follow.js";

/**
 * Programmatic bottom motion. Constants mirror the phone's scroll owners so the browser decelerates
 * into the tail instead of teleporting: RobustLazyListScroll.kt (FeedbackScrollSpec,
 * smoothSeekToItem), ChatScrollCoordinator.kt (SendFeedbackScrollSpec, 2 dp minimum step),
 * AbsoluteBottomScroll.kt (animateToAbsoluteBottom settling) and MessageListTailEffects.kt
 * (the attached streaming tail controller).
 */
const BOTTOM_MOTION = {
  seekTauMeasuredSeconds: 0.09,
  seekTauUnmeasuredSeconds: 0.16,
  seekMaxVelocityMeasuredViewports: 16,
  seekMaxVelocityUnmeasuredViewports: 52,
  maximumFrameStepViewportFraction: 0.82,
  minimumStepPx: 2,
  tolerancePx: 1.5,
  stableFrames: 4,
  blockedFrames: 12,
  maximumDurationMs: 30_000,
  sendStartupMs: 240,
  settlingGenerationMs: 700,
  settlingIdleMs: 192,
  settlingTimeoutMs: 1_600,
  settlingStableFrames: 6,
};

/** Numeric stand-in for a Compose Easing so a per-frame actor can reuse a CSS timing curve. */
export function cubicBezierEasing(x1, y1, x2, y2) {
  const cx = 3 * x1;
  const bx = 3 * (x2 - x1) - cx;
  const ax = 1 - cx - bx;
  const cy = 3 * y1;
  const by = 3 * (y2 - y1) - cy;
  const ay = 1 - cy - by;
  const curve = (a, b, c, t) => ((a * t + b) * t + c) * t;
  return (progress) => {
    if (progress <= 0) return 0;
    if (progress >= 1) return 1;
    let t = progress;
    for (let step = 0; step < 8; step += 1) {
      const error = curve(ax, bx, cx, t) - progress;
      if (Math.abs(error) < 1e-6) break;
      const slope = (3 * ax * t + 2 * bx) * t + cx;
      if (Math.abs(slope) < 1e-6) break;
      t -= error / slope;
    }
    return curve(ay, by, cy, Math.min(1, Math.max(0, t)));
  };
}

// Compose FastOutSlowInEasing, the envelope the phone puts on a send scroll.
const fastOutSlowIn = cubicBezierEasing(0.4, 0, 0.2, 1);

/** Reuse MessageList's refs and state; extraction does not add another scroll lifecycle. */
export function useMessageScroll({
  state, ids, scroller, pinned, bottomMotion, tailFollow, tailInput, tailInputHandlers,
  bottomButton, bottomSeek, bottomRequested, refreshBottom, bottomVisible, setBottomVisible,
  generationActive, settledOpenId, setSettledOpenId, switching, covered, search, branch,
  branchTarget, branchVisual, branchScroll, searchPosition, tailReady, pageLoading,
  requestedReady, pagePath, pageAtEdge,
}) {
  useLayoutEffect(() => {
    const root = scroller.current;
    const observer = new ResizeObserver(() => refreshBottom.current?.());
    observer.observe(root);
    observer.observe(root.firstElementChild);
    refreshBottom.current();
    return () => observer.disconnect();
  }, [state.openId, ids, covered, pageLoading, state.generating, branch, search]);
  useLayoutEffect(() => {
    const node = bottomButton.current;
    const style = getComputedStyle(node);
    const reduced = !!state.display?.reduceMotion;
    const from = { opacity: style.opacity, transform: reduced ? "none" : style.transform, boxShadow: style.boxShadow };
    const animation = node.animate([from, {
      opacity: bottomVisible ? 1 : 0,
      transform: reduced || bottomVisible ? "scale(1)" : "scale(.6)",
      boxShadow: bottomVisible ? "0 1px 3px #0005, 0 4px 8px 3px #0002" : "0 0 0 transparent",
    }], { duration: 400, easing: "cubic-bezier(.4,0,.2,1)", fill: "forwards" });
    animation.onfinish = () => {
      Object.assign(node.style, { opacity: bottomVisible ? "1" : "0", transform: reduced || bottomVisible ? "scale(1)" : "scale(.6)",
        boxShadow: bottomVisible ? "var(--shadow-4)" : "none" });
      animation.cancel();
    };
    return () => {
      const current = getComputedStyle(node);
      Object.assign(node.style, { opacity: current.opacity, transform: current.transform, boxShadow: current.boxShadow });
      animation.cancel();
    };
  }, [bottomVisible, state.display?.reduceMotion]);

  // The app opens a conversation at its newest message. Row bodies arrive after the path, so
  // this same owner releases the cover after visible bodies and bottom layout have settled.
  useLayoutEffect(() => {
    tailFollow.current.reset();
    bottomMotion.current = null;
    pinned.current = true;
    bottomRequested.current = false;
    setBottomVisible(false);
    setSettledOpenId(null);
  }, [state.openId]);
  useLayoutEffect(() => {
    const root = scroller.current;
    const column = root?.firstElementChild;
    if (!root || !column) return undefined;
    let frame = 0;
    let seekFrame = 0;
    let previousLayout = null;
    const coveredOpen = ["open", "fork"].includes(switching?.command.type);
    const pending = state.openStatus === "ready" && settledOpenId !== state.openId && (!covered || coveredOpen);
    const fading = switching?.phase === "covering" || switching?.phase === "revealing";
    const request = state.scrollRequest;
    let seekRequested = false;
    if (request && requestedReady && state.openStatus === "ready" &&
        pagePath.some((entry) => entry.id === request.messageId)) {
      pinned.current = true;
      sync.consumeScroll(request);
      seekRequested = true;
    }

    // One owner drives every programmatic bottom move the way the phone does: a send travels with
    // the feedback controller, growth during a generation uses the short tail correction, and the
    // actor settles on the end sentinel before releasing ownership.
    let motionFrame = 0;
    const bottomError = () => Math.max(0, root.scrollHeight - root.clientHeight - root.scrollTop);
    const updateTail = (now = performance.now()) => {
      if (covered || branch || search?.query.trim()) bottomMotion.current = null;
      tailFollow.current.update({
        generating: generationActive.current,
        blocked: covered || !!branch || !!search?.query.trim() || !!state.composer?.stopping ||
          !!state.display?.reduceMotion || !tailReady || pageLoading,
        handoff: pending || !!bottomMotion.current,
        stickToBottom: state.display?.stickToBottom ?? true,
      }, now);
      tailFollow.current.proximity(bottomError(), !!tailInput.current?.scrolling, now);
      pinned.current = pending || !!bottomMotion.current || tailFollow.current.following;
    };
    let scrollRemainder = 0;
    const scrollBy = (step) => {
      const before = root.scrollTop;
      // DOM scroll offsets quantize fractional movement; retain only unconsumed subpixels.
      const requested = step + scrollRemainder;
      root.scrollTop += requested;
      const consumed = root.scrollTop - before;
      scrollRemainder = requested - consumed;
      return Math.abs(consumed);
    };
    // An unhydrated tail row is a 54 px estimate, which is the web stand-in for a target the phone
    // has not composed yet: the seek then travels with the wider unmeasured gains.
    const tailIsMeasured = () => {
      const last = state.path[state.path.length - 1];
      if (!last) return true;
      if (state.streaming?.id === last.id) return true;
      return state.bodies.has(last.id);
    };
    const pump = () => {
      motionFrame = 0;
      const now = performance.now();
      updateTail(now);
      const motion = bottomMotion.current;
      if (!pinned.current) return;
      if (fading) return;
      // Opening stays a hard placement under the switching cover, exactly like scrollToItem.
      if (pending) {
        bottomMotion.current = null;
        root.scrollTop = root.scrollHeight;
        return;
      }
      if (covered) {
        bottomMotion.current = null;
        return;
      }
      if (state.display?.reduceMotion && motion) {
        root.scrollTop = root.scrollHeight;
        bottomMotion.current = null;
        updateTail(now);
        return;
      }
      const generating = generationActive.current;
      if (!motion) {
        const step = tailFollow.current.frame(bottomError(), now);
        if (Math.abs(step) > 0.05) scrollBy(step);
        pinned.current = tailFollow.current.following;
        if (pinned.current) wake();
        return;
      }
      const active = motion;
      const elapsed = Math.max(0.001, Math.min(0.05, (now - active.previous) / 1000));
      active.previous = now;
      const height = Math.max(1, root.clientHeight);
      const error = bottomError();
      if (active.mode === "seek") {
        if (now - active.started > BOTTOM_MOTION.maximumDurationMs) {
          bottomMotion.current = null;
          return;
        }
        const measured = tailIsMeasured();
        if (measured && error <= BOTTOM_MOTION.tolerancePx) {
          scrollBy(error);
          active.stable += 1;
          if (active.stable >= BOTTOM_MOTION.stableFrames) {
            if (generating) {
              bottomMotion.current = null;
              updateTail(now);
              wake();
              return;
            }
            active.mode = "settle";
            active.settlingStarted = now;
            active.settlingStable = 0;
          } else {
            wake();
            return;
          }
        } else {
          active.stable = 0;
          let step = coalescedScrollStep(
            measured ? error : Math.max(error, height * 0.75),
            elapsed,
            measured ? BOTTOM_MOTION.seekTauMeasuredSeconds : BOTTOM_MOTION.seekTauUnmeasuredSeconds,
            height * (measured
              ? BOTTOM_MOTION.seekMaxVelocityMeasuredViewports
              : BOTTOM_MOTION.seekMaxVelocityUnmeasuredViewports),
            BOTTOM_MOTION.minimumStepPx,
          );
          if (active.startupMs) step *= fastOutSlowIn((now - active.started) / active.startupMs);
          const cap = height * BOTTOM_MOTION.maximumFrameStepViewportFraction;
          step = Math.min(cap, Math.max(-cap, step));
          if (Math.abs(step) > 0.05) {
            active.blocked = scrollBy(step) <= 0.05 ? active.blocked + 1 : 0;
            if (active.blocked >= BOTTOM_MOTION.blockedFrames) {
              bottomMotion.current = null;
              return;
            }
          }
          wake();
          return;
        }
      }
      if (active.mode === "settle") {
        // Late growth or an active generation hands ownership back to the seek, as the phone does.
        if (generating || error > BOTTOM_MOTION.tolerancePx) {
          active.mode = "seek";
          active.started = now;
          active.stable = 0;
          active.blocked = 0;
          wake();
          return;
        }
        active.settlingStable += 1;
        const settlingElapsed = now - active.settlingStarted;
        const minimum = active.generationWasActive
          ? BOTTOM_MOTION.settlingGenerationMs
          : BOTTOM_MOTION.settlingIdleMs;
        if ((settlingElapsed >= minimum && active.settlingStable >= BOTTOM_MOTION.settlingStableFrames) ||
          settlingElapsed >= BOTTOM_MOTION.settlingTimeoutMs) {
          bottomMotion.current = null;
          return;
        }
        wake();
        return;
      }
    };
    function wake() {
      if (!motionFrame) motionFrame = requestAnimationFrame(() => { pump(); refreshBottom.current?.(); });
    }
    const startSeek = () => {
      scrollRemainder = 0;
      bottomRequested.current = false;
      pinned.current = true;
      // A reduced-motion or covered list takes the same instant placement the phone uses.
      if (pending || covered || state.display?.reduceMotion) {
        bottomMotion.current = null;
        root.scrollTop = root.scrollHeight;
        updateTail();
        return;
      }
      const now = performance.now();
      bottomMotion.current = {
        mode: "seek",
        startupMs: BOTTOM_MOTION.sendStartupMs,
        started: now,
        previous: now,
        stable: 0,
        blocked: 0,
        generationWasActive: generationActive.current,
      };
      wake();
    };
    bottomSeek.current = startSeek;
    if (seekRequested || (bottomRequested.current && tailReady)) {
      bottomRequested.current = false;
      startSeek();
    }

    const settle = () => {
      frame = 0;
      // The opening page may still grow upward; settling earlier would leave the reader at its old offset.
      if (!requestedReady) {
        previousLayout = null;
        return;
      }
      const viewport = root.getBoundingClientRect();
      const rows = [...column.querySelectorAll(".message-row")].filter((row) => {
        const bounds = row.getBoundingClientRect();
        return bounds.bottom > viewport.top && bounds.top < viewport.bottom;
      });
      if (rows.some((row) => state.streaming?.id !== row.dataset.id && !state.bodies.has(row.dataset.id))) {
        previousLayout = null;
        return;
      }
      const layout = `${root.scrollHeight}:${root.clientHeight}:${root.scrollTop}`;
      const atBottom = Math.max(0, root.scrollHeight - root.clientHeight - root.scrollTop) <= 1;
      if (layout === previousLayout && atBottom) {
        setSettledOpenId(state.openId);
      } else {
        previousLayout = layout;
        if (pinned.current) wake();
        frame = requestAnimationFrame(settle);
      }
    };
    const followBottom = (fromFrame = false) => {
      updateTail();
      if (fading) return;
      if (branch) {
        pinned.current = false;
        bottomMotion.current = null;
        const request = branch.request;
        const row = [...column.querySelectorAll('.message-row')].find(node => node.dataset.id === branchTarget);
        if (!request.scrollFinished && row && state.bodies.has(branchTarget)) {
          if (branchScroll.current?.id !== request.id || branchScroll.current.target !== branchTarget) {
            branchScroll.current = { id: request.id, target: branchTarget, started: performance.now(),
              from: root.scrollTop, distance: row.getBoundingClientRect().top - root.getBoundingClientRect().top - parseFloat(getComputedStyle(column).paddingTop),
              done: false };
          }
          const motion = branchScroll.current;
          if (!motion.done && fromFrame) {
            const progress = state.display?.reduceMotion ? 1 : Math.min(1, (performance.now() - motion.started) / 600);
            root.scrollTop = motion.from + motion.distance * cubicBezierEasing(0.3, 0, 0, 1)(progress);
            if (progress === 1) {
              motion.done = true;
              sync.branchAck('branch_scroll', branch, true);
            }
          }
          if (!motion.done && !seekFrame) seekFrame = requestAnimationFrame(() => { seekFrame = 0; followBottom(true); });
        }
        return;
      }
      if (search?.query.trim()) pinned.current = false;
      else searchPosition.current = null;
      if (search && !search.searching && search.matches.length && !covered) {
        const bar = root.closest(".chat").querySelector(".conversation-search-bar");
        if (!bar) return;
        const viewport = root.getBoundingClientRect();
        const top = Math.max(viewport.top, bar.getBoundingClientRect().bottom);
        const bottom = Math.min(viewport.bottom, root.closest(".chat").querySelector(".composer-host").getBoundingClientRect().top);
        const center = (top + bottom) / 2;
        const geometry = new Map();
        for (const range of CSS.highlights.get("agora-search") ?? []) {
          if (!root.contains(range.startContainer)) continue;
          for (const rect of range.getClientRects()) {
            const old = geometry.get(range.searchKey);
            geometry.set(range.searchKey, {
              top: Math.min(old?.top ?? rect.top, rect.top),
              bottom: Math.max(old?.bottom ?? rect.bottom, rect.bottom),
            });
          }
        }
        const midpoint = bounds => (bounds.top + bounds.bottom) / 2;
        if (search.index < 0) {
          let nearest = -1, distance = Infinity;
          search.matches.forEach((match, index) => {
            const bounds = geometry.get(match.key);
            if (bounds && bounds.bottom > top && bounds.top < bottom) {
              const candidate = Math.abs(midpoint(bounds) - center);
              if (candidate < distance) { nearest = index; distance = candidate; }
            }
          });
          if (nearest < 0) {
            const rows = [...column.querySelectorAll(".message-row")];
            const anchor = rows.reduce((best, row, index) =>
              Math.abs(midpoint(row.getBoundingClientRect()) - center) < best.distance
                ? { index, distance: Math.abs(midpoint(row.getBoundingClientRect()) - center) } : best,
            { index: 0, distance: Infinity }).index;
            const turnIndexes = new Map(state.path.map((entry, index) => [entry.id, index]));
            search.matches.forEach((match, index) => {
              const candidate = Math.abs((turnIndexes.get(match.messageId) ?? Infinity) - anchor);
              if (candidate < distance) { nearest = index; distance = candidate; }
            });
          }
          if (nearest >= 0) sync.selectSearchMatch(nearest, search.revision, true);
        } else {
          const match = search.matches[search.index];
          const bounds = geometry.get(match.key);
          const epoch = `${state.openId}:${search.revision}:${search.positionRevision}`;
          const now = performance.now();
          if (searchPosition.current?.epoch !== epoch) {
            searchPosition.current = { epoch, started: now, previous: now, stable: 0, blocked: 0, done: false };
          }
          const motion = searchPosition.current;
          if (!motion.done && now - motion.started < 30_000) {
            if (fromFrame) {
              const row = [...column.querySelectorAll(".message-row")].find(r => r.dataset.id === match.messageId);
              // Rows whose occurrence is hidden source (math, markers, code) keep the row-rect
              // estimate as their final target, matching Compose centering the turn itself.
              const target = bounds || row?.getBoundingClientRect() || null;
              if (target && now >= search.positionAfter) {
                const error = midpoint(target) - center;
                const dt = Math.max(0.001, Math.min(0.05, (now - motion.previous) / 1000));
                motion.previous = now;
                if (Math.abs(error) <= 1.5) {
                  // Unhydrated rows never finalize: marks may still appear when the body arrives.
                  if (++motion.stable >= 4 && (bounds || state.bodies.has(match.messageId))) motion.done = true;
                } else {
                  motion.stable = 0;
                  const reduced = !!state.display?.reduceMotion;
                  const height = Math.max(1, viewport.height);
                  const maximum = Math.max(1, height * (bounds ? 16 : 52) * dt);
                  const step = reduced ? error : Math.sign(error) * Math.min(
                    Math.abs(error) * (1 - Math.exp(-dt / (bounds ? 0.09 : 0.16))),
                    maximum, height * 0.82,
                  );
                  const before = root.scrollTop;
                  root.scrollTop += step;
                  motion.blocked = Math.abs(root.scrollTop - before) <= 0.05 ? motion.blocked + 1 : 0;
                  if (motion.blocked >= 12) motion.done = true;
                }
              }
            }
            // Seek steps run at most once per frame; ResizeObserver wakeups defer to rAF so
            // coalesced multi-callback frames never exceed the bounded per-frame velocity.
            if (!motion.done && !seekFrame) seekFrame = requestAnimationFrame(() => { seekFrame = 0; followBottom(true); });
          }
        }
      }
      if (pinned.current) wake();
      if (covered && !coveredOpen) return;
      if (pending && !frame) frame = requestAnimationFrame(settle);
    };
    const release = () => {
      tailFollow.current.userDrag();
      if (branch && !branch.request.scrollFinished && !branchScroll.current?.done) {
        branchScroll.current = { id: branch.request.id, target: branchTarget, done: true };
        sync.branchAck('branch_scroll', branch, false);
      }
      pinned.current = false;
      scrollRemainder = 0;
      bottomRequested.current = false;
      bottomMotion.current = null;
      cancelAnimationFrame(motionFrame);
      motionFrame = 0;
      if (searchPosition.current) searchPosition.current.done = true;
      cancelAnimationFrame(seekFrame);
      seekFrame = 0;
      refreshBottom.current?.();
      pageAtEdge.current?.();
    };
    const handlers = {
      start: release,
      stop: () => { updateTail(); if (pinned.current) wake(); refreshBottom.current?.(); },
    };
    tailInputHandlers.current = handlers;
    const follow = new ResizeObserver(followBottom);
    follow.observe(column);
    follow.observe(root);
    followBottom();
    return () => {
      follow.disconnect();
      cancelAnimationFrame(frame);
      cancelAnimationFrame(motionFrame);
      cancelAnimationFrame(seekFrame);
      if (bottomSeek.current === startSeek) bottomSeek.current = null;
      if (tailInputHandlers.current === handlers) tailInputHandlers.current = null;
    };
  }, [state.openId, state.openStatus, ids, state.bodies, state.streaming, settledOpenId, state.scrollRequest,
    state.generating, state.composer?.stopping, search, branch, covered, switching?.phase, tailReady,
    pageLoading, state.display?.reduceMotion, state.display?.stickToBottom]);
}
