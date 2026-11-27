// The per-session tool catalog sheet. The phone's SessionToolsDialog is the reference: the same
// available/unavailable split, the same row geometry, and a switch that applies to the session.
import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { DetailSheet } from "./detail-sheet.js";
import { t } from "./i18n.js";
import {
  icon, ICON_ACCOUNT_TREE, ICON_ADD_PHOTO_ALTERNATE, ICON_CHECKLIST, ICON_DESCRIPTION, ICON_EXPAND_MORE,
  ICON_EXTENSION, ICON_LANGUAGE, ICON_MCP, ICON_MOBILE_HAND, ICON_QUESTION_ANSWER, ICON_REPEAT, ICON_SEARCH,
  ICON_TERMINAL,
} from "./icons.js";
import { sync } from "./sync.js";

// The icon of each group's settings page, matching SessionToolsDialog.groupIcon.
const GROUP_ICONS = {
  memory: ICON_DESCRIPTION,
  skills: ICON_EXTENSION,
  past_conversations: ICON_SEARCH,
  web: ICON_LANGUAGE,
  terminal: ICON_TERMINAL,
  image: ICON_ADD_PHOTO_ALTERNATE,
  todo: ICON_CHECKLIST,
  phone: ICON_MOBILE_HAND,
  subagent: ICON_ACCOUNT_TREE,
  automation: ICON_REPEAT,
  ask_user: ICON_QUESTION_ANSWER,
  mcp: ICON_EXTENSION,
};

const groupLabel = group => t.sessionToolsGroups[group.id] ?? group.id;
const groupIcon = group => group.id === "mcp" ? icon(ICON_MCP, "0 0 24 24", "evenodd")
  : icon(GROUP_ICONS[group.id] ?? ICON_EXTENSION);
const groupTools = group => group.tools ?? [];

/** The reason replaces the switch on a group this request cannot carry. */
function unavailableReason(reason) {
  if (reason === "switch_off") return t.sessionToolsOffSwitch;
  if (reason === "low_context") return t.sessionToolsOffLowContext;
  return t.sessionToolsOffEmpty;
}

/** Compose animateContentSize(tween(400)): retain the current height across a two-line/full-text change. */
function ToolDescription({ text, expanded, visible, reduceMotion }) {
  const node = useRef(null), height = useRef(null), animation = useRef(null);
  useLayoutEffect(() => {
    const element = node.current;
    if (!element) return;
    const previous = animation.current;
    const from = previous ? getComputedStyle(element).height
      : height.current == null ? null : `${height.current}px`;
    previous?.cancel();
    animation.current = null;
    // A collapsed group reports zero height. Becoming visible only records the real current height.
    if (!visible) {
      height.current = null;
      return;
    }
    const target = element.getBoundingClientRect().height;
    height.current = target;
    if (from == null || reduceMotion || Math.abs(parseFloat(from) - target) <= .5) return;
    const next = element.animate([{ height: from }, { height: `${target}px` }], {
      duration: 400, easing: "cubic-bezier(0, 0, .2, 1)", fill: "both",
    });
    animation.current = next;
    next.onfinish = () => {
      if (animation.current !== next) return;
      animation.current = null;
      next.cancel(); // The CSS auto height is identical once the animation reaches its endpoint.
    };
  }, [text, expanded, visible, reduceMotion]);
  useLayoutEffect(() => () => animation.current?.cancel(), []);
  return html`<span class="session-tools-tool-description" ref=${node} data-expanded=${expanded}>${text}</span>`;
}

