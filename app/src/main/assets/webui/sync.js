// The browser's runtime client over /api/sync. Draft settlement comes from the phone's composer.
import { useEffect, useState } from "./vendor/preact-hooks.mjs";
import { sessionSignedIn } from "./api.js";
import { t } from "./i18n.js";

// The app's ConversationMessagePayloadCache bounds: rows kept after they leave the screen.
const CACHE_MAX_ENTRIES = 16;
const CACHE_MAX_BYTES = 8 * 1024 * 1024;
const RETRY_MAX_MS = 10_000;
// WebSocket close code the server uses when the browser session ends.
const CLOSE_VIOLATED_POLICY = 1008;

const listeners = new Set();
let state = {
  conversations: [],
  listLoading: true,
  listHasMore: false,
  listLimit: 0,
  searchQuery: "",
  searchResults: [],
  searchLoading: false,
  searchFailed: false,
  conversationSearch: null,
  display: null,
  openId: null,
  /** "loading" | "ready" | "deleted" | "failed" while a conversation is open. */
  openStatus: null,
  path: [],
  generating: false,
  streaming: null,
  bodies: new Map(),
  connected: false,
  composer: null,
  /** This connection's own context window, priced on the phone; null until the first figure. */
  context: null,
  text: "",
  pendingAction: 0,
  pageAction: null,
  pageResult: null,
  shareText: null,
  /** This connection's own drawer-row delete: the command in flight and the answer it got. */
  conversationDelete: null,
  conversationDeleteResult: null,
  todo: [], subagents: [], parentConversationId: null,
  // A real first event for each projection, including empty; reset at every selection/connection.
  initialToolsReady: 0,
  interactions: [],
  interactionPending: 0,
  pendingPastes: [],
  snackbar: null,
  scrollRequest: null,
  branchReplacement: null,
  connectionId: null,
  /** Identified presentation barrier; the command remains unexecuted until the cover is opaque. */
  switching: null,
};
const bodySizes = new Map();
let watched = new Set();
// Rows the list still draws (its displayed page) while the next page is being fetched; eviction
// must not blank them even though they may have left the watch set.
let kept = new Set();
let socket = null;
let retryMs = 1_000;
let onSessionEnded = () => {};
let running = false;
let retryTimer = 0;
let openSeq = 0;
let editRevision = 0;
let nextAction = 0;
let searchRevision = 0;
let conversationSearchRevision = 0;
let noticeId = 0;
// The last streaming frame this connection received, the base of the next streaming_patch.
let streamFrame = null;
const uploads = new Set();

function update(patch) {
  state = { ...state, ...patch };
  const request = state.switching;
  if (request?.phase === "loading") {
    const initiallyReady = state.openId == null || state.initialToolsReady === 7;
    const ready = request.command.type === "open"
      ? (state.openId == null ? state.composer?.seq === openSeq : state.openStatus === "ready" && state.composer?.seq === openSeq)
      : ["fork", "conversation_delete"].includes(request.command.type)
        ? request.result && (!request.result.success ||
          (state.openId !== request.command.conversationId && state.composer?.seq === openSeq &&
            (state.openId == null || state.openStatus === "ready")))
        : request.result?.success && state.openId == null ||
          request.target && request.target.length === state.path.length && request.target.every((target, index) => {
          const row = state.path[index];
          return row.id === target.id && row.parentId === target.parentId &&
            row.branchIndex === target.branchIndex && row.totalBranches === target.totalBranches;
        });
    if (ready && (initiallyReady || ["failed", "deleted"].includes(state.openStatus))) {
      state = { ...state, switching: { ...request, phase: "settling" } };
    }
  }
  listeners.forEach((listener) => listener(state));
}

function send(command) {
  if (socket?.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(command));
  return true;
}

