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
  interactions: [],
  interactionPending: 0,
  snackbar: null,
  scrollRequest: null,
  connectionId: null,
};
const bodySizes = new Map();
let watched = new Set();
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
const uploads = new Set();

function update(patch) {
  state = { ...state, ...patch };
  listeners.forEach((listener) => listener(state));
}

function send(command) {
  if (socket?.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(command));
  return true;
}

function select(conversationId, patch = {}) {
  watched = new Set();
  bodySizes.clear();
  const composer = state.composer ? { ...state.composer, attachments: [], queue: [], text: "",
    phase: "IDLE", generating: false, stopping: false } : null;
  update({ openId: conversationId, openStatus: conversationId ? "loading" : null,
    path: [], generating: false, streaming: null, bodies: new Map(), scrollRequest: null,
    pageAction: null, pageResult: null, shareText: null, interactions: [], interactionPending: 0, conversationSearch: null,
    composer, context: state.context, ...patch });
}

/** Drops rows that are off screen once there are too many or they are too large. */
function evict(bodies) {
  let bytes = 0;
  const idle = [];
  for (const id of bodies.keys()) {
    if (watched.has(id)) continue;
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
    case "interactions":
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      update({ interactions: event.items, interactionPending: event.actionId >= state.interactionPending ? 0 : state.interactionPending });
      break;
    case "connection":
      update({ connectionId: event.connectionId });
      break;
    case "scroll_to_bottom":
      if (event.conversationId === state.openId && event.seq === openSeq) update({ scrollRequest: event });
      break;
    case "opened":
      // ChatBottomBar keeps the model and controls it had while the next conversation loads, so the
      // phone never shows an empty bar. The browser keeps the same things - model, controls and the
      // context window - and drops only what belongs to the conversation being left behind.
      if (event.seq === openSeq && (event.conversationId ?? null) !== state.openId)
        select(event.conversationId ?? null);
      break;
    case "composer":
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      update({ composer: event, generating: event.generating,
        text: event.editRevision >= editRevision ? event.text : state.text,
        pendingAction: event.actionId >= state.pendingAction ? 0 : state.pendingAction });
      break;
    case "context":
      // Only this connection's own target: two browsers, and the phone, price their own windows.
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      update({ context: event });
      break;
    case "snackbar":
      update({ snackbar: { id: ++noticeId, message: event.message } });
      break;
    case "page_action":
      if (event.seq !== openSeq || (event.conversationId ?? null) !== state.openId ||
          event.actionId !== state.pageAction?.actionId || event.action !== state.pageAction.type) return;
      update({ pageAction: null, pageResult: event, shareText: event.action === "share" && event.success ? event.text : null });
      break;
    case "conversations":
      update({ conversations: event.items, listLoading: false, listHasMore: !!event.hasMore,
        listLimit: event.limit || event.items.length });
      break;
    case "display":
      update({ display: event });
      break;
    case "path":
      if (event.conversationId !== state.openId) return;
      if (state.streaming && !event.messages.some((message) => message.id === state.streaming.id)) {
        update({ path: event.messages, generating: event.generating, streaming: null, openStatus: "ready" });
      } else {
        update({ path: event.messages, generating: event.generating, openStatus: "ready" });
      }
      break;
    case "payload": {
      if (event.conversationId !== state.openId) return;
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
      if (event.conversationId !== state.openId) return;
      if (event.message &&
          !["SUCCESS", "STOPPED", "ERROR"].includes(state.bodies.get(event.message.id)?.status)) {
        update({ streaming: event.message });
      } else if (!event.message && state.streaming &&
          ["SUCCESS", "STOPPED", "ERROR"].includes(state.bodies.get(state.streaming.id)?.status)) {
        update({ streaming: null });
      } else if (!state.streaming) {
        update({ streaming: null });
      }
      // Otherwise retain the last frame until the watched terminal row arrives.
      break;
    case "deleted":
      if (event.conversationId === state.openId) update({ openStatus: "deleted" });
      break;
    case "load_failed":
      if (event.conversationId === state.openId) update({ openStatus: "failed" });
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
    update({ connected: true, composer: null, context: null, pendingAction: 0, pageAction: null, pageResult: null, shareText: null, connectionId: null,
      listLoading: true, listLimit: 0, interactions: [], interactionPending: 0 });
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
    update({ connected: false, composer: null, context: null, pendingAction: 0, pageAction: null, pageResult: null, shareText: null, connectionId: null,
      interactions: [], interactionPending: 0,
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
    if (!["fork", "share"].includes(type) || !target || !state.connected || !state.openId || state.pageAction ||
        target.connectionId !== state.connectionId || target.seq !== openSeq || target.conversationId !== state.openId) return false;
    const command = { ...values, type, conversationId: state.openId, seq: openSeq, actionId: ++nextAction };
    if (!send(command)) return false;
    update({ pageAction: command, pageResult: null, shareText: null });
    return command;
  },
  dismissShare() { update({ shareText: null }); },
  /**
   * One row-level command. The browser only names the row; the phone's owners resolve the branch,
   * the mutation and whether the row was the last one standing.
   */
  rowCommand(type, messageId, values = {}) {
    if (!["edit", "regenerate", "delete"].includes(type) || !state.connected || !state.openId) return false;
    return send({ type, conversationId: state.openId, messageId, seq: openSeq, ...values });
  },
  notify(message) { update({ snackbar: { id: ++noticeId, message } }); },
  loadMore() {
    if (!state.connected || state.listLoading || !state.listHasMore) return;
    if (send({ type: "list_more", tokens: state.listLimit })) update({ listLoading: true });
  },
  pin(conversationId, enabled) { send({ type: "pin", conversationId, enabled }); },
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
    select(null, { connected: false, composer: null, text: "", pendingAction: 0, snackbar: null, connectionId: null,
      searchQuery: "", searchResults: [], searchLoading: false, searchFailed: false });
  },
  open(conversationId) {
    if (!state.connected || conversationId === state.openId) return;
    openSeq++;
    editRevision = 0;
    select(conversationId, { text: "", pendingAction: 0 });
    send({ type: "open", conversationId, seq: openSeq });
  },
  edit(text) {
    update({ text });
    send({ type: "draft", text, revision: ++editRevision, seq: openSeq });
  },
  submit() {
    if (!state.connected || !state.composer || state.pendingAction) return;
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
      ? { connectionId: state.connectionId, seq: openSeq } : null;
  },
  async uploadFiles(files, forcedType, target) {
    if (!target || target.connectionId !== state.connectionId || !state.connected) return;
    for (const file of files) {
      if (target.connectionId !== state.connectionId || !state.connected) return;
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
        if (error.name !== "AbortError" && target.connectionId === state.connectionId) {
          update({ snackbar: { id: ++noticeId, message: error.message } });
        }
      } finally { uploads.delete(controller); }
    }
  },
  attachmentCommand(type, attachmentId, config = {}) {
    if (!state.connected || !state.composer || state.pendingAction) return false;
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