/** One carried group: a disclosure surface with a sibling switch, never a switch inside a button. */
function Group({ group, groupEnabled, expanded, isToolExpanded, reduceMotion, onToggle, onExpand, onToolClick }) {
  const label = groupLabel(group);
  const tools = groupTools(group);
  return html`<div class="session-tools-group" data-expanded=${expanded}>
    <div class="session-tools-group-row">
      <button class="session-tools-group-toggle" type="button" aria-expanded=${expanded}
        aria-label=${label} onClick=${onExpand}></button>
      <span class="session-tools-icon" aria-hidden="true">${groupIcon(group)}</span>
      <div class="session-tools-group-text">
        <div class="session-tools-group-title">${label}</div>
        <div class="session-tools-group-meta">${t.sessionToolsGroupMeta(tools.length, group.tokens ?? 0)}</div>
      </div>
      <button class="tool-switch session-tools-switch" type="button" role="switch" aria-label=${label}
        aria-checked=${groupEnabled} data-checked=${groupEnabled}
        onClick=${() => onToggle(!groupEnabled)}></button>
      <span class="session-tools-chevron" aria-hidden="true">${icon(ICON_EXPAND_MORE)}</span>
    </div>
    <div class="session-tools-tools" inert=${!expanded} aria-hidden=${expanded ? null : "true"}>
      <div class="session-tools-tools-inner">
        ${tools.map(tool => html`<div class="session-tools-tool-row" key=${tool.name}>
          <button class="session-tools-tool" type="button" aria-expanded=${isToolExpanded(tool.name)}
            onClick=${() => onToolClick(tool.name)}>
            <span class="session-tools-tool-head">
              <span class="session-tools-tool-name">${tool.name}</span>
              <span class="session-tools-tool-tokens">${t.sessionToolsTokens(tool.tokens)}</span>
            </span>
            <${ToolDescription} text=${tool.description} expanded=${isToolExpanded(tool.name)}
              visible=${expanded} reduceMotion=${reduceMotion} />
          </button>
        </div>`)}
      </div>
    </div>
  </div>`;
}

/** A group this request does not carry: dimmed, no switch, no disclosure. */
function Unavailable({ group }) {
  return html`<div class="session-tools-group unavailable">
    <div class="session-tools-group-row">
      <span class="session-tools-icon" aria-hidden="true">${groupIcon(group)}</span>
      <div class="session-tools-group-text">
        <div class="session-tools-group-title">${groupLabel(group)}</div>
        <div class="session-tools-group-meta">${unavailableReason(group.unavailable)}</div>
      </div>
    </div>
  </div>`;
}

export function SessionTools({ state, focusReturn, onClose }) {
  const catalog = state.sessionTools;
  const [expandedGroups, setExpandedGroups] = useState(() => new Set());
  const [expandedTools, setExpandedTools] = useState(() => new Set());
  const groups = catalog?.failed ? [] : catalog?.groups ?? [];
  const carried = groups.filter(group => group.unavailable == null);
  const leftOut = groups.filter(group => group.unavailable != null);
  const enabled = new Set(catalog?.enabledGroups ?? []);
  const selected = carried.filter(group => enabled.has(group.id));
  // SessionToolCatalog.tokensOf: the selected definitions plus one tool-use preamble.
  const tokens = selected.reduce((sum, group) => sum + (group.tokens ?? 0), 0) +
    (selected.some(group => groupTools(group).length > 0) ? catalog?.toolSetTokens ?? 0 : 0);
  const totalTools = groups.reduce((sum, group) => sum + groupTools(group).length, 0);
  const selectedTools = selected.reduce((sum, group) => sum + groupTools(group).length, 0);
  const trailing = catalog && !catalog.failed ? t.sessionToolsHeader(selectedTools, totalTools, tokens) : null;
  function toggleExpanded(id) {
    setExpandedGroups(current => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }
  function toggleTool(name) {
    setExpandedTools(current => {
      const next = new Set(current);
      if (next.has(name)) next.delete(name); else next.add(name);
      return next;
    });
  }
  if (!catalog) return null;
  return html`<${DetailSheet} title=${t.tools} display=${state.display} focusReturn=${focusReturn}
    headerTrailing=${trailing} onClose=${onClose}>
    <div class="session-tools-body" data-failed=${catalog.failed ? "true" : null}>
      ${catalog.failed ? html`<div class="session-tools-message" role="status">${t.sessionToolsFailed}</div>`
        : html`
          ${carried.map(group => html`<${Group} key=${group.id} group=${group} groupEnabled=${enabled.has(group.id)}
            expanded=${expandedGroups.has(group.id)} isToolExpanded=${name => expandedTools.has(name)}
            reduceMotion=${!!state.display?.reduceMotion}
            onToggle=${value => sync.toggleSessionToolGroup(group.id, value)}
            onExpand=${() => toggleExpanded(group.id)} onToolClick=${toggleTool} />`)}
          ${leftOut.length > 0 && html`
            ${carried.length > 0 && html`<div class="session-tools-divider" role="separator"></div>`}
            ${leftOut.map(group => html`<${Unavailable} key=${group.id} group=${group} />`)}`}
        `}
    </div>
  </${DetailSheet}>`;
}