function select(conversationId, patch = {}) {
  streamFrame = null;
  watched = new Set();
  kept = new Set();
  bodySizes.clear();
  const composer = state.composer ? { ...state.composer, attachments: [], queue: [], text: "",
    phase: "IDLE", generating: false, stopping: false } : null;
  // Only the currently proven root and its known children can retain this family's presentation.
  const family = state.parentConversationId || state.openId;
  const sameFamily = state.connected && !!state.connectionId && !!conversationId && !!family &&
    (conversationId === family || state.subagents.some(agent => agent.id === conversationId));
  update({ openId: conversationId, openStatus: conversationId ? "loading" : null,
    path: [], generating: false, streaming: null, bodies: new Map(), scrollRequest: null, branchReplacement: null,
    pageAction: null, pageResult: null, shareText: null, todo: [], initialToolsReady: 0,
    subagents: sameFamily ? state.subagents : [],
    parentConversationId: sameFamily && conversationId !== family ? family : null,
    interactions: [], interactionPending: 0, conversationSearch: null,
    composer, context: state.context, ...patch });
}

/** Drops rows that are off screen once there are too many or they are too large. */
function evict(bodies) {
  let bytes = 0;
  const idle = [];
  for (const id of bodies.keys()) {
    if (watched.has(id) || kept.has(id)) continue;
    idle.push(id);
    bytes += bodySizes.get(id) ?? 0;
  }
  while (idle.length > CACHE_MAX_ENTRIES || (bytes > CACHE_MAX_BYTES && idle.length > 0)) {
    const id = idle.shift();
    bytes -= bodySizes.get(id) ?? 0;
    bodies.delete(id);
    bodySizes.delete(id);
  }
}

function showStreaming(message) {
  if (message &&
      !["SUCCESS", "STOPPED", "ERROR"].includes(state.bodies.get(message.id)?.status)) {
    update({ streaming: message });
  } else if (!message && state.streaming &&
      ["SUCCESS", "STOPPED", "ERROR"].includes(state.bodies.get(state.streaming.id)?.status)) {
    update({ streaming: null });
  } else if (!state.streaming) {
    update({ streaming: null });
  }
  // Otherwise retain the last frame until the watched terminal row arrives.
}

/**
 * Applies a WebJsonPatch.kt patch. Unchanged members keep their identity, so memoized rows and
 * blocks the patch does not touch skip re-rendering.
 */
export function applyStreamingPatch(base, patch) {
  if ("v" in patch) return patch.v;
  if ("a" in patch) return base + patch.a;
  if ("l" in patch) {
    const out = base.slice(0, patch.l);
    while (out.length < patch.l) out.push(null);
    for (const [index, entry] of Object.entries(patch.i ?? {})) out[+index] = applyStreamingPatch(out[+index], entry);
    return out;
  }
  const out = { ...base };
  for (const key of patch.d ?? []) delete out[key];
  for (const [key, entry] of Object.entries(patch.o ?? {})) out[key] = applyStreamingPatch(base[key], entry);
  return out;
}

