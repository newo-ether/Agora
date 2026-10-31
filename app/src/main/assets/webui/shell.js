import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { icon, ICON_CLOSE } from "./icons.js";
import { MoreMenu } from "./menu.js";
import { sync, useSync } from "./sync.js";
import { MessageList } from "./messages.js";
import { Composer } from "./composer.js";
import { DrawerContent } from "./shell-drawer.js";
import { TopBar } from "./shell-topbar.js";
import { ConversationDialog, ConversationRenameDialog, ConversationDeleteDialog } from "./shell-dialogs.js";
export { ConversationRow } from "./shell-drawer.js";
/*
 * Chat frame, mirroring the app's chat screen (ChatTopBar, ChatDrawerContent, ChatBottomBar).
 * Controls the browser cannot use yet are shown as in the app but disabled.
 */
// ChatDrawerHost.usesSideBySideDrawer: wider than DRAWER_MAX_WIDTH (360) + CHAT_APP_WIDTH_THRESHOLD (600).
const SIDE_BY_SIDE_QUERY = "(min-width: 960.02px)";
// DrawerPinned is the persisted wide-window preference; the narrow window never reads or writes it.
const DRAWER_PINNED_KEY = "agora.drawerPinned";
const readDrawerPinned = () => { try { return localStorage.getItem(DRAWER_PINNED_KEY) === "true"; } catch (_) { return false; } };

function useMediaQuery(query) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener("change", update);
    return () => list.removeEventListener("change", update);
  }, [query]);
  return matches;
}

