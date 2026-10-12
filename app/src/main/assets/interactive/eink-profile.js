/* Agora-owned adaptive presentation. Vendor assets and generated code are not rewritten. */
(function () {
  "use strict";
  const selector = 'html[data-agora-display="eink"]';
  const css = `
${selector} {
 --color-background-primary:#fff;--color-background-secondary:#fff;--color-background-tertiary:#fff;
 --color-background-info:#fff;--color-background-danger:#fff;--color-background-success:#fff;--color-background-warning:#fff;
 --color-text-primary:#000;--color-text-secondary:#000;--color-text-tertiary:#000;
 --color-text-info:#000;--color-text-danger:#000;--color-text-success:#000;--color-text-warning:#000;
 --color-border-primary:#000;--color-border-secondary:#000;--color-border-tertiary:#000;
 --color-border-info:#000;--color-border-danger:#000;--color-border-success:#000;--color-border-warning:#000;
 color-scheme:light;background-color:#fff!important;color:#000!important;
}
${selector} *,${selector} *::before,${selector} *::after {
 animation:none!important;transition:none!important;box-shadow:none!important;text-shadow:none!important;
 backdrop-filter:none!important;scroll-behavior:auto!important;
}
${selector} body {
 background-color:#fff!important;color:#000!important;font-family:var(--font-sans)!important;
 font-size:calc(16px * var(--agora-font-scale,1));line-height:1.5;
}
${selector} :is(section,article,main,aside,header,footer,div,table,th,td,button,input,select,textarea) {
 background-color:#fff!important;border-color:#000!important;
}
${selector} :is(p,span,label,legend,h1,h2,h3,h4,h5,h6,output,a,button,input,select,textarea,th,td) {
 color:#000!important;
}
${selector} :is(button,input:not([type=checkbox]):not([type=radio]),select,textarea,[role=button],[role=slider]) {
 min-height:48px;min-width:48px;font:inherit;opacity:1!important;border:1px solid #000;
}
${selector} input[type=range] {appearance:none;-webkit-appearance:none;border:0!important;min-width:160px;padding:0;accent-color:#000}
${selector} input[type=range]::-webkit-slider-runnable-track {height:2px;background:#000;border:0}
${selector} input[type=range]::-webkit-slider-thumb {-webkit-appearance:none;width:28px;height:28px;margin-top:-13px;border:2px solid #000;border-radius:50%;background:#fff}
${selector} :is(input[type=checkbox],input[type=radio]) {min-width:24px;min-height:24px;accent-color:#000}
${selector} :is(button,input,select,textarea,[role=button]):focus-visible {outline:2px solid #000;outline-offset:2px}
${selector} :is(button,[role=button]):active,${selector} [aria-pressed=true],${selector} [aria-selected=true] {
 background:#000!important;color:#fff!important;
}
${selector} :is([aria-pressed=true],[aria-selected=true]) * {color:#fff!important}
${selector} :is(:disabled,[aria-disabled=true]) {opacity:1!important;border:2px dashed #000!important}
${selector} [aria-invalid=true],${selector} [role=alert] {border:2px solid #000!important;padding:8px}
${selector} output {font-variant-numeric:tabular-nums}
${selector} a {text-decoration:underline}
${selector} th,${selector} td {border-bottom:1px solid #000;padding:8px;text-align:start}
`;
  // Serialized into the opaque-origin frame, with no parent/native references in its closure.
  function install(enabled, fontScale) {
    const root = document.documentElement;
    function apply(mode, scale) {
      const next = mode ? 'eink' : 'default';
      const size = String(Number.isFinite(scale) ? Math.max(1, Math.min(3, scale)) : 1);
      if (root.dataset.agoraDisplay === next && root.style.getPropertyValue('--agora-font-scale') === size) return;
      const x = window.scrollX, y = window.scrollY;
      root.style.setProperty('--agora-font-scale', size);
      root.dataset.agoraDisplay = next;
      // Standard bubbling reaches both document and window listeners exactly once.
      document.dispatchEvent(new CustomEvent('agora:displaychange', {detail:{eInkMode:!!mode},bubbles:true}));
      window.scrollTo({left:x,top:y,behavior:'instant'});
    }
    function state() {
      const scroller = document.scrollingElement;
      return {canPrevious:scroller.scrollTop > 0, canNext:scroller.scrollTop + window.innerHeight < scroller.scrollHeight - 1};
    }
    function page(direction) {
      const line = parseFloat(getComputedStyle(document.body).lineHeight) || 24;
      const height = window.innerHeight;
      const step = height - Math.min(height / 2, line * 3);
      window.scrollBy({top:direction === 'previous' ? -step : step,behavior:'instant'});
      return state();
    }
    // Presentation helpers are not security capabilities; the native port remains outside this frame.
    window.AgoraEinkPresentation = {apply, page, state};
    apply(enabled, fontScale);
  }
  window.AgoraEinkProfile = {css, install};
})();