function receive(event, size) {
  switch (event.type) {
    case "conversation_search": {
      const search = state.conversationSearch;
      if (!search || event.connectionId !== state.connectionId || event.conversationId !== state.openId ||
          event.seq !== openSeq || event.revision !== search.revision || event.query !== search.query) return;
      const matches = event.matches || [];
      const key = search.matches[search.index]?.key;
      update({ conversationSearch: { ...search, searching: event.searching, failed: !!event.failed,
        ...(!event.searching ? { matches, index: matches.findIndex(match => match.key === key) } : {}) } });
      break;
    }
    case "search":
      if (event.connectionId !== state.connectionId || event.revision !== searchRevision || event.query !== state.searchQuery) return;
      update({ searchLoading: event.searching, searchFailed: !!event.failed,
        ...(!event.searching ? { searchResults: event.items || [] } : {}) });
      break;
    case "subagents":
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      update({ subagents: event.items || [], parentConversationId: event.parentConversationId ?? null,
        initialToolsReady: state.initialToolsReady | 1 });
      break;
    case "todo":
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      update({ todo: event.items || [], initialToolsReady: state.initialToolsReady | 2 });
      break;
    case "interactions":
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      update({ interactions: event.items, initialToolsReady: state.initialToolsReady | 4,
        interactionPending: event.actionId >= state.interactionPending ? 0 : state.interactionPending });
      break;
    case "connection":
      streamFrame = null;
      update({ connectionId: event.connectionId,
        ...(event.connectionId !== state.connectionId ? { subagents: [], parentConversationId: null, initialToolsReady: 0 } : {}) });
      break;
    case "branch_replacement":
      if (event.connectionId !== state.connectionId || event.seq !== openSeq || event.conversationId !== state.openId) return;
      update({ branchReplacement: event.request ? event : null });
      break;
    case "scroll_to_bottom":
      if (event.conversationId === state.openId && event.seq === openSeq) update({ scrollRequest: event });
      break;
    case "opened":
      // ChatBottomBar keeps the model and controls it had while the next conversation loads, so the
      // phone never shows an empty bar. The browser keeps the same things - model, controls and the
      // context window - and drops only what belongs to the conversation being left behind.
      if (event.seq === openSeq && (event.conversationId ?? null) !== state.openId) {
        const request = state.switching;
        select(event.conversationId ?? null, request?.confirmation ? {
          switching: request, pageAction: state.pageAction, pageResult: state.pageResult,
        } : { switching: request?.command.type === "delete" ? { ...request, phase: "settling" } : request });
      }
      break;
    case "composer":
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      update({ composer: event, generating: event.generating,
        text: event.editRevision >= editRevision ? event.text : state.text,
        pendingAction: event.actionId >= state.pendingAction ? 0 : state.pendingAction });
      break;
    case "context": {
      // Only this connection's own target: two browsers, and the phone, price their own windows.
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      // ChatApp reads `contextProjection.usage` directly, so the figure of the conversation just
      // left stays on the ring and under the title until the new one is priced; an event without a
      // figure never blanks it. A failed projection has no figure to keep, as on the phone.
      const keepLastFigure = event.estimatedTokens == null && !event.failed && state.context;
      update({ context: keepLastFigure ? state.context : event });
      break;
    }
    case "snackbar":
      update({ snackbar: { id: ++noticeId, message: event.message } });
      break;
    case "page_action":
      if (event.seq !== openSeq || event.conversationId !== state.pageAction?.conversationId ||
          event.actionId !== state.pageAction.actionId || event.action !== state.pageAction.type) return;
      update({ pendingAction: event.actionId >= state.pendingAction ? 0 : state.pendingAction,
        pageAction: null, pageResult: event, shareText: event.action === "share" && event.success ? event.text : null,
        ...(state.switching?.id === event.actionId ? { switching: { ...state.switching, result: event,
          confirmation: event.success && state.switching.confirmation } } : {}) });
      break;
    case "conversation_delete":
      // Only the request this browser sent settles its own dialog. A delete started by another
      // client arrives as a shorter list, and as Deleted when it took the open conversation.
      if (!state.conversationDelete || event.actionId !== state.conversationDelete.actionId ||
          event.conversationId !== state.conversationDelete.conversationId) return;
      update({ pendingAction: event.actionId >= state.pendingAction ? 0 : state.pendingAction,
        conversationDelete: null, conversationDeleteResult: event,
        ...(state.switching?.id === event.actionId ? { switching: { ...state.switching, result: event,
          confirmation: event.success && state.switching.confirmation } } : {}) });
      break;
    case "conversations":
      update({ conversations: event.items, listLoading: false, listHasMore: !!event.hasMore,
        listLimit: event.limit || event.items.length });
      break;
    case "display":
      update({ display: event });
      break;
    case "row_action": {
      const request = state.switching;
      if (event.conversationId !== request?.command.conversationId || event.seq !== openSeq ||
          request.command.actionId !== event.actionId || request.phase !== "loading") return;
      update({ pendingAction: event.actionId >= state.pendingAction ? 0 : state.pendingAction,
        switching: { ...request, target: event.path, result: event,
          confirmation: event.success && request.confirmation } });
      break;
    }
    case "path": {
      if (event.conversationId !== state.openId || (event.seq ?? 0) !== openSeq) return;
      update({ path: event.messages, generating: event.generating, openStatus: "ready",
        ...(state.streaming && !event.messages.some(message => message.id === state.streaming.id) ? { streaming: null } : {}) });
      break;
    }
    case "payload": {
      if (event.conversationId !== state.openId || (event.seq ?? 0) !== openSeq) return;
      const bodies = new Map(state.bodies);
      bodies.delete(event.message.id); // Re-insert so the newest body is evicted last.
      bodies.set(event.message.id, event.message);
      bodySizes.set(event.message.id, size * 2);
      evict(bodies);
      if (state.streaming?.id === event.message.id &&
          ["SUCCESS", "STOPPED", "ERROR"].includes(event.message.status)) {
        update({ bodies, streaming: null });
      } else {
        update({ bodies });
      }
      break;
    }
    case "streaming":
      if (event.conversationId !== state.openId || (event.seq ?? 0) !== openSeq) return;
      streamFrame = event.message ?? null;
      showStreaming(streamFrame);
      break;
    case "streaming_patch":
      if (event.conversationId !== state.openId || (event.seq ?? 0) !== openSeq ||
          streamFrame?.id !== event.messageId) return;
      streamFrame = applyStreamingPatch(streamFrame, event.patch);
      showStreaming(streamFrame);
      break;
    case "deleted":
      if (event.conversationId === state.openId && (event.seq ?? 0) === openSeq) update({ openStatus: "deleted",
        switching: state.switching && !state.switching.confirmation ? { ...state.switching, phase: "settling" } : state.switching });
      break;
    case "load_failed":
      if (event.conversationId === state.openId && (event.seq ?? 0) === openSeq) update({ openStatus: "failed",
        switching: state.switching && !state.switching.confirmation ? { ...state.switching, phase: "settling" } : state.switching });
      break;
  }
}

