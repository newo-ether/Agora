import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { CircularProgress } from "./material/progress.js";
import { attachScrollbar } from "./material/scrollbar.js";
import { t } from "./i18n.js";
import {
  icon, ICON_ADD, ICON_MORE_VERT, ICON_SEARCH, ICON_PIN, ICON_CLOSE,
  ICON_DELETE, ICON_EDIT, ICON_REFRESH, ICON_QUESTION_MARK, ICON_ARROW_BACK,
} from "./icons.js";
import { MoreMenu } from "./menu.js";
import { sync } from "./sync.js";

function DrawerButton({ className, iconPath, label, onClick, disabled = true }) {
  return html`
    <button class=${`drawer-button ${className}`} type="button" disabled=${disabled} onClick=${onClick}>
      ${icon(iconPath)}<span>${label}</span>
    </button>`;
}

/**
 * A drawer row: 44 dp with 2 dp above and below, a capsule highlight on secondaryContainer when
 * selected, the title in bodyLarge, and an 18 dp slot for the generating spinner or unread dot.
 */
export function ConversationRow({ conversation, selected, onSelect, onMenu }) {
  // Pending interaction has priority over generation and unread status.
  const indicator = conversation.interaction || (conversation.generating ? "generating"
    : conversation.unread && !selected ? "unread" : null);
  return html`
    <div class="conversation-slot" role="listitem" data-conversation-id=${conversation.id}>
    <button class=${selected ? "conversation-row selected" : "conversation-row"} type="button"
      aria-current=${selected ? "true" : null} onClick=${() => onSelect(conversation.id)}
      onContextMenu=${event => { event.preventDefault(); onMenu(conversation, event.currentTarget); }}>
      <span class="conversation-title">${conversation.title}</span>
      ${indicator && html`<span class="conversation-indicator">
        ${indicator === "generating" && html`<${CircularProgress} size=${18} stroke=${2} />`}
        ${(indicator === "question" || indicator === "approval") && html`<span class="conversation-interaction" role="img" aria-label=${indicator === "approval" ? t.interactionApproval : t.interactionTitle}>${icon(ICON_QUESTION_MARK)}</span>`}
        ${indicator === "unread" && html`<span class="unread-dot" role="img" aria-label=${t.unreadGeneration}></span>`}
      </span>`}
    </button>
    <button class="conversation-more" type="button" title=${t.options} aria-label=${t.options}
      onClick=${event => onMenu(conversation, event.currentTarget)}>${icon(ICON_MORE_VERT)}</button>
    </div>`;
}

