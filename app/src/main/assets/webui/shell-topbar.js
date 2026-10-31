import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { icon, ICON_ADD, ICON_MENU, ICON_MORE_VERT, ICON_ARROW_BACK, ICON_CHEVRON_DOWN } from "./icons.js";
import { MoreMenu } from "./menu.js";
import { postJson } from "./api.js";
import { sync } from "./sync.js";

/**
 * ChatTopBar: title capsule (menu + brand, or the conversation title at conversationTitleSolo)
 * and actions capsule (new chat + more).
 */
export function TopBar({ title, storedTitle, subtitle, conversationId, parentConversationId, drawerOpen, onToggleDrawer, menuButton, reduceMotion, onSignedOut, connected, onFork, onShare, pageBusy, onSystemPrompt, promptEnabled, search, generating, conversationActionsEnabled, onRequestRename, onRequestDelete }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [retainedMenu, setRetainedMenu] = useState(null);
  const activeMenu = useRef(null);
  const menuTarget = sync.interactionTarget();
  const conversationMenuEnabled = connected && !search && !!menuTarget && conversationActionsEnabled && retainedMenu?.conversationId === conversationId &&
    retainedMenu?.connectionId === menuTarget?.connectionId && retainedMenu?.seq === menuTarget?.seq;
  activeMenu.current = { request: retainedMenu, open: menuOpen, enabled: conversationMenuEnabled };
  const moreButton = useRef(null);
  const bar = useRef(null);
  const searchTransition = useRef({ active: !!search, animation: null, outgoing: null });
  const searchField = useRef(null);
  useLayoutEffect(() => {
    const node = bar.current, motion = searchTransition.current;
    const active = !!search;
    if (!node || motion.active === active) return;
    motion.animation?.cancel();
    motion.outgoing?.remove();
    const previous = motion.snapshot?.cloneNode(true) ?? node.cloneNode(true);
    previous.querySelectorAll("input").forEach(input => input.removeAttribute("id"));
    previous.inert = true;
    previous.setAttribute("aria-hidden", "true");
    previous.classList.add("top-bar-outgoing");
    node.parentElement.append(previous);
    motion.outgoing = previous;
    const enterMs = reduceMotion ? 360 : active ? 400 : 360;
    const exitMs = reduceMotion ? 300 : active ? 300 : 320;
    const fromScale = reduceMotion ? 1 : active ? .94 : .97;
    const toScale = reduceMotion ? 1 : active ? .97 : .94;
    previous.animate([{ opacity: 1, transform: "scale(1)" }, { opacity: 0, transform: `scale(${toScale})` }],
      { duration: exitMs, easing: "cubic-bezier(.4,0,.2,1)", fill: "forwards" });
    motion.animation = node.animate([{ opacity: 0, transform: `scale(${fromScale})` }, { opacity: 1, transform: "scale(1)" }],
      { duration: enterMs, easing: "cubic-bezier(.4,0,.2,1)" });
    motion.animation.onfinish = () => { previous.remove(); motion.outgoing = null; motion.animation = null; };
    motion.active = active;
  }, [!!search, reduceMotion]);
  useLayoutEffect(() => {
    const motion = searchTransition.current;
    motion.snapshot = bar.current.cloneNode(true);
  });
  useLayoutEffect(() => () => {
    searchTransition.current.animation?.cancel();
    searchTransition.current.outgoing?.remove();
  }, []);
  useLayoutEffect(() => { if (search) searchField.current?.focus(); }, [!!search]);
  const titleIdentity = conversationId == null ? "new-chat" : title ? JSON.stringify([conversationId, title]) : "brand";
  const titled = titleFrames => {
    if (!titleFrames.some(frame => frame.identity === titleIdentity)) {
      return [...titleFrames, { identity: titleIdentity, title, subtitle, initialAlpha: 0 }];
    }
    // The token figure arrives on its own, so the frame of the title that is showing takes it
    // without restarting the crossfade; every other frame keeps what it had while it fades out.
    return titleFrames.every(frame => frame.subtitle === subtitle) ? titleFrames
      : titleFrames.map(frame => frame.identity === titleIdentity ? { ...frame, subtitle } : frame);
  };
  const [titleFrames, setTitleFrames] = useState([{ identity: titleIdentity, title, subtitle, initialAlpha: 1 }]);
  const presentations = titled(titleFrames);
  const titleCanvas = useRef(null);
  const titleClip = useRef({ identity: null, target: 0, deadline: 0, animation: null, canvas: null });
  const titleFade = useRef({ identity: titleIdentity, animations: new Map() });
  useLayoutEffect(() => {
    const canvas = titleCanvas.current;
    const clip = titleClip.current;
    // The search bar replaces the whole title capsule, so the canvas is absent while
    // search owns the bar; geometry resumes when the capsule remounts.
    if (!canvas) return undefined;
    const measure = () => {
      const label = [...canvas.querySelectorAll("h1")].find((node) => node.dataset.titleIdentity === titleIdentity);
      if (!label) return;
      const target = Math.min(canvas.clientWidth, titleIdentity === "new-chat" ? 52 : 74 + Math.min(label.scrollWidth, 180));
      const initial = clip.identity === null || clip.canvas !== canvas;
      const changed = clip.identity !== titleIdentity;
      if (!initial && !changed && clip.target === target && (!reduceMotion || !clip.animation)) return;
      const now = performance.now();
      const from = initial ? target : parseFloat(getComputedStyle(canvas).getPropertyValue("--title-clip-width"));
      canvas.style.setProperty("--title-clip-width", `${from}px`);
      clip.animation?.cancel();
      clip.animation = null;
      if (changed) clip.deadline = now + 400;
      clip.identity = titleIdentity;
      clip.canvas = canvas;
      clip.target = target;
      if (initial || reduceMotion || now >= clip.deadline) {
        canvas.style.setProperty("--title-clip-width", `${target}px`);
        clip.deadline = 0;
        return;
      }
      // Identity changes restart; geometry alone rebases within the same original deadline.
      const animation = canvas.animate([
        { "--title-clip-width": `${from}px` }, { "--title-clip-width": `${target}px` },
      ], { duration: clip.deadline - now, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      clip.animation = animation;
      animation.onfinish = () => {
        if (clip.animation !== animation) return;
        canvas.style.setProperty("--title-clip-width", `${clip.target}px`);
        animation.cancel();
        clip.animation = null;
        clip.deadline = 0;
      };
    };
    measure();
    const geometry = new ResizeObserver(measure);
    [canvas, ...canvas.querySelectorAll("h1")].forEach((node) => geometry.observe(node, { box: "border-box" }));
    document.fonts.addEventListener("loadingdone", measure);
    return () => { geometry.disconnect(); document.fonts.removeEventListener("loadingdone", measure); };
  }, [titleIdentity, reduceMotion, titleFrames, !!search]);
  useLayoutEffect(() => {
    const fade = titleFade.current;
    if (fade.identity === titleIdentity) return;
    // The search bar owns the top bar while search is open; the pending identity is not
    // consumed until the capsule remounts and this effect re-runs.
    if (!titleCanvas.current) return;
    const labels = [...titleCanvas.current.querySelectorAll("h1")];
    for (const label of labels) {
      const alpha = getComputedStyle(label).opacity;
      fade.animations.get(label.dataset.titleIdentity)?.cancel();
      label.style.opacity = alpha;
    }
    fade.animations.clear();
    fade.identity = titleIdentity;
    setTitleFrames(presentations);
    for (const label of labels) {
      const identity = label.dataset.titleIdentity;
      const animation = label.animate([{ opacity: getComputedStyle(label).opacity },
        { opacity: identity === titleIdentity ? 1 : 0 }],
      { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      fade.animations.set(identity, animation);
      if (identity === titleIdentity) animation.onfinish = () => {
        if (fade.animations.get(identity) !== animation) return;
        labels.forEach((node) => { node.style.opacity = node === label ? "1" : "0"; });
        fade.animations.forEach((owned) => owned.cancel());
        fade.animations.clear();
        setTitleFrames([{ identity, title, subtitle, initialAlpha: 1 }]);
      };
    }
  }, [titleIdentity, !!search]);
  useLayoutEffect(() => () => {
    activeMenu.current.open = false;
    titleClip.current.animation?.cancel();
    titleFade.current.animations.forEach((animation) => animation.cancel());
  }, []);
  async function signOut() {
    closeMenu();
    await postJson("/api/logout", {}).catch(() => null);
    onSignedOut();
  }
  function closeMenu() {
    activeMenu.current.open = false;
    setMenuOpen(false);
  }
  function requestConversationAction(request, onRequest) {
    const current = activeMenu.current, target = sync.interactionTarget();
    if (!request || !current.open || !current.enabled || current.request !== request ||
        request.connectionId !== target?.connectionId || request.seq !== target?.seq ||
        request.conversationId !== target?.conversationId) return;
    closeMenu();
    onRequest(request);
  }
  return html`
    <header class="top-bar" ref=${bar}>
      ${search ? html`<div class="capsule conversation-search-bar" aria-busy=${search.searching}>
        <button class="bar-button" type="button" aria-label=${t.close} onClick=${() => sync.dismissConversationSearch()}>${icon(ICON_ARROW_BACK)}</button>
        <input ref=${searchField} type="search" value=${search.query} placeholder=${t.conversationSearchHint}
          aria-label=${t.conversationSearch} disabled=${!connected} onInput=${event => sync.conversationSearch(event.currentTarget.value)}
          onKeyDown=${event => { if (event.key === "Escape") { event.preventDefault(); sync.dismissConversationSearch(); } }} />
        <span class="conversation-search-count" role="status">${search.failed ? t.searchFailed : `${search.index < 0 ? 0 : search.index + 1}/${search.matches.length}`}</span>
        <button class="bar-button search-previous" type="button" aria-label=${t.searchPrevious} disabled=${search.searching || search.index <= 0}
          onClick=${() => sync.selectSearchMatch(search.index - 1, search.revision)}>${icon(ICON_CHEVRON_DOWN)}</button>
        <button class="bar-button" type="button" aria-label=${t.searchNext} disabled=${search.searching || search.index < 0 || search.index >= search.matches.length - 1}
          onClick=${() => sync.selectSearchMatch(search.index + 1, search.revision)}>${icon(ICON_CHEVRON_DOWN)}</button>
      </div>` : html`
      <div class="capsule title-capsule">
        <div class="title-canvas" ref=${titleCanvas}>
          <div class="title-content">
            <button class="bar-button" type="button" ref=${menuButton} aria-label=${parentConversationId ? t.backToParent : t.menu}
              aria-controls=${parentConversationId ? null : "drawer"} aria-expanded=${parentConversationId ? null : drawerOpen ? "true" : "false"} onClick=${onToggleDrawer}>
              ${icon(parentConversationId ? ICON_ARROW_BACK : ICON_MENU)}
            </button>
            <div class="title-labels">
              ${presentations.map((frame) => html`<h1 key=${frame.identity}
                class=${frame.title ? (frame.subtitle ? "conversation-bar-title has-subtitle" : "conversation-bar-title") : "brand-title"}
                data-title-identity=${frame.identity} aria-hidden=${frame.identity === titleIdentity && frame.identity !== "new-chat" ? null : "true"}
                style=${{ opacity: frame.initialAlpha }}><span class="title-text">${frame.identity === "new-chat" ? "" : frame.title || t.title}</span>${frame.subtitle &&
                  html`<span class="conversation-subtitle">${frame.subtitle}</span>`}</h1>`)}
            </div>
          </div>
        </div>
      </div>
      <div class="capsule actions-capsule">
        <button class="bar-button add" type="button" aria-label=${t.newChat} disabled=${!connected}
          onClick=${() => sync.open(null)}>
          ${icon(ICON_ADD)}
        </button>
        <div class="menu-anchor">
          <button class="bar-button" type="button" ref=${moreButton} aria-label=${t.options}
            aria-haspopup="menu" aria-expanded=${menuOpen ? "true" : "false"}
            onClick=${() => {
              if (menuOpen) { closeMenu(); return; }
              setRetainedMenu({ ...sync.interactionTarget(), conversationId, title: storedTitle ?? "", anchor: moreButton.current });
              setMenuOpen(true);
            }}>
            ${icon(ICON_MORE_VERT)}
          </button>
        </div>
      </div>`}
    </header>
    ${(menuOpen || retainedMenu) && html`<${MoreMenu} expanded=${menuOpen} reduceMotion=${reduceMotion}
      anchor=${moreButton} onSignOut=${signOut} onClose=${closeMenu}
      pageActionsEnabled=${connected && !!conversationId && !pageBusy && !generating}
      promptEnabled=${promptEnabled} onSystemPrompt=${() => { closeMenu(); onSystemPrompt(); }}
      onSearch=${() => { closeMenu(); sync.conversationSearch(""); }}
      onFork=${() => { closeMenu(); onFork(); }} onShare=${() => { closeMenu(); onShare(); }}
      conversationActionsEnabled=${menuOpen && conversationMenuEnabled}
      onRename=${() => requestConversationAction(retainedMenu, onRequestRename)}
      onDelete=${() => requestConversationAction(retainedMenu, onRequestDelete)}
      onExited=${(ownedFocus) => {
        if (activeMenu.current.request !== retainedMenu || activeMenu.current.open) return;
        setRetainedMenu(null);
        if (ownedFocus) moreButton.current?.focus();
      }} />`}`;
}
