import { Fragment } from "./vendor/preact.mjs";
import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";
import { DetailSheet } from "./detail-sheet.js";
import { ToolItem, retainToolItems } from "./tool-item.js";
import { icon, ICON_ACCOUNT_TREE, ICON_ERROR, ICON_PAUSE_CIRCLE_OUTLINE } from "./icons.js";
import { Spinner } from "./material/progress.js";

/** Retained layers continue their visible opacity when a change interrupts a fade. */
function useCrossfadeLayers(host, value) {
  const [retained, setRetained] = useState([{ id: 0, value }]);
  const last = retained[retained.length - 1];
  const layers = last.value === value ? retained : [...retained, { id: last.id + 1, value }];
  useLayoutEffect(() => {
    const nodes = [...host.current.children];
    if (layers.length === 1) { nodes[0].style.opacity = "1"; return; }
    setRetained(layers);
    let disposed = false;
    const animations = nodes.map((node, index) => node.animate([
      { opacity: getComputedStyle(node).opacity }, { opacity: index === nodes.length - 1 ? 1 : 0 },
    ], { duration: 250, easing: "cubic-bezier(.4,0,.2,1)", fill: "forwards" }));
    animations[animations.length - 1].onfinish = () => {
      if (disposed) return;
      nodes[nodes.length - 1].style.opacity = "1";
      setRetained([layers[layers.length - 1]]);
    };
    return () => {
      disposed = true;
      nodes.forEach((node, index) => {
        if (node.isConnected) node.style.opacity = getComputedStyle(node).opacity;
        animations[index].cancel();
      });
    };
  }, [value]);
  return layers;
}

/** The fixed-size status slot crossfades icons and progress without spatial motion. */
export function SubagentStatus({ status }) {
  const host = useRef(null);
  const layers = useCrossfadeLayers(host, status);
  return html`<span class="subagent-status" ref=${host} aria-hidden="true">
    ${layers.map(layer => html`<span key=${layer.id}>
      ${layer.value === "running" ? html`<${Spinner} size=${18} stroke=${2} />`
        : icon(layer.value === "failed" ? ICON_ERROR
          : layer.value === "stopped" ? ICON_PAUSE_CIRCLE_OUTLINE : ICON_ACCOUNT_TREE)}
    </span>`)}
  </span>`;
}

/** Title text crossfades in place like the status slot; the shared row owns width changes. */
export function SubagentTitle({ title }) {
  const host = useRef(null);
  const layers = useCrossfadeLayers(host, title);
  return html`<strong class="subagent-title" ref=${host}>
    ${layers.map(layer => html`<span key=${layer.id}>${layer.value}</span>`)}
  </strong>`;
}

function statusLabel(status) {
  return t.subagentStatuses[status] || t.subagentStatuses.idle;
}

