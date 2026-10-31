// The open conversation's selected branch, drawn as the app's MessageList and MessageItem.
// The list is paged by message rounds; every row of the current page is watched, so the phone
// sends all of the page's bodies before the page is drawn.
import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { CircularProgress, Spinner } from "./material/progress.js";
import { icon, ICON_WEB, ICON_CHEVRON_DOWN } from "./icons.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";
import { DetailSheet, canOpenSheetItem } from "./detail-sheet.js";
import { attachScrollbar } from "./material/scrollbar.js";
import { bindTailScrollInput, createStreamingTailFollow } from "./tail-follow.js";
import { MessageDeleteDialog } from "./message-actions.js";
import { groupForMessage, sheetItemsForMessage, createGroupExpansionController } from "./message-model.js";
import { Row } from "./message-row.js";
import { useMessageScroll } from "./message-scroll.js";

export { ModelMessage } from "./message-model.js";

/**
 * ChatWelcomeContent: the launcher mark above the product name, typed one glyph at a time.
 *
 * TypewriterText.kt TypewriterMode.TEXT_GRADIENT reveals code point i at (i + 1) * 100 ms at 16 %
 * alpha and reaches full alpha 420 ms later, while the complete string owns the layout throughout.
 * Every glyph is a real span here, so one CSS animation per glyph carries the same curve and the
 * line never re-wraps mid-typing.
 *
 * The entrance is not this component's policy. chatMainContentTransition plays it through
 * AnimatedContent on every arrival at New Chat, while newChatMotionPolicy only decides whether the
 * typewriter runs; the two shared one class here, which left a second and every later switch to New
 * Chat with no transition at all.
 */
const WELCOME_GLYPH_STEP_MS = 100;
// newChatMotionPolicy: newChatEntryId == 1L, so the phone types the welcome once per session and
// shows it static on every later visit.
let welcomeTyped = false;

function ChatWelcome({ reduceMotion }) {
  const typing = !reduceMotion && !welcomeTyped;
  welcomeTyped = true;
  // role="img" keeps the glyph-split spans out of the accessibility tree as one name.
  return html`
    <div class=${`chat-welcome welcome-enters ${typing ? "welcome-typing" : ""}`} role="img"
      aria-label=${t.welcome}>
      <span class="welcome-mark">${icon(ICON_WEB)}</span>
      <span class="welcome-text">${[...t.welcome].map((glyph, index) => html`<span
        class="welcome-glyph" style=${typing
          ? { animationDelay: `${(index + 1) * WELCOME_GLYPH_STEP_MS}ms` }
          : null}>${glyph}</span>`)}</span>
    </div>`;
}

/**
 * MessageItem's generationLifecycleAppearanceModifier: a row the list has never shown before fades
 * in once over MESSAGE_ENTER_DURATION_MS (320, linear, scale 1). The decision is taken when the row
 * mounts and never replays; a row with a tool segment is forceOpaque (style.css).
 */
function RowEntrance({ enter, exiting, children }) {
  const entering = useRef(enter);
  return html`<div class=${entering.current ? "message-entering" : null} data-branch-exit=${exiting ? "true" : null}
    inert=${exiting} aria-hidden=${exiting ? "true" : null}>${children}</div>`;
}