function connect() {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(`${scheme}//${location.host}/api/sync`);
  socket = ws;
  ws.onopen = () => {
    if (socket !== ws || !running) return;
    retryMs = 1_000;
    update({ connected: true, composer: null, context: null, pendingAction: 0, pageAction: null, pageResult: null, shareText: null, branchReplacement: null, connectionId: null,
      conversationDelete: null, conversationDeleteResult: null,
      listLoading: true, listLimit: 0, todo: [], subagents: [], parentConversationId: null,
      initialToolsReady: 0, interactions: [], interactionPending: 0 });
    update({ searchQuery: "", searchResults: [], searchLoading: false, searchFailed: false });
    update({ conversationSearch: null });
    send({ type: "open", conversationId: state.openId, seq: openSeq });
    // Reconnection restores input only, never Send or Stop.
    editRevision = 0;
    if (state.text) send({ type: "draft", text: state.text, revision: ++editRevision, seq: openSeq });
    if (watched.size) send({ type: "watch", messageIds: [...watched] });
  };
  ws.onmessage = (message) => {
    if (socket === ws && running) receive(JSON.parse(message.data), message.data.length);
  };
  ws.onclose = async (close) => {
    if (socket !== ws) return;
    socket = null;
    uploads.forEach(controller => controller.abort());
    update({ connected: false, switching: state.switching ? { ...state.switching, phase: "settling", confirmation: false } : null,
      composer: null, context: null, pendingAction: 0, pageAction: null, pageResult: null, shareText: null, branchReplacement: null, connectionId: null,
      conversationDelete: null, conversationDeleteResult: null,
      todo: [], subagents: [], parentConversationId: null, initialToolsReady: 0, interactions: [], interactionPending: 0,
      conversationSearch: null,
      searchQuery: "", searchResults: [], searchLoading: false, searchFailed: false,
      snackbar: state.pendingAction ? { id: ++noticeId, message: t.sendUnconfirmed } : state.snackbar });
    // A refused upgrade also arrives here, so the session decides between retrying and signing out.
    const signedIn = close.code !== CLOSE_VIOLATED_POLICY && (await sessionSignedIn().catch(() => true));
    if (!running) return;
    if (!signedIn) {
      onSessionEnded();
      return;
    }
    retryTimer = setTimeout(() => { if (running && !socket) connect(); }, retryMs);
    retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
  };
}