/** Family identity preserves capsules; ordinary selected-target state still owns navigation. */
export function SubagentsBar({ state, enter = true }) {
  const grouped = state.display?.subagentCapsuleDisplayMode === "grouped";
  const family = state.parentConversationId || state.openId;
  const owner = `${state.connectionId || ""}:${family || ""}`;
  const agents = state.connected && state.openId ? state.subagents || [] : [];
  const desired = useRef(null);
  desired.current = agents.length
    ? { owner, agents, latest: agents, conversationId: state.openId, grouped } : null;
  const [shown, setShown] = useState(desired.current);
  const [opened, setOpened] = useState(false);
  const [pending, setPending] = useState(null);
  const sheetOwner = useRef(owner), button = useRef(null);
  const reduced = !!state.display?.reduceMotion;
  const active = !!agents.length && shown?.owner === owner && shown.grouped === grouped;
  const selected = active ? state.openId : shown?.conversationId;
  const sheetValid = active && grouped && agents.length >= 2;
  useLayoutEffect(() => {
    if (sheetOwner.current !== owner || (opened && !sheetValid)) {
      setOpened(false);
      setPending(null);
    }
    sheetOwner.current = owner;
    if (active && shown.latest !== agents) {
      setShown({ ...desired.current, agents: grouped ? agents : retainToolItems(shown.agents, agents) });
    } else if (!shown && desired.current) setShown(desired.current);
  }, [owner, active, agents, shown, grouped, opened, sheetValid]);
  function exited(id) {
    setShown(current => {
      if (current?.owner !== shown.owner || current.grouped) return current;
      if (desired.current?.owner === current.owner && desired.current.grouped === current.grouped &&
          desired.current.agents.some(agent => agent.id === id)) return current;
      const remaining = current.agents.filter(agent => agent.id !== id);
      return remaining.length ? { ...current, agents: remaining } : desired.current;
    });
  }
  function groupExited() {
    setShown(current => {
      if (current?.owner !== shown.owner || !current.grouped) return current;
      return desired.current;
    });
  }
  if (!shown) return null;
  if (shown.grouped) {
    const list = shown.agents;
    const single = list.length === 1 ? list[0] : null;
    const runningCount = list.filter(agent => agent.status === "running").length;
    const title = single ? single.title
      : runningCount ? t.subagentsRunningCount(runningCount) : t.subagentsCount(list.length);
    const status = single ? single.status : runningCount ? "running" : "idle";
    return html`<${Fragment}>
      <${ToolItem} key=${`${shown.owner}:subagents`} toolKey=${`subagents:${shown.owner}`}
        identity=${shown.owner} active=${active} reduced=${reduced} enter=${enter} retain=${true}
        onExited=${groupExited}>
        <button type="button" class="subagent-capsule" ref=${button} disabled=${!active}
          aria-label=${`${t.sourceSubagent}, ${title}, ${statusLabel(status)}`}
          aria-current=${list.some(agent => agent.id === selected) ? "page" : null}
          aria-expanded=${single ? null : opened && sheetValid}
          onClick=${() => {
            if (!active) return;
            if (single) sync.open(single.id);
            else if (agents.length >= 2) setOpened(true);
          }}>
          <${SubagentStatus} status=${status} />
          <span class="subagent-text"><small>${t.sourceSubagent}</small><${SubagentTitle} title=${title} /></span>
        </button>
      </${ToolItem}>
      ${opened && sheetValid && html`<${DetailSheet} title=${t.subagentsCount(agents.length)}
        display=${state.display} focusReturn=${button} closing=${pending != null}
        onClose=${() => {
          const chosen = pending;
          setOpened(false);
          setPending(null);
          if (chosen && agents.some(agent => agent.id === chosen)) sync.open(chosen);
        }}>
        <div class="subagent-choices">
          ${agents.map(agent => html`<button type="button" class="subagent-choice" key=${agent.id}
            disabled=${pending != null}
            aria-current=${agent.id === selected ? "page" : null}
            aria-label=${`${agent.title}, ${statusLabel(agent.status)}`}
            onClick=${() => { if (pending == null) setPending(agent.id); }}>
            <${SubagentStatus} status=${agent.status} />
            <span class="subagent-choice-text"><strong>${agent.title}</strong><small>${statusLabel(agent.status)}</small></span>
          </button>`)}
        </div>
      </${DetailSheet}>`}
    </${Fragment}>`;
  }
  return shown.agents.map(agent => {
    const enabled = active && agents.some(item => item.id === agent.id);
    return html`<${ToolItem} key=${`${shown.owner}:${agent.id}`} toolKey=${`subagent:${agent.id}`}
      identity=${shown.owner} active=${enabled} reduced=${reduced} enter=${enter} retain=${true}
      onExited=${() => exited(agent.id)}>
      <button type="button" class="subagent-capsule" disabled=${!enabled}
        aria-label=${`${t.sourceSubagent}, ${agent.title}, ${statusLabel(agent.status)}`}
        aria-current=${agent.id === selected ? "page" : null}
        onClick=${() => { if (enabled) sync.open(agent.id); }}>
        <${SubagentStatus} status=${agent.status} />
        <span class="subagent-text"><small>${t.sourceSubagent}</small><${SubagentTitle} title=${agent.title} /></span>
      </button>
    </${ToolItem}>`;
  });
}