function PageProgress({ active, edge }) {
  const host = useRef(null);
  useLayoutEffect(() => {
    const node = host.current;
    if (active) node.style.visibility = "visible";
    const animation = node.animate([{ opacity: getComputedStyle(node).opacity }, { opacity: active ? 1 : 0 }],
      { duration: 180, easing: "linear", fill: "forwards" });
    animation.onfinish = () => {
      node.style.opacity = active ? "1" : "0";
      if (!active) node.style.visibility = "hidden";
      animation.cancel();
    };
    return () => { node.style.opacity = getComputedStyle(node).opacity; animation.cancel(); };
  }, [active]);
  return html`<div ref=${host} class=${`message-page-progress ${edge}`} data-loading=${active ? "true" : "false"}
    aria-hidden=${active ? null : "true"}><span><${CircularProgress} size=${32} stroke=${3}
      label=${active ? t.loading : null} /></span></div>`;
}
/** Independent token threshold and round cap; whole rounds are never split. */
const PAGE_TOKEN_WINDOW = 256 * 1024;
const MAX_PAGE_ROUNDS = 10;
const MAX_PAGE_WINDOWS = 3;
const OLDER_PAGE_EDGE_PX = 4;
export function MessageList({ state, label, onPageAction }) {
  const scroller = useRef(null);
  const bar = useRef(null);
  const pinned = useRef(false);
  const bottomMotion = useRef(null);
  const tailFollow = useRef(createStreamingTailFollow());
  const tailInput = useRef(null);
  const tailInputHandlers = useRef(null);
  const bottomButton = useRef(null);
  const bottomSeek = useRef(null);
  const bottomRequested = useRef(false);
  const bottomVisibility = useRef({ openId: null, top: 0, travel: 0, heading: false, near: true });
  const refreshBottom = useRef(null);
  const [bottomVisible, setBottomVisible] = useState(false);
  const generationActive = useRef(false);
  const search = state.conversationSearch;
  const searchPosition = useRef(null);
  const searchTarget = useRef(null);
  searchTarget.current = search?.matches[search.index]?.messageId ?? null;
  generationActive.current = !!state.generating;
  const cover = useRef(null);
  const [settledOpenId, setSettledOpenId] = useState(null);
  const switching = state.switching;
  const covered = !!switching;
  const coveredId = useRef(null);
  const expansion = useRef(new Map());
  const expansionController = useRef(createGroupExpansionController());
  const appearances = useRef(new Set());
  const glyphBirths = useRef(new Map());
  const previousOpenId = useRef(state.openId);
  const [sheet, setSheet] = useState(null);
  const [deleteDialog, setDeleteDialog] = useState(null);
  useEffect(() => {
    if (deleteDialog && (deleteDialog.connectionId !== state.connectionId ||
        deleteDialog.seq !== sync.interactionTarget()?.seq ||
        (deleteDialog.conversationId !== state.openId && !state.switching?.confirmation))) setDeleteDialog(null);
  }, [state.connectionId, state.openId, state.switching, deleteDialog]);
  function requestDelete(message, deletesConversation) {
    const target = sync.interactionTarget();
    if (target) setDeleteDialog({ ...target, messageId: message.id, compactOnly: !!message.compact, deletesConversation });
  }
  const sheetWatchedId = sheet?.conversationId === state.openId ? sheet.messageId : null;
  const watchedSheet = useRef(sheetWatchedId);
  watchedSheet.current = sheetWatchedId;
  const branch = state.branchReplacement;
  // Costs survive body eviction, but belong only to this selected conversation. Unknown rounds
  // hydrate in bounded adjacent batches; missing costs are never treated as free.
  const loadCosts = useRef({ openId: state.openId, values: new Map() });
  if (loadCosts.current.openId !== state.openId) loadCosts.current = { openId: state.openId, values: new Map() };
  const rememberCost = message => {
    if (Number.isFinite(message?.loadTokens)) loadCosts.current.values.set(message.id, Math.max(1, message.loadTokens));
  };
  state.bodies.forEach(rememberCost);
  rememberCost(state.streaming);
  const roundStarts = state.path.reduce((starts, entry, index) => {
    if (index === 0 || entry.participant === "USER") starts.push(index);
    return starts;
  }, []);
  const roundCount = roundStarts.length;
  const roundEnd = (round) => (round + 1 < roundCount ? roundStarts[round + 1] : state.path.length);
  const roundOfId = (id) => {
    const index = id ? state.path.findIndex((entry) => entry.id === id) : -1;
    if (index < 0) return -1;
    let round = 0;
    while (round + 1 < roundCount && roundStarts[round + 1] <= index) round += 1;
    return round;
  };
  const roundCost = (round) => {
    let total = 0;
    for (let index = roundStarts[round]; index < roundEnd(round); index += 1) {
      const cost = loadCosts.current.values.get(state.path[index].id);
      if (cost == null) return null;
      total += cost;
    }
    return total;
  };
  /** Include the first crossing round, stop at ten, or exhaust the remaining complete rounds. */
  const fit = (start, end, keep) => {
    if (keep === "start") end = Math.min(end, start + MAX_PAGE_ROUNDS);
    else start = Math.max(start, end - MAX_PAGE_ROUNDS);
    let first = keep === "start" ? start : end;
    let last = first;
    let tokens = 0;
    const probes = [];
    for (let round = keep === "start" ? start : end - 1;
      round >= start && round < end; round += keep === "start" ? 1 : -1) {
      const cost = roundCost(round);
      if (cost == null) {
        let probe = round;
        let rows = 0;
        // Resolve adjacent unknown costs in bounded batches, never price missing text as zero.
        while (probe >= start && probe < end && rows < 48) {
          const entries = state.path.slice(roundStarts[probe], roundEnd(probe));
          if (rows && rows + entries.length > 48) break;
          probes.push(...entries.map(entry => entry.id));
          rows += entries.length;
          probe += keep === "start" ? 1 : -1;
        }
        if (last > first) break;
        if (keep === "start") last = round + 1; else first = round;
        break;
      }
      if (keep === "start") last = round + 1; else first = round;
      tokens += cost;
      if (tokens > PAGE_TOKEN_WINDOW) break;
    }
    return { start: first, end: last, probes };
  };
  const pageEntries = (page) => (page.end > page.start
    ? state.path.slice(roundStarts[page.start], roundEnd(page.end - 1)) : []);
  /** A page is stored by ids, so it survives rows being added; endId null follows the newest round. */
  const pageRequestFor = (page, keep) => ({
    openId: state.openId,
    startId: state.path[roundStarts[page.start]]?.id ?? null,
    endId: page.end >= roundCount ? null : state.path[roundStarts[page.end]].id,
    lastId: state.path[roundStarts[page.end - 1]]?.id ?? null,
    keep,
  });
  const resolvePage = (request, fill = true) => {
    if (request?.openId !== state.openId) return null;
    const start = roundOfId(request.startId);
    const end = request.endId == null ? roundCount : roundOfId(request.endId);
    if (start < 0 || end <= start) return null;
    if (!fill || request.keep === "closed") return { start, end, probes: [] };
    return request.keep === "start" ? fit(start, roundCount, "start") : fit(0, end, "end");
  };
  const [pageRequest, setPageRequest] = useState(null);
  const retainedPages = useRef({ openId: state.openId, windows: [] });
  if (retainedPages.current.openId !== state.openId) retainedPages.current = { openId: state.openId, windows: [] };
  let windows = retainedPages.current.windows.map(page => resolvePage(page, false)).filter(Boolean);
  const retained = windows.length ? { start: windows[0].start, end: windows.at(-1).end } : null;
  const loading = pageRequest?.openId === state.openId && pageRequest.direction;
  let requested = loading
    ? retained ? pageRequest.direction < 0 ? fit(0, retained.start, "end") : fit(retained.end, roundCount, "start") : fit(0, roundCount, "end")
    : resolvePage(pageRequest) ?? (retained ? { ...retained, probes: [] } : fit(0, roundCount, "end"));
  let targeted = false;
  // Distant explicit targets replace the range; adjacent reader paging keeps its existing windows.
  // A send scrolls to the newest message, so it returns to the tail page with its preceding context.
  for (const [id, tail] of [[searchTarget.current, false], [state.scrollRequest?.messageId, true],
    [sheet?.messageId, false], [branch?.request.oldMessageId, false]]) {
    const round = roundOfId(id);
    if (round >= 0 && (round < requested.start || round >= requested.end) &&
        (!retained || round < retained.start || round >= retained.end)) {
      requested = tail ? fit(0, roundCount, "end") : fit(round, roundCount, "start");
      targeted = true;
    }
  }
  const requestedEntries = pageEntries(requested);
  const requestedReady = requested.probes.length === 0 &&
    requestedEntries.every((entry) => state.streaming?.id === entry.id || state.bodies.has(entry.id));
  const displayedPage = useRef(null);
  const previousPage = retained ?? (displayedPage.current ? resolvePage(displayedPage.current, false) : null);
  let displayed = previousPage ?? requested;
  if (requestedReady) {
    if (loading && retained && !targeted) {
      windows = pageRequest.direction < 0 ? [requested, ...windows] : [...windows, requested];
      if (windows.length > MAX_PAGE_WINDOWS) {
        if (pageRequest.direction < 0) windows.pop(); else windows.shift();
      }
    } else if (!retained || targeted || pageRequest?.keep !== "closed" && !loading) {
      windows = [requested];
    }
    displayed = { start: windows[0]?.start ?? requested.start, end: windows.at(-1)?.end ?? requested.end };
    retainedPages.current.windows = windows.map(page => pageRequestFor(page, "closed"));
  }
  useLayoutEffect(() => {
    if (requestedReady && (loading || targeted || pageRequest?.keep !== "closed" || pageRequest.openId !== state.openId)) {
      setPageRequest(pageRequestFor(displayed, "closed"));
    }
  });
  displayedPage.current = pageRequestFor(displayed, "closed");
  const pageLoading = !requestedReady || (!!state.openId && state.initialToolsReady !== undefined &&
    state.initialToolsReady !== 7 && !["failed", "deleted"].includes(state.openStatus));
  const tailReady = requestedReady && displayed.end === roundCount;
  refreshBottom.current = () => {
    const root = scroller.current;
    if (!root) return;
    const latch = bottomVisibility.current;
    if (latch.openId !== state.openId) {
      Object.assign(latch, { openId: state.openId, top: root.scrollTop, travel: 0, heading: false, near: true });
    }
    const remaining = root.scrollHeight - root.clientHeight - root.scrollTop;
    const olderPage = displayed.end < roundCount;
    latch.near = !olderPage && remaining <= (latch.near ? 96 : 48);
    setBottomVisible(latch.heading && !!state.openId && state.path.length > 0 && !covered &&
      state.openStatus === "ready" && !pageLoading && !latch.near &&
      !bottomRequested.current && !bottomMotion.current &&
      !tailFollow.current.following && !branch && !search?.query.trim());
  };
  const pagePath = pageEntries(displayed);
  const windowStart = displayed.end > displayed.start ? roundStarts[displayed.start] : 0;
  const hasOlderRounds = displayed.start > 0;
  const hasNewerRounds = displayed.end < roundCount;
  const pageKey = `${state.openId}:${displayedPage.current.startId}:${displayedPage.current.endId}`;
  // A page change keeps the reader's view: the first row still on screen that the new page also
  // draws is measured before the change and put back at the same offset after it.
  const pageAnchor = useRef(null);
  const pageInput = useRef({ direction: 0, touchY: null, openId: state.openId });
  const pageAtEdge = useRef(null);
  if (pageInput.current.openId !== state.openId) {
    pageInput.current = { direction: 0, touchY: null, openId: state.openId };
  }
  const anchoredPageKey = useRef(null);
  const pagePaint = useRef(null);
  const pageExits = useRef(null);
  const pagePaintIds = useRef({ openId: state.openId, ids: new Set() });
  if (anchoredPageKey.current !== pageKey) {
    // A layout correction is not another gesture. Only fresh input may cross the next edge.
    pageInput.current.direction = 0;
    const root = scroller.current;
    pageAnchor.current = null;
    pagePaint.current = null;
    if (root && anchoredPageKey.current?.startsWith(`${state.openId}:`)) {
      const kept = new Set(pagePath.map((entry) => entry.id));
      const top = root.getBoundingClientRect().top;
      const rows = [...root.querySelectorAll(".message-column .message-row")];
      const row = rows.find(node => kept.has(node.dataset.id) && node.getBoundingClientRect().bottom > top);
      if (row) pageAnchor.current = { id: row.dataset.id, offset: row.getBoundingClientRect().top - top };
      pagePaint.current = { openId: state.openId, incoming: new Set(pagePath.filter(entry =>
        !pagePaintIds.current.ids.has(entry.id)).map(entry => entry.id)), outgoing: rows.filter(node => {
          const bounds = node.getBoundingClientRect();
          return !kept.has(node.dataset.id) && bounds.bottom > top && bounds.top < top + root.clientHeight;
        }).map(node => ({ node: node.cloneNode(true), bounds: node.getBoundingClientRect() })) };
    }
    anchoredPageKey.current = pageKey;
  }
  pagePaintIds.current = { openId: state.openId, ids: new Set(pagePath.map(entry => entry.id)) };
  useLayoutEffect(() => {
    const root = scroller.current;
    const anchor = pageAnchor.current;
    pageAnchor.current = null;
    if (!root) return;
    const column = root.querySelector(".message-column");
    column?.style.removeProperty("padding-bottom");
    if (!anchor || pinned.current) return;
    const row = [...root.querySelectorAll(".message-row")].find((node) => node.dataset.id === anchor.id);
    if (row) {
      const target = root.scrollTop + row.getBoundingClientRect().top - root.getBoundingClientRect().top - anchor.offset;
      const missing = target - (root.scrollHeight - root.clientHeight);
      // A short replacement needs trailing room, otherwise the browser clamps away the anchor.
      if (column && missing > 0) {
        column.style.paddingBottom = `${parseFloat(getComputedStyle(column).paddingBottom) + missing}px`;
      }
      root.scrollTop = target;
    }
  }, [pageKey]);
  useLayoutEffect(() => {
    const paint = pagePaint.current;
    pagePaint.current = null;
    const root = scroller.current, host = pageExits.current;
    if (!root || !host || !paint || paint.openId !== state.openId) return;
    for (const row of root.querySelectorAll(".message-column .message-row")) {
      if (paint.incoming.has(row.dataset.id)) row.parentElement.animate([{ opacity: 0 }, { opacity: 1 }],
        { duration: 180, easing: "linear" });
    }
    host.replaceChildren();
    if (!paint.outgoing.length) { host.style.top = ""; host.style.height = ""; return; }
    const viewport = root.getBoundingClientRect();
    host.style.top = `${root.scrollTop}px`;
    host.style.height = `${root.clientHeight}px`;
    for (const { node, bounds } of paint.outgoing) {
      node.className = "message-page-snapshot";
      node.removeAttribute("data-id");
      node.setAttribute("inert", "");
      node.setAttribute("aria-hidden", "true");
      Object.assign(node.style, { top: `${bounds.top - viewport.top}px`, left: `${bounds.left - viewport.left}px`,
        width: `${bounds.width}px`, height: `${bounds.height}px` });
      host.append(node);
      const fade = node.animate([{ opacity: 1 }, { opacity: 0 }],
        { duration: 180, easing: "linear", fill: "forwards" });
      fade.onfinish = () => {
        node.remove(); fade.cancel();
        if (!host.childElementCount) { host.style.top = ""; host.style.height = ""; }
      };
    }
  }, [pageKey]);
  // Explicit history requests are not displaced by an out-of-page streaming reply.
  const ids = pagePath.map((entry) => entry.id).join(",");
  const branchVisual = useRef({ id: null, entries: [], bodies: new Map() });
  const branchScroll = useRef(null);
  const visualKeys = useRef(new Map());
  if (branch && branchVisual.current.id !== branch.request.id) {
    const start = state.path.findIndex(entry => entry.id === branch.request.oldMessageId);
    branchVisual.current = { id: branch.request.id, entries: start < 0 ? [] : state.path.slice(start),
      bodies: new Map(state.bodies) };
    if (state.streaming) branchVisual.current.bodies.set(state.streaming.id, state.streaming);
  }
  if (branch?.request.sourceUserMessageId && branch.request.targetUserMessageId) {
    visualKeys.current.set(branch.request.targetUserMessageId,
      visualKeys.current.get(branch.request.sourceUserMessageId) || branch.request.sourceUserMessageId);
  }
  const exitIds = new Set(branch ? branchVisual.current.entries.map(entry => entry.id) : []);
  const renderedPath = branch ? [...pagePath, ...branchVisual.current.entries.filter(entry =>
    !state.path.some(active => active.id === entry.id))] : pagePath;
  const branchTarget = branch?.request.targetUserMessageId;
  const watchedBranch = useRef(branchTarget);
  watchedBranch.current = branchTarget;
  // shouldAnimateMessageLifecycleEntrance: only a row the list has not shown yet, never while the
  // conversation is switching, never a branch-exit row or the edited user message that keeps its
  // visual key. A user row enters when it is the scroll target or the newest user message of a
  // running generation; a model row while it streams or when it answers the scroll target.
  const knownRows = useRef({ openId: state.openId, ids: new Set() });
  if (knownRows.current.openId !== state.openId) knownRows.current = { openId: state.openId, ids: new Set() };
  const lastUserId = [...state.path].reverse().find((entry) => entry.participant === "USER")?.id;
  const entranceTarget = state.scrollRequest?.messageId ?? null;
  const shouldEnter = (entry) => {
    if (covered || knownRows.current.ids.has(entry.id) || exitIds.has(entry.id)) return false;
    if (entry.id === branchTarget) return false;
    if (entry.participant === "USER") return entry.id === entranceTarget || (!!state.generating && entry.id === lastUserId);
    if (entry.participant === "MODEL") {
      return state.streaming?.id === entry.id || (entranceTarget != null && entry.parentId === entranceTarget);
    }
    return false;
  };
  useEffect(() => {
    renderedPath.forEach((entry) => knownRows.current.ids.add(entry.id));
  });
  // calculateTailHolderMinHeightPx: the last turn is at least as tall as the viewport minus the
  // 140 dp top target and the bottom obstruction (bottom bar + 8 dp), less whatever of the tail anchor
  // turn (the newest real user message or Compact) sits above it, so a sent message can reach the
  // top target. The web list is flat, so a spacer after the rows supplies the missing height.
  const tailSpacer = useRef(null);
  const tailAnchorId = (() => {
    for (let index = pagePath.length - 1; index >= 0; index -= 1) {
      const entry = pagePath[index];
      const message = state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id);
      if (entry.participant === "USER" || message?.compact) return entry.id;
    }
    return null;
  })();
  useLayoutEffect(() => {
    const root = scroller.current;
    const spacer = tailSpacer.current;
    if (!root || !spacer) return undefined;
    const column = spacer.parentElement;
    const measure = () => {
      const anchor = tailAnchorId
        ? [...column.querySelectorAll(".message-row")].find((row) => row.dataset.id === tailAnchorId) : null;
      if (!anchor) { spacer.style.height = "0px"; return; }
      const style = getComputedStyle(column);
      const minimum = Math.max(0, root.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom));
      const tail = spacer.getBoundingClientRect().top - anchor.getBoundingClientRect().top;
      const height = Math.max(0, Math.round(minimum - tail));
      if (spacer.style.height !== `${height}px`) spacer.style.height = `${height}px`;
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(root);
    // Composer/tools clearance changes padding without resizing the column's content box.
    observer.observe(column, { box: "border-box" });
    return () => observer.disconnect();
  }, [tailAnchorId, ids]);
  useLayoutEffect(() => {
    const root = scroller.current;
    const request = branch?.request;
    const rows = [...root.querySelectorAll('.message-row')].filter(row =>
      branchVisual.current.entries.some(entry => entry.id === row.dataset.id));
    if (!request) {
      const animations = rows.map(row => row.animate([{ opacity: getComputedStyle(row).opacity }, { opacity: 1 }],
        { duration: 180, easing: 'linear', fill: 'forwards' }));
      animations.forEach((animation, index) => {
        animation.onfinish = () => { rows[index].style.opacity = ''; animation.cancel(); };
      });
      branchVisual.current = { id: null, entries: [], bodies: new Map() };
      branchScroll.current = null;
      return () => animations.forEach(animation => animation.cancel());
    }
    if (branchVisual.current.entries.some(entry => exitIds.has(entry.id))) pinned.current = false;
    const animations = rows.map(row => row.animate([{ opacity: 1 }, { opacity: 0 }],
      { duration: request.fadeFinished ? 0 : 180, easing: 'linear', fill: 'forwards' }));
    Promise.all(animations.map(animation => animation.finished)).then(() => {
      sync.branchAck('branch_fade', branch);
    }).catch(() => {});
    return () => {
      rows.forEach(row => { row.style.opacity = getComputedStyle(row).opacity; });
      animations.forEach(animation => animation.cancel());
    };
  }, [branch?.request.id]);
  useLayoutEffect(() => {
    const request = branch?.request;
    if (request?.stage !== 'COMMITTED' || !request.scrollFinished ||
        state.path.some(entry => entry.id === request.oldMessageId)) return;
    const frame = requestAnimationFrame(() => sync.branchAck('branch_complete', branch));
    return () => cancelAnimationFrame(frame);
  }, [branch, state.path]);
  const wrap = state.display?.autoWrapCodeBlocks ?? true;
  // An empty new chat replaces the whole body with ChatWelcomeContent, as on the phone.
  const showWelcome = !state.openId && state.path.length === 0;
  const selectedOnPath = sheet && state.openId === sheet.conversationId &&
    state.path.some((entry) => entry.id === sheet.messageId);
  const sheetMessage = selectedOnPath
    ? state.streaming?.id === sheet.messageId ? state.streaming : state.bodies.get(sheet.messageId)
    : null;
  const sheetGroup = sheetMessage && sheet?.groupKey != null
    ? groupForMessage(sheetMessage, sheet.groupKey) : null;
  const sheetItems = sheetMessage ? sheetItemsForMessage(sheetMessage, sheet.groupKey) : [];
  const selectedItem = sheetItems.find((item) => item.detailIndex === sheet?.detailIndex);
  const validSheet = selectedOnPath && (!sheetMessage ||
    ((sheet.groupKey == null || sheetGroup) &&
      (sheet.page !== "detail" || canOpenSheetItem(selectedItem))));

  function openSheet(messageId, groupKey) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "list", detailIndex: null, listFirst: true });
  }
  function openDetail(messageId, groupKey, detailIndex) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "detail", detailIndex, listFirst: false });
  }
  useEffect(() => {
    if (!sheet) return;
    if (sheet.conversationId !== state.openId || state.openStatus === "deleted" ||
        state.openStatus === "failed" ||
        (state.openStatus === "ready" && !state.path.some((entry) => entry.id === sheet.messageId)) ||
        (sheetMessage && !validSheet)) setSheet(null);
  }, [sheet, state.openId, state.openStatus, ids, sheetMessage, validSheet]);

  useEffect(() => {
    if (previousOpenId.current === state.openId) return;
    previousOpenId.current = state.openId;
    visualKeys.current.clear();
    expansion.current.clear();
    expansionController.current.reset();
    // SegmentAppearanceRegistry is session-scoped and lives above the list, so a segment that has
    // already entered once never replays its appearance - not when the list is rebuilt and not when
    // the same conversation is opened again. Keys carry the message id, so conversations never
    // collide and nothing is cleared on a switch.
    glyphBirths.current.clear();
  }, [state.openId]);

  // Chrome runs no CSS transition on its own scrollbar, so the animated bar is drawn beside it; the
  // native bar stays in place and keeps every hit target.
  useEffect(() => {
    const input = bindTailScrollInput(scroller.current, {
      start: () => tailInputHandlers.current?.start(),
      stop: () => tailInputHandlers.current?.stop(),
    }, bar.current);
    tailInput.current = input;
    return () => { input.dispose(); tailInput.current = null; };
  }, []);
  useEffect(() => attachScrollbar(scroller.current, bar.current, direction => {
    tailInput.current?.scrollbarInput();
    pageInput.current.direction = direction;
    pageAtEdge.current?.();
  }), []);

  // The page and its next unknown round share the existing payload watch. Keep the displayed
  // page subscribed until replacement is ready, and retain both through idle-cache eviction.
  const requestedIds = [...new Set([...requestedEntries.map(entry => entry.id), ...requested.probes])].join(",");
  useEffect(() => {
    const requestedList = requestedIds ? requestedIds.split(",") : [];
    const retained = [...pagePath.map(entry => entry.id), ...requestedList];
    sync.keep(retained);
    sync.watch([...new Set([searchTarget.current, sheetWatchedId, branchTarget, ...retained].filter(Boolean))]);
  }, [requestedIds, ids, sheetWatchedId, searchTarget.current, branchTarget]);

  useMessageScroll({
    state, ids, scroller, pinned, bottomMotion, tailFollow, tailInput, tailInputHandlers,
    bottomButton, bottomSeek, bottomRequested, refreshBottom, bottomVisible, setBottomVisible,
    generationActive, settledOpenId, setSettledOpenId, switching, covered, search, branch,
    branchTarget, branchVisual, branchScroll, searchPosition, tailReady, pageLoading,
    requestedReady, pagePath, pageAtEdge,
  });
  function seekBottom() {
    if (!bottomVisible) return;
    bottomRequested.current = true;
    setBottomVisible(false);
    if (tailReady) bottomSeek.current?.();
    else setPageRequest(pageRequestFor(fit(0, roundCount, "end"), "end"));
  }

  useLayoutEffect(() => {
    const node = cover.current;
    if (!node || !switching || !["covering", "revealing"].includes(switching.phase)) return undefined;
    const entering = switching.phase === "covering";
    coveredId.current = switching.id;
    const animation = node.animate(
      [{ opacity: getComputedStyle(node).opacity }, { opacity: entering ? 1 : 0 }],
      { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" },
    );
    animation.onfinish = () => {
      node.style.opacity = entering ? "1" : "0";
      animation.cancel();
      if (entering) sync.covered(switching.id);
      else sync.revealed(switching.id);
    };
    return () => {
      node.style.opacity = getComputedStyle(node).opacity;
      animation.cancel();
    };
  }, [switching?.id, switching?.phase]);
  useLayoutEffect(() => {
    if (!switching || switching.phase !== "settling") return undefined;
    const root = scroller.current;
    let frame = 0, previous = null;
    const check = () => {
      frame = 0;
      const failed = !state.connected || ["failed", "deleted"].includes(state.openStatus);
      const waiting = switching.confirmation || !failed && (pageLoading || !requestedReady || document.fonts.status !== "loaded" ||
        root.querySelector('[data-stream-fading="true"]') ||
        [...root.querySelectorAll(".markdown img")].some(image => !image.complete) ||
        (!!state.openId && state.openStatus !== "ready") || (state.connected && state.composer == null));
      const chat = root.closest(".chat");
      const animations = chat.getAnimations({ subtree: true }).filter(animation => {
        const timing = animation.effect?.getComputedTiming();
        return animation.playState !== "finished" && timing?.endTime !== Infinity &&
          !animation.effect?.target?.closest(".conversation-loading-cover");
      });
      const rects = [...root.querySelectorAll(".message-row,.info-group-surface,.info-group-items,img,.markdown")]
        .map(node => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height]; });
      const geometry = JSON.stringify([root.scrollHeight, root.clientHeight, root.scrollTop, rects,
        chat.querySelector(".composer-host")?.getBoundingClientRect().toJSON(),
        getComputedStyle(chat).getPropertyValue("--chat-bottom-inset")]);
      const pendingScroll = bottomMotion.current || (branch && !branchScroll.current?.done) ||
        (!failed && ["open", "fork"].includes(switching.command.type) && state.openId && settledOpenId !== state.openId);
      if (!waiting && !pendingScroll && !animations.length && geometry === previous) sync.settled(switching.id);
      else {
        previous = waiting || animations.length || pendingScroll ? null : geometry;
        frame = requestAnimationFrame(check);
      }
    };
    frame = requestAnimationFrame(check);
    return () => cancelAnimationFrame(frame);
  }, [switching, state.connected, state.openId, state.openStatus, state.composer, state.path,
    state.bodies, state.streaming, pageLoading, requestedReady, settledOpenId, branch]);
  useEffect(() => () => sync.cancelSwitch(coveredId.current), []);

  // chatMainContentTransition replaces one page frame with the other: the frame being left fades out
  // over tween(300) while the new one enters, and a switch between two existing conversations plays
  // nothing at all. The outgoing frame is a frozen snapshot rather than a live subtree, because
  // AnimatedContent stops recomposing an exiting target the moment it is not the current one - a live
  // copy would keep watching rows and reporting sizes for content the user has already left.
  const frameKind = showWelcome ? "welcome" : "thread";
  const exiting = useRef(null);
  const enterFrame = useRef(false);
  const lastFrameKind = useRef(frameKind);
  const exitHost = useRef(null);
  if (lastFrameKind.current !== frameKind) {
    // Read while the DOM still holds the frame being replaced: the render body runs before the patch.
    const live = scroller.current?.firstElementChild;
    exiting.current = live
      ? { node: live.cloneNode(true), top: scroller.current.scrollTop } : null;
    enterFrame.current = frameKind === "thread";
    lastFrameKind.current = frameKind;
  }
  useLayoutEffect(() => {
    const host = exitHost.current;
    const leaving = exiting.current;
    exiting.current = null;
    let fade = null;
    // The exit host is absolute inside the scroller, so while it holds a frame it is part of the
    // scroll range. ChatWelcomeContent sits in no scroll container: arriving at New Chat drops the
    // old offset at once and shows the leaving frame through a clipped, shifted copy at the top, so
    // the welcome never inherits a scroll position or a range it can be dragged through.
    const clear = () => { host.replaceChildren(); host.style.top = ""; host.style.height = ""; host.style.overflow = ""; };
    if (host && leaving) {
      const toWelcome = frameKind !== "thread";
      host.replaceChildren(leaving.node);
      if (toWelcome) {
        if (scroller.current) scroller.current.scrollTop = 0;
        host.style.top = "0px";
        host.style.overflow = "hidden";
        leaving.node.style.transform = `translateY(${-leaving.top}px)`;
      } else {
        host.style.top = `${leaving.top}px`;
      }
      host.style.height = `${scroller.current?.clientHeight ?? 0}px`;
      fade = host.animate([{ opacity: 1 }, { opacity: 0 }],
        { duration: 300, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      fade.onfinish = () => { clear(); fade.cancel(); };
    } else if (host && frameKind !== "thread" && scroller.current) {
      scroller.current.scrollTop = 0;
    }
    // Returning from New Chat to an existing conversation is fadeIn(tween(300)).
    if (enterFrame.current) {
      enterFrame.current = false;
      scroller.current?.firstElementChild?.animate?.([{ opacity: 0 }, { opacity: 1 }],
        { duration: 300, easing: "cubic-bezier(0.4, 0, 0.2, 1)" });
    }
    return () => { if (fade) { fade.onfinish = null; fade.cancel(); clear(); } };
  }, [frameKind]);

  pageAtEdge.current = () => {
    const root = scroller.current;
    if (!root) return;
    const direction = pageInput.current.direction;
    if (covered || pinned.current || bottomMotion.current || branch || search?.query.trim()) return;
    if (pageLoading || !requestedReady) {
      // Reversing during hydration cancels the pending edge, not the reader's retained windows.
      if (loading && direction && direction !== pageRequest.direction) {
        setPageRequest(pageRequestFor(displayed, "closed"));
      }
      return;
    }
    if (hasOlderRounds && direction < 0 && root.scrollTop <= OLDER_PAGE_EDGE_PX) {
      pageInput.current.direction = 0;
      setPageRequest({ openId: state.openId, direction: -1 });
    } else if (hasNewerRounds && direction > 0 && root.scrollHeight - root.clientHeight - root.scrollTop <= OLDER_PAGE_EDGE_PX) {
      pageInput.current.direction = 0;
      setPageRequest({ openId: state.openId, direction: 1 });
    }
  };
  return html`
    <section class="messages" ref=${scroller} aria-label=${label} aria-busy=${covered} inert=${covered}
      onWheel=${event => { pageInput.current.direction = Math.sign(event.deltaY); pageAtEdge.current?.(); }}
      ontouchstart=${event => { pageInput.current.touchY = event.touches[0]?.clientY ?? null; }}
      ontouchmove=${event => {
        const y = event.touches[0]?.clientY;
        if (y != null && pageInput.current.touchY != null) {
          pageInput.current.direction = Math.sign(pageInput.current.touchY - y);
          pageInput.current.touchY = y;
          pageAtEdge.current?.();
        }
      }}
      onKeyDown=${event => {
        const tag = event.target.tagName;
        if (["INPUT", "TEXTAREA", "SELECT"].includes(tag) || event.target.isContentEditable) return;
        if (["ArrowUp", "PageUp", "Home"].includes(event.key)) pageInput.current.direction = -1;
        else if (["ArrowDown", "PageDown", "End", " "].includes(event.key)) {
          pageInput.current.direction = event.key === " " && event.shiftKey ? -1 : 1;
        }
        pageAtEdge.current?.();
      }}
      onScroll=${(event) => {
        const root = event.currentTarget;
        const latch = bottomVisibility.current;
        const delta = root.scrollTop - latch.top;
        if (!covered && !pageLoading && !bottomMotion.current && !branch && !search?.query.trim()) {
          if (delta) latch.travel = Math.sign(delta) === Math.sign(latch.travel) ? latch.travel + delta : delta;
          if (Math.abs(latch.travel) >= 32) { latch.heading = latch.travel > 0; latch.travel = 0; }
        }
        latch.top = root.scrollTop;
        refreshBottom.current?.();
        pageAtEdge.current?.();
      }}>
      ${showWelcome ? html`<${ChatWelcome} reduceMotion=${!!state.display?.reduceMotion} />`
        : html`<div class="message-column">
        <${PageProgress} key="older-page" edge="older" active=${!covered && !!loading && loading < 0 && pageLoading} />
        ${renderedPath.map((entry, index) => html`
          <${RowEntrance} key=${visualKeys.current.get(entry.id) || entry.id} enter=${shouldEnter(entry)}
            exiting=${exitIds.has(entry.id)}>
          <${Row} entry=${entry} wrap=${wrap} display=${state.display}
            search=${search} first=${windowStart === 0 && index === 0} rows=${state.path.length}
            generating=${!!state.generating} reduceMotion=${!!state.display?.reduceMotion}
            branchEnabled=${state.connected && state.openStatus === "ready" && !covered && !state.generating && !state.pendingAction && !branch}
            onPageAction=${onPageAction} onDelete=${requestDelete}
            expansion=${expansion.current} expansionController=${expansionController.current}
            appearances=${appearances.current} glyphBirths=${glyphBirths.current} conversationId=${state.openId}
            onOpenSheet=${openSheet} onOpenDetail=${openDetail}
            streaming=${state.streaming?.id === entry.id}
            stopping=${!!state.composer?.stopping && state.streaming?.id === entry.id}
            body=${exitIds.has(entry.id) ? branchVisual.current.bodies.get(entry.id)
              : state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id)} /></${RowEntrance}>`)}
        ${!hasNewerRounds && html`<div class="message-tail-minimum" key="tail-minimum" ref=${tailSpacer} aria-hidden="true"></div>`}
        <${PageProgress} key="newer-page" edge="newer" active=${!covered && !!loading && loading > 0 && pageLoading} />
      </div>`}
      <div key="history-exit" class="message-page-exits" ref=${pageExits} aria-hidden="true" inert></div>
      <div key="page-exit" class="chat-frame-exits" ref=${exitHost} aria-hidden="true"></div>
    </section>
    <div class="scrollbar-overlay" ref=${bar} aria-hidden="true"><span></span></div>
    <div class="scroll-bottom-slot" inert=${!bottomVisible} aria-hidden=${!bottomVisible}>
      <button ref=${bottomButton} class="scroll-bottom-button" type="button" tabindex=${bottomVisible ? 0 : -1}
        aria-label=${t.scrollToBottom} title=${t.scrollToBottom} onClick=${seekBottom}>${icon(ICON_CHEVRON_DOWN)}</button>
    </div>
    ${covered && html`<div class="conversation-loading-cover" ref=${cover} data-phase=${switching.phase} data-request=${switching.id}
      onPointerDown=${(event) => { event.preventDefault(); event.stopPropagation(); }}
      onWheel=${(event) => event.preventDefault()} onContextMenu=${(event) => event.preventDefault()}>
      <div class="conversation-loading-range">
        <${Spinner} size=${48} stroke=${5} label=${label} />
      </div>
    </div>`}
    ${deleteDialog && html`<${MessageDeleteDialog} state=${state} request=${deleteDialog}
      onClose=${() => setDeleteDialog(null)} />`}
    ${sheet && validSheet && html`<${DetailSheet} key=${`${sheet.conversationId}:${sheet.messageId}:${sheet.groupKey ?? "direct"}`}
      group=${sheetGroup} items=${sheetItems} page=${sheet.page} detailIndex=${sheet.detailIndex}
      selectedItem=${selectedItem} display=${state.display} wrap=${wrap}
      conversationId=${sheet.conversationId} messageId=${sheet.messageId}
      onSelectItem=${(detailIndex) => setSheet({ ...sheet, page: "detail", detailIndex })}
      onBack=${sheet.page === "detail" && sheet.listFirst
        ? () => setSheet({ ...sheet, page: "list", detailIndex: null }) : null}
      onClose=${() => setSheet(null)} />`}`;
}