export function Shell({ onSignedOut }) {
  const state = useSync();
  const snackbarNode = useRef(null);
  const snackbarMotion = useRef(null);
  const nextSnackbar = useRef(state.snackbar);
  nextSnackbar.current = state.snackbar;
  const [snackbar, setSnackbar] = useState(null);
  const [snackbarVisible, setSnackbarVisible] = useState(false);
  useEffect(() => {
    let handoff;
    if (state.snackbar) {
      if (snackbar && snackbarVisible) {
        setSnackbarVisible(false);
        // MainActivity replaces the retained payload halfway through its 400 ms exit.
        handoff = setTimeout(() => {
          setSnackbar(state.snackbar);
          setSnackbarVisible(true);
        }, 200);
      } else {
        setSnackbar(state.snackbar);
        setSnackbarVisible(true);
      }
    } else setSnackbarVisible(false);
    return () => clearTimeout(handoff);
  }, [state.snackbar?.id]);
  useEffect(() => {
    if (!snackbar) return;
    const timer = setTimeout(() => sync.dismissSnackbar(snackbar.id), 4_000);
    return () => clearTimeout(timer);
  }, [snackbar?.id]);
  useLayoutEffect(() => {
    const node = snackbarNode.current;
    if (!node) return;
    const reduced = !!state.display?.reduceMotion;
    const previous = snackbarMotion.current;
    const samePayload = previous?.id === snackbar.id;
    const style = getComputedStyle(node);
    const from = samePayload ? { opacity: style.opacity, transform: style.transform }
      : { opacity: 0, transform: reduced ? "none" : "scale(.8)" };
    previous?.animation.cancel();
    const target = { opacity: snackbarVisible ? 1 : 0,
      transform: reduced ? "none" : `scale(${snackbarVisible ? 1 : .8})` };
    const animation = node.animate([from, target], {
      duration: 400, easing: "cubic-bezier(.4,0,.2,1)", fill: "both",
    });
    snackbarMotion.current = { id: snackbar.id, animation };
    animation.finished.then(() => {
      if (!snackbarVisible && snackbarMotion.current?.animation === animation && !nextSnackbar.current) {
        setSnackbar(null);
      }
    }).catch(() => {});
  }, [snackbar?.id, snackbarVisible, state.display?.reduceMotion]);
  useLayoutEffect(() => () => snackbarMotion.current?.animation.cancel(), []);
  useEffect(() => {
    sync.start(onSignedOut);
    return () => sync.stop();
  }, []);
  const sideBySide = useMediaQuery(SIDE_BY_SIDE_QUERY);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerPinned, setDrawerPinned] = useState(readDrawerPinned);
  const [composerExpanded, setComposerExpanded] = useState(false);
  const [pageDialog, setPageDialog] = useState(null);
  // Drawer and current-conversation actions share the same retained dialogs.
  const [rowDialog, setRowDialog] = useState(null);
  const conversationActionsEnabled = state.connected && !!state.openId && state.openStatus === "ready" &&
    !state.switching && !state.pendingAction && !state.pageAction && !state.conversationDelete &&
    !["SUBMITTING", "ACCEPTED_PENDING_CLEAR"].includes(state.composer?.phase);
  const deleteResult = state.conversationDeleteResult;
  useEffect(() => {
    if (pageDialog && (pageDialog.connectionId !== state.connectionId ||
        pageDialog.seq !== sync.interactionTarget()?.seq ||
        (pageDialog.conversationId !== state.openId && !state.switching?.confirmation))) setPageDialog(null);
  }, [state.connectionId, state.openId, state.switching, pageDialog]);
  useEffect(() => { if (pageDialog?.type === "share" && !state.pageAction && !state.shareText) setPageDialog(null); }, [state.pageAction, state.shareText]);
  // A row names the message it forks or shares; the top bar names none and so acts on the whole
  // conversation, which is what the phone's owners do with a null message id.
  function openPageAction(type, messageId = null) {
    const target = { ...sync.attachmentTarget(), conversationId: state.openId, type, messageId };
    const values = messageId == null ? {} : { messageId };
    if (type !== "share" || sync.pageCommand(type, target, values)) setPageDialog(target);
  }
  const shell = useRef(null);
  const drawerTarget = useRef(0);
  const previousWide = useRef(null);
  const drawerAnimation = useRef(null);
  const drawerDrag = useRef(null);
  const dragClick = useRef(null);
  const drawerDismissedComposer = useRef(false);
  const drawerFrame = useRef(0);
  const drawer = useRef(null);
  const drawerSearchBack = useRef(null);
  const menuButton = useRef(null);
  const wasOpen = useRef(false);
  const wasModalOpen = useRef(false);
  const modalOpen = drawerOpen && !sideBySide;
  const reduceMotion = !!state.display?.reduceMotion;
  function observeDrawerProgress(progress) {
    const crossed = progress > 0.5;
    if (crossed && !drawerDismissedComposer.current) {
      shell.current.querySelector(".composer textarea")?.blur();
      setComposerExpanded(false);
    }
    drawerDismissedComposer.current = crossed;
  }
  useLayoutEffect(() => {
    const chat = shell.current.querySelector(".chat");
    const composer = chat.querySelector(".composer-host");
    const tools = composer.querySelector(".tools-scroll");
    const capsules = [...chat.querySelectorAll(".top-bar > .capsule")];
    const measure = () => {
      const bounds = chat.getBoundingClientRect();
      const bottoms = capsules.map((node) => node.getBoundingClientRect().bottom);
      // An empty capsule row would measure to -Infinity, which is not a length and would leave every
      // inset unset, so it falls back to a small positive inset instead of poisoning the pair.
      const top = Math.max(...bottoms, bounds.top + 8) - bounds.top + 8;
      const toolsInset = Number.parseFloat(chat.style.getPropertyValue("--chat-tools-message-inset")) || 0;
      const bottom = bounds.bottom - composer.getBoundingClientRect().top + toolsInset;
      chat.style.setProperty("--chat-top-inset", `${top}px`);
      chat.style.setProperty("--chat-bottom-inset", `${bottom}px`);
    };
    const geometry = new ResizeObserver(measure);
    [chat, composer, tools, ...capsules].filter(Boolean).forEach((node) => geometry.observe(node, { box: "border-box" }));
    measure();
    tools?.addEventListener("toolsresize", measure);
    return () => {
      geometry.disconnect();
      tools?.removeEventListener("toolsresize", measure);
    };
  }, []);
  // One interpolated progress drives drawer, scrim and desktop inset; freeze its actual value on takeover.
  function freezeDrawer() {
    const progress = Number(getComputedStyle(shell.current).getPropertyValue("--drawer-progress"));
    shell.current.style.setProperty("--drawer-progress", String(progress));
    drawerAnimation.current?.cancel();
    drawerAnimation.current = null;
    cancelAnimationFrame(drawerFrame.current);
    observeDrawerProgress(progress);
    return progress;
  }
  function settleDrawer(open) {
    const from = freezeDrawer();
    const to = open ? 1 : 0;
    drawerTarget.current = to;
    setDrawerOpen(from > 0 || to > 0);
    if (reduceMotion || from === to) {
      shell.current.style.setProperty("--drawer-progress", String(to));
      observeDrawerProgress(to);
      setDrawerOpen(to > 0);
      return;
    }
    const animation = shell.current.animate(
      [{ "--drawer-progress": String(from) }, { "--drawer-progress": String(to) }],
      { duration: 300, easing: "cubic-bezier(0, 0, 0.2, 1)", fill: "forwards" },
    );
    drawerAnimation.current = animation;
    const observe = () => {
      if (drawerAnimation.current !== animation) return;
      observeDrawerProgress(Number(getComputedStyle(shell.current).getPropertyValue("--drawer-progress")));
      drawerFrame.current = requestAnimationFrame(observe);
    };
    drawerFrame.current = requestAnimationFrame(observe);
    animation.onfinish = () => {
      if (drawerAnimation.current !== animation) return;
      shell.current.style.setProperty("--drawer-progress", String(to));
      animation.cancel();
      drawerAnimation.current = null;
      cancelAnimationFrame(drawerFrame.current);
      observeDrawerProgress(to);
      setDrawerOpen(to > 0);
    };
  }
  function endDrawerDrag(event, cancelled = false) {
    const drag = drawerDrag.current;
    if (!drag || (event && drag.id !== event.pointerId)) return;
    drawerDrag.current = null;
    if (shell.current.hasPointerCapture(drag.id)) shell.current.releasePointerCapture(drag.id);
    if (!drag.accepted) {
      if (drag.interrupted) settleDrawer(drawerTarget.current > 0);
      return;
    }
    dragClick.current = drag.id;
    const progress = freezeDrawer();
    const velocity = event && event.timeStamp - drag.time < 100 ? drag.velocity : 0;
    settleDrawer(cancelled ? drawerTarget.current > 0 : velocity === 0 ? progress >= 0.5 : velocity > 0);
  }
  function beginDrawerDrag(event) {
    dragClick.current = null;
    if (sideBySide || !event.isPrimary || event.button !== 0 ||
        event.target.closest(".detail-sheet-layer, .dropdown, dialog, input, textarea, a, [contenteditable]") ||
        !window.getSelection()?.isCollapsed ||
        (event.pointerType === "mouse" && event.target.closest(".markdown, .user-text"))) return;
    for (let node = event.target; node && node !== shell.current; node = node.parentElement) {
      if (node.scrollWidth > node.clientWidth + 1 && /auto|scroll/.test(getComputedStyle(node).overflowX)) return;
    }
    const interrupted = drawerAnimation.current != null;
    drawerDrag.current = { id: event.pointerId, x: event.clientX, y: event.clientY,
      lastX: event.clientX, time: event.timeStamp, velocity: 0, accepted: false, interrupted, progress: freezeDrawer() };
  }
  function moveDrawerDrag(event) {
    const drag = drawerDrag.current;
    if (!drag || drag.id !== event.pointerId) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    if (!drag.accepted) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) < 8) return;
      if (Math.abs(dy) >= Math.abs(dx)) { endDrawerDrag(event, true); return; }
      drag.accepted = true;
      shell.current.setPointerCapture(event.pointerId);
    }
    const elapsed = event.timeStamp - drag.time;
    if (elapsed > 0) drag.velocity = (event.clientX - drag.lastX) / elapsed;
    drag.lastX = event.clientX;
    drag.time = event.timeStamp;
    const progress = Math.max(0, Math.min(1, drag.progress + dx / drawer.current.clientWidth));
    shell.current.style.setProperty("--drawer-progress", String(progress));
    observeDrawerProgress(progress);
    setDrawerOpen(progress > 0);
    event.preventDefault();
  }
  useLayoutEffect(() => {
    const node = shell.current;
    const stopOwnedTouch = (event) => { if (drawerDrag.current?.accepted) event.preventDefault(); };
    const onResize = () => endDrawerDrag(null, true);
    node.addEventListener("touchmove", stopOwnedTouch, { passive: false });
    window.addEventListener("resize", onResize);
    return () => {
      node.removeEventListener("touchmove", stopOwnedTouch);
      window.removeEventListener("resize", onResize);
    };
  }, [sideBySide, reduceMotion]);
  useEffect(() => () => { drawerAnimation.current?.cancel(); cancelAnimationFrame(drawerFrame.current); }, []);
  // Wide/narrow state machine: entering wide restores the expansion from the persisted pin,
  // entering narrow always collapses the overlay and leaves the pin untouched. A same-mode
  // re-run (motion setting change) just re-settles at the current expansion.
  useLayoutEffect(() => {
    endDrawerDrag(null, true);
    const was = previousWide.current;
    previousWide.current = sideBySide;
    if (was === null) {
      const open = sideBySide && readDrawerPinned();
      drawerTarget.current = open ? 1 : 0;
      shell.current.style.setProperty("--drawer-progress", open ? "1" : "0");
      setDrawerOpen(open);
      return;
    }
    if (was === sideBySide) { settleDrawer(drawerTarget.current > 0); return; }
    settleDrawer(sideBySide && readDrawerPinned());
  }, [sideBySide, reduceMotion]);
  // ChatTopBar falls back to the brand while the title is blank.
  const drawerConversationId = state.parentConversationId || state.openId;
  const openConversation = state.subagents.find(c => c.id === state.openId) ||
    state.conversations.find(c => c.id === state.openId);
  const openTitle = openConversation?.title?.trim() || null;
  // ChatTopBar stacks the token subtitle under a conversation title and hides it with the brand
  // title, or while there is nothing in the window yet (`contextAvailable = totalTokens > 0`). The
  // figure is the composer ring's own: this connection's projection, kept while the next one prices.
  const contextSubtitle = openTitle && (state.context?.estimatedTokens ?? 0) > 0
    ? t.contextUsage(state.context.estimatedLabel, state.context.budgetLabel) : null;

  useLayoutEffect(() => {
    // The chat is inert until the closed state renders, so focus returns to the menu button here.
    if (!drawerOpen && wasOpen.current) menuButton.current?.focus();
    wasOpen.current = drawerOpen;
    const enteringModal = modalOpen && !wasModalOpen.current;
    wasModalOpen.current = modalOpen;
    if (!drawerOpen) return undefined;
    // The panel itself takes focus on entry; focusing a control here would focus the search
    // field first, which on a phone opens the soft keyboard without the user asking for it.
    if (enteringModal) drawer.current?.focus();
    const onKey = (event) => {
      if (event.defaultPrevented || shell.current.querySelector(".detail-sheet-layer, .dropdown, dialog[open]")) return;
      if (!modalOpen && !drawer.current.contains(event.target)) return;
      if (event.key === "Escape") {
        if (drawerSearchBack.current) {
          event.preventDefault();
          drawerSearchBack.current();
          drawer.current.querySelector(".drawer-search-toggle")?.focus({ preventScroll: true });
        } else if (modalOpen) { event.preventDefault(); settleDrawer(false); }
      }
      if (event.key === "Tab" && modalOpen) {
        const controls = [...drawer.current.querySelectorAll("input:not([disabled]), button:not([disabled])")];
        const first = controls[0] ?? drawer.current;
        const last = controls.at(-1) ?? drawer.current;
        if (!drawer.current.contains(document.activeElement) ||
            (event.shiftKey ? document.activeElement === first : document.activeElement === last)) {
          event.preventDefault();
          (event.shiftKey ? last : first).focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen, modalOpen, reduceMotion]);

  const shellClass = ["shell", drawerOpen && "drawer-open", sideBySide ? "side-by-side" : "modal"]
    .filter(Boolean).join(" ");
  return html`
    <div class=${shellClass} ref=${shell} data-blur-effects=${state.display?.blurEffectsEnabled == null ? null : String(state.display.blurEffectsEnabled)}
      data-reduce-motion=${state.display?.reduceMotion == null ? null : String(state.display.reduceMotion)}
      onPointerDown=${beginDrawerDrag} onPointerMove=${moveDrawerDrag}
      onPointerUp=${(event) => endDrawerDrag(event)} onPointerCancel=${(event) => endDrawerDrag(event, true)}
      onLostPointerCapture=${(event) => { if (event.target === shell.current) endDrawerDrag(event, true); }}
      onClickCapture=${(event) => {
        if (event.pointerId === dragClick.current) { event.preventDefault(); event.stopPropagation(); dragClick.current = null; }
      }}>
      <aside id="drawer" class="drawer" ref=${drawer} aria-label=${t.conversations} tabindex="-1"
        role=${sideBySide ? null : "dialog"} aria-modal=${modalOpen ? "true" : null}
        inert=${!drawerOpen}>
        <${DrawerContent} conversations=${state.conversations} openId=${drawerConversationId}
          connected=${state.connected} loading=${state.listLoading} hasMore=${state.listHasMore} reduceMotion=${reduceMotion} drawerVisible=${drawerOpen} searchBack=${drawerSearchBack}
          switching=${!!state.switching || state.openStatus === "loading"}
          searchQuery=${state.searchQuery} searchResults=${state.searchResults} searchLoading=${state.searchLoading} searchFailed=${state.searchFailed}
          onRequestRename=${(request) => setRowDialog({ ...request, type: "rename" })}
          onRequestDelete=${(request) => setRowDialog({ ...request, type: "delete" })}
          onSelect=${(id) => { if (id == null || id !== drawerConversationId) sync.open(id); if (!sideBySide) settleDrawer(false); }} />
      </aside>
      <div class="scrim" aria-hidden="true" onClick=${() => settleDrawer(false)}></div>
      <main class=${`chat ${composerExpanded ? "chat-composer-expanded" : ""}`} inert=${modalOpen}>
        <${TopBar} title=${openTitle} storedTitle=${openConversation?.title ?? ""} subtitle=${contextSubtitle} conversationId=${state.openId} parentConversationId=${state.parentConversationId} drawerOpen=${drawerOpen} menuButton=${menuButton} reduceMotion=${reduceMotion} onSignedOut=${onSignedOut} connected=${state.connected}
          generating=${!!state.generating}
          conversationActionsEnabled=${conversationActionsEnabled}
          onRequestRename=${(request) => setRowDialog({ ...request, type: "rename" })}
          onRequestDelete=${(request) => setRowDialog({ ...request, type: "delete" })}
          search=${state.conversationSearch}
          onSystemPrompt=${() => openPageAction("system_prompt")}
          promptEnabled=${state.connected && !!state.composer?.systemPrompt && !state.composer?.controls?.lowContextModeEnabled && !state.pendingAction}
          onFork=${() => openPageAction("fork")} onShare=${() => openPageAction("share")} pageBusy=${!!state.pageAction}
          onToggleDrawer=${() => {
            if (state.parentConversationId) { sync.open(state.parentConversationId); return; }
            if (!sideBySide) { settleDrawer(drawerTarget.current === 0); return; }
            // In the wide window the button toggles the persisted pin, not a temporary overlay.
            const next = !drawerPinned;
            setDrawerPinned(next);
            try { localStorage.setItem(DRAWER_PINNED_KEY, String(next)); } catch (_) {}
            settleDrawer(next);
          }} />
        <${MessageList} state=${state} label=${openTitle || t.newChat} onPageAction=${openPageAction} />
        <div class="chat-top-blur" aria-hidden="true"><span></span><span></span><span></span><span></span></div>
        <${Composer} state=${state} MoreMenu=${MoreMenu} expanded=${composerExpanded} onExpandedChange=${setComposerExpanded} />
        ${snackbar && html`<div class="chat-snackbar-host" inert=${!snackbarVisible}>
          <div class="chat-snackbar" ref=${snackbarNode} role=${snackbarVisible ? "status" : null}
            aria-hidden=${!snackbarVisible}>
            <span class="chat-snackbar-text">${snackbar.message}</span>
            <button class="chat-snackbar-close" type="button" aria-label=${t.cancel}
              onClick=${() => sync.dismissSnackbar(snackbar.id)}>${icon(ICON_CLOSE)}</button>
          </div></div>`}
        ${pageDialog && html`<${ConversationDialog} state=${state} request=${pageDialog} onClose=${() => setPageDialog(null)} />`}
      </main>
      ${rowDialog?.type === "rename" && html`<${ConversationRenameDialog} conversation=${rowDialog} onClose=${() => setRowDialog(null)} />`}
      ${rowDialog?.type === "delete" && html`<${ConversationDeleteDialog} actionId=${rowDialog.actionId}
        complete=${rowDialog.actionId != null && deleteResult?.actionId === rowDialog.actionId && deleteResult.success}
        pending=${rowDialog.actionId != null && state.conversationDelete?.actionId === rowDialog.actionId}
        failed=${rowDialog.failed === true ||
          (rowDialog.actionId != null && deleteResult?.actionId === rowDialog.actionId && !deleteResult.success)}
        onConfirm=${() => {
          const command = sync.deleteConversation(rowDialog.conversationId);
          setRowDialog(command ? { ...rowDialog, actionId: command.actionId, failed: false } : { ...rowDialog, failed: true });
        }}
        onClose=${() => setRowDialog(null)} />`}
    </div>`;
}