export const sync = {
  conversationSearch(query) {
    if (!state.connected || !state.connectionId || !state.openId ||
        (state.conversationSearch && query === state.conversationSearch.query)) return;
    const revision = ++conversationSearchRevision;
    if (send({ type: "conversation_search", connectionId: state.connectionId, conversationId: state.openId,
      seq: openSeq, revision, text: query })) {
      update({ conversationSearch: { query, revision, searching: !!query.trim(), matches: [], index: -1, failed: false,
        positionRevision: 0, positionAfter: performance.now() + 300 } });
    }
  },
  dismissConversationSearch() {
    if (state.conversationSearch) {
      send({ type: "conversation_search", connectionId: state.connectionId, conversationId: state.openId,
        seq: openSeq, revision: ++conversationSearchRevision, text: "" });
      update({ conversationSearch: null });
    }
  },
  selectSearchMatch(index, revision, initial = false) {
    const search = state.conversationSearch;
    if (!search || search.revision !== revision || index < 0 || index >= search.matches.length || search.index === index) return;
    update({ conversationSearch: { ...search, index, positionRevision: search.positionRevision + 1,
      positionAfter: initial ? search.positionAfter : performance.now() } });
  },
  search(query) {
    if (!state.connected || !state.connectionId || query === state.searchQuery) return;
    if (send({ type: "search", text: query, revision: ++searchRevision, connectionId: state.connectionId })) {
      update({ searchQuery: query, searchLoading: !!query.trim(), searchFailed: false,
        ...(!query.trim() ? { searchResults: [] } : {}) });
    }
  },
  interactionTarget() {
    return state.connected && state.connectionId ? { connectionId: state.connectionId, seq: openSeq, conversationId: state.openId } : null;
  },
  interactionCommand(type, values, target) {
    if (!["question_submit", "question_skip", "shell_decision"].includes(type) || !state.connected || state.interactionPending || !target || target.connectionId !== state.connectionId ||
        target.seq !== openSeq || target.conversationId !== state.openId) return false;
    const command = { ...values, type, ...target, actionId: ++nextAction };
    if (!send(command)) return false;
    update({ interactionPending: command.actionId });
    return true;
  },
  pageCommand(type, target, values = {}) {
    if (!["fork", "share"].includes(type) || !target || !state.connected || !state.openId || state.pageAction || state.switching ||
        target.connectionId !== state.connectionId || target.seq !== openSeq || target.conversationId !== state.openId) return false;
    const command = { ...values, type, conversationId: state.openId, seq: openSeq, actionId: ++nextAction };
    if (type === "fork") {
      update({ pageAction: command, pageResult: null, shareText: null,
        switching: { id: command.actionId, command: { ...command, connectionId: target.connectionId }, phase: "covering", confirmation: true } });
    } else {
      if (!send(command)) return false;
      update({ pageAction: command, pageResult: null, shareText: null });
    }
    return command;
  },
  dismissShare() { update({ shareText: null }); },
  /**
   * One row-level command. The browser only names the row; the phone's owners resolve the branch,
   * the mutation and whether the row was the last one standing.
   */
  branchAck(type, event, success) {
    if (!state.connected || state.branchReplacement?.request.id !== event.request.id ||
        event.connectionId !== state.connectionId || event.seq !== openSeq || event.conversationId !== state.openId) return false;
    return send({ type, connectionId: event.connectionId, conversationId: event.conversationId,
      seq: event.seq, actionId: event.request.id, ...(success == null ? {} : { enabled: success }) });
  },
  rowCommand(type, messageId, values = {}) {
    if (!["edit", "regenerate", "delete", "recompact", "branch"].includes(type) || !state.connected || !state.openId) return false;
    if (type === "delete" || type === "branch") {
      const entry = state.path.find(entry => entry.id === messageId);
      if (!state.connectionId || state.openStatus !== "ready" || state.generating || state.pendingAction ||
          !entry || state.switching) return false;
      if (type === "branch" && (!entry.showBranchSelector || ![-1, 1].includes(values.direction) ||
          entry.branchIndex + values.direction < 0 || entry.branchIndex + values.direction >= entry.totalBranches)) return false;
      const command = { type, connectionId: state.connectionId, conversationId: state.openId, messageId, seq: openSeq,
        actionId: ++nextAction, ...(type === "branch" ? { direction: values.direction,
          branchAnchorMessageId: entry.branchAnchorMessageId, branchAnchorParentId: entry.branchAnchorParentId,
          branchIndex: entry.branchIndex, totalBranches: entry.totalBranches } : {}) };
      update({ switching: { id: command.actionId, command, phase: "covering", confirmation: !!values.confirmation } });
      return command;
    }
    return send({ type, conversationId: state.openId, messageId, seq: openSeq, ...values });
  },
  notify(message) { update({ snackbar: { id: ++noticeId, message } }); },
  loadMore() {
    if (!state.connected || state.listLoading || !state.listHasMore) return;
    if (send({ type: "list_more", tokens: state.listLimit })) update({ listLoading: true });
  },
  pin(conversationId, enabled) { send({ type: "pin", conversationId, enabled }); },
  generateTitle(conversationId) {
    if (!state.connected || state.openStatus === "loading") return false;
    return send({ type: "conversation_title", conversationId });
  },
  renameConversation(conversationId, title) {
    return send({ type: "conversation_rename", conversationId, text: title });
  },
  /**
   * Ask the phone's conversation owner to delete one drawer row. The list it sends back is the
   * success signal; the returned command is what settles the dialog through its own result.
   */
  deleteConversation(conversationId) {
    if (!state.connected || state.conversationDelete || state.switching) return null;
    const command = { type: "conversation_delete", conversationId, actionId: ++nextAction,
      connectionId: state.connectionId, seq: openSeq };
    if (conversationId === state.openId) {
      update({ conversationDelete: command, conversationDeleteResult: null,
        switching: { id: command.actionId, command, phase: "covering", confirmation: true } });
    } else {
      if (!send(command)) return null;
      update({ conversationDelete: command, conversationDeleteResult: null });
    }
    return command;
  },
  start(sessionEnded) {
    running = true;
    onSessionEnded = sessionEnded;
    if (!socket) connect();
  },
  stop() {
    running = false;
    clearTimeout(retryTimer);
    const ws = socket;
    socket = null;
    ws?.close();
    uploads.forEach(controller => controller.abort());
    editRevision = 0;
    openSeq = 0;
    select(null, { connected: false, switching: null, composer: null, text: "", pendingAction: 0, snackbar: null, connectionId: null,
      searchQuery: "", searchResults: [], searchLoading: false, searchFailed: false });
  },
  open(conversationId) {
    if (!state.connected || (conversationId === state.openId && !state.switching)) return;
    const command = { type: "open", conversationId, seq: openSeq + 1, connectionId: state.connectionId };
    if (conversationId == null) {
      if (!send(command)) return;
      openSeq = command.seq;
      editRevision = 0;
      select(null, { text: "", pendingAction: 0, switching: null });
      return;
    }
    update({ switching: { id: ++nextAction, command, phase: "covering" } });
  },
  covered(id) {
    const request = state.switching;
    if (!request || request.id !== id || request.phase !== "covering") return;
    const command = request.command;
    if (!state.connected || command.connectionId !== state.connectionId ||
        (command.type !== "open" && (command.conversationId !== state.openId || command.seq !== openSeq))) {
      update({ switching: { ...request, phase: "settling" } });
      return;
    }
    if (command.type === "open") {
      openSeq = command.seq;
      editRevision = 0;
      select(command.conversationId, { text: "", pendingAction: 0,
        switching: { ...request, phase: "loading" } });
    } else update({ pendingAction: command.actionId, switching: { ...request, phase: "loading" } });
    if (!send(command)) update({ switching: { ...request, phase: "settling", confirmation: false,
      result: { actionId: command.actionId, success: false } }, pendingAction: 0, pageAction: null,
      pageResult: { actionId: command.actionId, success: false }, conversationDelete: null,
      conversationDeleteResult: { actionId: command.actionId, success: false } });
  },
  confirmationExited(id) {
    if (state.switching?.id === id && state.switching.confirmation)
      update({ switching: { ...state.switching, confirmation: false } });
  },
  settled(id) {
    if (state.switching?.id === id && state.switching.phase === "settling")
      update({ switching: { ...state.switching, phase: "revealing" } });
  },
  revealed(id) {
    if (state.switching?.id === id && state.switching.phase === "revealing") update({ switching: null });
  },
  cancelSwitch(id) {
    if (state.switching?.id === id) update({ switching: null });
  },
  edit(text) {
    update({ text });
    send({ type: "draft", text, revision: ++editRevision, seq: openSeq });
  },
  submit() {
    if (!state.connected || !state.composer || state.pendingAction ||
        state.pendingPastes.some(item => item.conversationId === state.openId && item.uploading)) return;
    const phase = state.composer.phase;
    if (phase !== "IDLE" && phase !== "WAITING") return;
    const drain = phase === "IDLE" && !state.text.trim() && !state.composer.attachments?.length && !state.generating && state.composer.queue?.length;
    const command = { type: phase === "WAITING" ? "cancel_waiting" : drain ? "send_queued" : "send",
      seq: openSeq, actionId: ++nextAction, text: state.text };
    if (send(command)) update({ pendingAction: command.actionId });
  },
  selectModel(modelId) {
    if (!state.connected || !state.composer || state.pendingAction) return;
    const command = { type: "model", modelId, seq: openSeq, actionId: ++nextAction };
    if (send(command)) update({ pendingAction: command.actionId });
  },
  removeQueued(queuedId) {
    if (state.connected && state.composer) send({ type: "remove_queued", queuedId, seq: openSeq });
  },
  attachmentTarget() {
    return state.connected && state.connectionId && state.composer?.seq === openSeq
      ? { connectionId: state.connectionId, seq: openSeq, conversationId: state.openId } : null;
  },
  pasteText(text, target = this.attachmentTarget()) {
    return this.retainSandboxFiles([new File([text], "pasted-text.txt", { type: "text/plain" })], target);
  },
  retainSandboxFiles(files, target = this.attachmentTarget()) {
    if (!target || !files.length) return false;
    const current = this.attachmentTarget();
    const selected = current && target.connectionId === current.connectionId && target.seq === current.seq &&
      target.conversationId === current.conversationId;
    const pending = files.map(file => ({ id: ++noticeId, file, target,
      conversationId: target.conversationId, uploading: false }));
    update({ pendingPastes: [...state.pendingPastes, ...pending] });
    if (selected && state.composer?.sandboxAttachments && state.composer.phase === "IDLE") {
      const upload = async () => { for (const item of pending) await this.retryPaste(item.id); };
      upload();
    }
    return true;
  },
  async retryPaste(id) {
    const paste = state.pendingPastes.find(item => item.id === id);
    const target = this.attachmentTarget();
    if (!paste || paste.uploading || !target || paste.conversationId !== state.openId ||
        !state.composer?.sandboxAttachments || state.composer.phase !== "IDLE" ||
        (paste.conversationId === null && (paste.target.connectionId !== target.connectionId || paste.target.seq !== target.seq))) return false;
    // Retry is explicit. Bind it to the currently selected original conversation, never a new owner.
    update({ pendingPastes: state.pendingPastes.map(item => item === paste ? { ...paste, uploading: true } : item) });
    const uploaded = await this.uploadFiles([paste.file], "sandbox", target);
    update({ pendingPastes: uploaded ? state.pendingPastes.filter(item => item.id !== id)
      : state.pendingPastes.map(item => item.id === id ? { ...item, uploading: false } : item) });
    return uploaded;
  },
  downloadPaste(id) {
    const paste = state.pendingPastes.find(item => item.id === id);
    if (!paste) return;
    const url = URL.createObjectURL(paste.file);
    const link = document.createElement("a");
    link.href = url; link.download = paste.file.name; link.click();
    URL.revokeObjectURL(url);
  },
  discardPaste(id) {
    update({ pendingPastes: state.pendingPastes.filter(item => item.id !== id) });
  },
  async uploadFiles(files, forcedType, target) {
    if (!target || target.connectionId !== state.connectionId || !state.connected) return false;
    let succeeded = true;
    for (const file of files) {
      if (target.connectionId !== state.connectionId || !state.connected) return false;
      const controller = new AbortController();
      uploads.add(controller);
      try {
        const query = new URLSearchParams({ seq: String(target.seq), name: file.name, mime: file.type });
        if (forcedType) query.set("type", forcedType);
        const response = await fetch(`/api/attachments/${encodeURIComponent(target.connectionId)}?${query}`, {
          method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/octet-stream" },
          body: file, signal: controller.signal,
        });
        if (!response.ok) throw new Error(response.status === 413 ? t.fileTooLarge : t.fileLoadFailed);
      } catch (error) {
        succeeded = false;
        if (error.name !== "AbortError" && target.connectionId === state.connectionId) {
          update({ snackbar: { id: ++noticeId, message: error.message } });
        }
      } finally { uploads.delete(controller); }
    }
    return succeeded;
  },
  attachmentCommand(type, attachmentId, config = {}, target = this.attachmentTarget()) {
    if (!target || target.connectionId !== state.connectionId || target.seq !== openSeq ||
        !state.connected || !state.composer || state.pendingAction) return false;
    const command = { ...config, type, attachmentId, seq: openSeq, actionId: ++nextAction };
    if (!send(command)) return false;
    update({ pendingAction: command.actionId });
    return true;
  },
  setting(setting, value, target = this.attachmentTarget()) {
    if (!target || !state.connected || !state.composer || state.pendingAction ||
        target.connectionId !== state.connectionId || target.seq !== openSeq) return false;
    const command = { type: "setting", setting, seq: target.seq, modelId: state.composer.modelId, actionId: ++nextAction,
      ...(typeof value === "boolean" ? { enabled: value } : typeof value === "number" ? { tokens: value } : { value }) };
    if (!send(command)) return false;
    update({ pendingAction: command.actionId });
    return true;
  },
  editorCommand(type, values, target) {
    if (!target || !state.connected || !state.composer || state.pendingAction ||
        target.connectionId !== state.connectionId || target.seq !== openSeq || target.conversationId !== state.openId) return false;
    const command = { ...values, type, conversationId: target.conversationId, seq: target.seq, actionId: ++nextAction };
    if (!send(command)) return false;
    update({ pendingAction: command.actionId });
    return true;
  },
  attachmentUrl(id, kind, index = 0) {
    if (!state.connected || !state.connectionId || !state.composer) return null;
    return `/api/attachments/${encodeURIComponent(state.connectionId)}/${encodeURIComponent(id)}/${kind}/${index}?seq=${openSeq}`;
  },
  stopGeneration() {
    if (state.connected && state.composer && !state.composer.stopping) send({ type: "stop", seq: openSeq });
  },
  dismissSnackbar(id) {
    if (state.snackbar?.id === id) update({ snackbar: null });
  },
  consumeScroll(request) {
    if (state.scrollRequest === request) update({ scrollRequest: null });
  },
  /** Rows on screen; the server sends and keeps their bodies current. */
  /** Rows on screen that must keep their bodies whether or not they are watched. */
  keep(ids) {
    kept = new Set(ids);
  },
  watch(ids) {
    const next = new Set(ids);
    if (next.size === watched.size && [...next].every((id) => watched.has(id))) return;
    watched = next;
    send({ type: "watch", messageIds: [...next] });
  },
};

export function useSync() {
  const [snapshot, setSnapshot] = useState(state);
  useEffect(() => {
    listeners.add(setSnapshot);
    setSnapshot(state);
    return () => listeners.delete(setSnapshot);
  }, []);
  return snapshot;
}