/** ChatDrawerContent: title, search, New Chat, then the conversation list. */
export function DrawerContent({ conversations, openId, onSelect, connected, loading, hasMore, reduceMotion,
  searchQuery, searchResults, searchLoading, searchFailed, drawerVisible = true, searchBack, onRequestRename, onRequestDelete, switching }) {
  const searching = !!searchQuery.trim();
  const list = useRef(null), bar = useRef(null), focusIndex = useRef(null);
  const placements = useRef(new Map());
  const [searchExpanded, setSearchExpanded] = useState(false);
  const searchField = useRef(null);
  function collapseSearch() {
    sync.search(""); searchField.current?.blur(); setSearchExpanded(false);
  }
  useLayoutEffect(() => {
    if (!searchBack) return;
    searchBack.current = drawerVisible && searchExpanded ? collapseSearch : null;
    return () => { searchBack.current = null; };
  }, [drawerVisible, searchExpanded, searchBack]);
  useLayoutEffect(() => {
    if (!drawerVisible) {
      if (searchExpanded || searchQuery) sync.search("");
      searchField.current?.blur();
      setSearchExpanded(false);
    }
  }, [drawerVisible]);
  useLayoutEffect(() => { if (searchExpanded && drawerVisible) searchField.current?.focus({ preventScroll: true }); }, [searchExpanded]);
  const [viewport, setViewport] = useState({ top: 0, height: 500 });
  const [menu, setMenu] = useState(null), [menuOpen, setMenuOpen] = useState(false);
  // Chrome runs no CSS transition on its own scrollbar, so this list draws its bar beside the native
  // gutter through the same owner the chat uses. The gutter itself stays and keeps every hit target.
  useEffect(() => attachScrollbar(list.current, bar.current), []);
  const rows = useMemo(() => {
    const pinned = conversations.filter(item => item.isPinned);
    const ordinary = conversations.filter(item => !item.isPinned);
    const result = [];
    let top = 0;
    const heading = (key, title) => { result.push({ key, title, top, height: 36 }); top += 36; };
    const append = item => { result.push({ key: item.id, conversation: item, top, height: 44 }); top += 44; };
    if (pinned.length) { heading("pinned-heading", t.pinned); pinned.forEach(append); }
    if (pinned.length && ordinary.length) heading("conversations-heading", t.conversations);
    ordinary.forEach(append);
    return { items: result, height: top };
  }, [conversations]);
  const visible = rows.items.filter(item => item.top + item.height >= viewport.top - 264 &&
    item.top <= viewport.top + viewport.height + 264);
  useLayoutEffect(() => {
    const node = list.current;
    const resize = () => setViewport({ top: node.scrollTop, height: node.clientHeight });
    const observer = new ResizeObserver(resize);
    observer.observe(node);
    resize();
    return () => observer.disconnect();
  }, []);
  useLayoutEffect(() => {
    const node = list.current;
    node.style.setProperty("--drawer-edge-top", node.scrollTop <= 2 ? "0" : "1");
    node.style.setProperty("--drawer-edge-bottom", node.scrollHeight - node.clientHeight - node.scrollTop <= 2 ? "0" : "1");
  }, [viewport, rows, searching, searchResults, loading]);
  useLayoutEffect(() => {
    if (!searching && connected && hasMore && !loading && viewport.top + viewport.height >= rows.height - 88) sync.loadMore();
    if (focusIndex.current != null) {
      const target = rows.items[focusIndex.current]?.conversation;
      if (target) list.current.querySelector(`[data-conversation-id="${CSS.escape(target.id)}"] .conversation-row`)?.focus({ preventScroll: true });
      focusIndex.current = null;
    }
    if (menu && !menu.anchor.isConnected) setMenuOpen(false);
  }, [viewport, rows, hasMore, loading, connected, searching]);
  useLayoutEffect(() => {
    const active = placements.current;
    const mounted = new Set();
    for (const node of list.current.querySelectorAll("[data-drawer-position]")) {
      const key = node.dataset.drawerPosition;
      const top = parseFloat(node.style.top);
      const previous = active.get(key);
      mounted.add(key);
      if (searching || reduceMotion) {
        previous?.animation?.cancel();
        active.set(key, { node, top, animation: null });
        continue;
      }
      if (previous?.node === node && previous.top !== top) {
        const transform = getComputedStyle(node).transform;
        const offset = transform === "none" ? 0 : new DOMMatrixReadOnly(transform).m42;
        const from = previous.top + offset - top;
        previous.animation?.cancel();
        const animation = node.animate([{ transform: `translateY(${from}px)` }, { transform: "translateY(0px)" }],
          { duration: 400, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "both" });
        const placement = { node, top, animation };
        active.set(key, placement);
        animation.onfinish = () => {
          if (active.get(key) !== placement) return;
          placement.animation = null;
          animation.cancel();
        };
      } else if (!previous || previous.node !== node) {
        previous?.animation?.cancel();
        active.set(key, { node, top, animation: null });
      }
    }
    for (const [key, placement] of active) if (!mounted.has(key)) {
      placement.animation?.cancel();
      active.delete(key);
    }
  });
  useLayoutEffect(() => () => {
    placements.current.forEach(placement => placement.animation?.cancel());
    placements.current.clear();
  }, []);
  useLayoutEffect(() => { list.current.scrollTop = 0; }, [searching]);
  function highlight(text) {
    if (!searchQuery.trim()) return text;
    const parts = [], lower = text.toLowerCase(), query = searchQuery.toLowerCase();
    let from = 0, index;
    while ((index = lower.indexOf(query, from)) >= 0) {
      parts.push(text.slice(from, index), html`<mark>${text.slice(index, index + query.length)}</mark>`);
      from = index + query.length;
    }
    parts.push(text.slice(from));
    return parts;
  }
  function keyboard(event) {
    if (searching) return;
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    const current = event.target.closest("[data-conversation-id]")?.dataset.conversationId;
    let index = rows.items.findIndex(item => item.key === current);
    const direction = event.key === "ArrowUp" || event.key === "End" ? -1 : 1;
    index = event.key === "Home" ? 0 : event.key === "End" ? rows.items.length - 1 : index + direction;
    while (index >= 0 && index < rows.items.length && !rows.items[index].conversation) index += direction;
    if (!rows.items[index]) return;
    event.preventDefault();
    focusIndex.current = index;
    list.current.scrollTop = Math.max(0, rows.items[index].top - viewport.height / 2 + 22);
    setViewport({ top: list.current.scrollTop, height: list.current.clientHeight });
  }
  return html`
    <div class="drawer-header" data-search-expanded=${searchExpanded}>
      <h2 class="drawer-title" aria-hidden=${searchExpanded}>${t.title}</h2>
      <div class="drawer-search" data-expanded=${searchExpanded}>
        <button class="drawer-search-toggle" type="button" aria-label=${searchExpanded ? t.close : t.searchHint}
          aria-expanded=${searchExpanded} onClick=${event => {
            if (searchExpanded) {
              collapseSearch();
              event.currentTarget.focus({ preventScroll: true });
            } else setSearchExpanded(true);
          }}>${icon(searchExpanded ? ICON_ARROW_BACK : ICON_SEARCH)}</button>
        ${searchExpanded && html`
        <input ref=${searchField} type="text" enterkeyhint="search" placeholder=${t.searchHint} aria-label=${t.searchHint} disabled=${!connected}
          value=${searchQuery} onInput=${event => sync.search(event.currentTarget.value)} />
        <span class="drawer-search-trailing">
          <span class="drawer-search-progress" data-active=${searchLoading} aria-hidden=${!searchLoading}>
            <${CircularProgress} size=${18} stroke=${2} label=${t.loading} />
          </span>
          <button class="drawer-search-clear" type="button" aria-label=${t.clearSearch}
            data-active=${!!searchQuery && !searchLoading} disabled=${!searchQuery || searchLoading}
            onClick=${() => sync.search("")}>${icon(ICON_CLOSE)}</button>
        </span>`}
      </div>
    </div>
    ${!searching && html`
    <${DrawerButton} className="filled new-chat" iconPath=${ICON_ADD} label=${t.newChat}
      disabled=${!connected} onClick=${() => onSelect(null)} />
    `}
    <div class="drawer-list" ref=${list} role="list" aria-label=${t.conversations} aria-busy=${searching ? searchLoading : loading}
      onKeyDown=${keyboard} onScroll=${event => setViewport({ top: event.currentTarget.scrollTop, height: event.currentTarget.clientHeight })}>
      ${searching ? html`
        ${searchResults.map(result => html`<div key=${result.id} role="listitem"><button class="drawer-search-result" type="button"
          disabled=${!connected} onClick=${() => { sync.search(""); onSelect(result.id); }}>
          <span class="drawer-search-result-heading"><span>${highlight(result.title)}</span>
            ${result.score > 0 && html`<span class="drawer-search-score">${Math.trunc(result.score * 100)}%</span>`}</span>
          ${result.snippets.map(snippet => html`<span class="drawer-search-snippet">${snippet.role === "USER" ? t.searchRoleUser : t.searchRoleModel}: ${highlight(snippet.text)}</span>`)}
        </button></div>`)}
        ${!searchLoading && !searchResults.length && html`<p class="drawer-search-empty" role="status">${searchFailed ? t.searchFailed : t.searchNoResults}</p>`}
      ` : html`<div class="drawer-list-window" style=${{ height: `${rows.height}px` }}>
        ${visible.map(item => html`<div key=${item.key} class="drawer-list-position" data-drawer-position=${item.key} style=${{ top: `${item.top}px`, height: `${item.height}px` }}>
          ${item.conversation ? html`<${ConversationRow} conversation=${item.conversation}
            selected=${item.key === openId} onSelect=${onSelect}
            onMenu=${(conversation, anchor) => { setMenu({ conversation, anchor }); setMenuOpen(true); }} />`
            : html`<h3 class="drawer-section">${item.title}</h3>`}
        </div>`)}
      </div>
      ${loading && html`<div class="drawer-list-progress"><${CircularProgress} size=${32} stroke=${3} label=${t.loading} /></div>`}
      `}
    </div>
    <div class="scrollbar-overlay drawer-scrollbar" ref=${bar} aria-hidden="true"><span></span></div>
    ${menu && html`<${MoreMenu} expanded=${menuOpen} reduceMotion=${reduceMotion} anchor=${{ current: menu.anchor }}
      onClose=${() => setMenuOpen(false)} onExited=${() => setMenu(null)}>
      <button class="dropdown-item" role="menuitem" type="button" disabled=${!connected || switching}
        onClick=${() => { sync.pin(menu.conversation.id, !menu.conversation.isPinned); setMenuOpen(false); }}>
        ${icon(ICON_PIN)}<span>${menu.conversation.isPinned ? t.unpin : t.pin}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button"
        disabled=${!connected || switching}
        onClick=${() => { sync.generateTitle(menu.conversation.id); setMenuOpen(false); }}>
        ${icon(ICON_REFRESH)}<span>${t.generateTitle}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled=${!connected || switching}
        onClick=${() => { onRequestRename({ conversationId: menu.conversation.id, title: menu.conversation.title, anchor: menu.anchor }); setMenuOpen(false); }}>
        ${icon(ICON_EDIT)}<span>${t.rename}</span>
      </button>
      <button class="dropdown-item destructive" role="menuitem" type="button" disabled=${!connected || switching}
        onClick=${() => { onRequestDelete({ conversationId: menu.conversation.id, title: menu.conversation.title, anchor: menu.anchor }); setMenuOpen(false); }}>
        ${icon(ICON_DELETE)}<span>${t.delete}</span>
      </button>
    </${MoreMenu}>`}`;
}
