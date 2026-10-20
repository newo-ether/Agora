/* Scroll geometry only; no generated interface receives the native message port. */
(function () {
  "use strict";
  function install() {
    let activeTarget = null, queued = false, lastState = null;
    function report(force = false) {
      queued = false;
      let previous = false, next = false;
      const root = document.scrollingElement;
      const seen = new Set();
      function include(element) {
        if (!element || seen.has(element)) return;
        seen.add(element);
        const overflow = getComputedStyle(element).overflowY;
        if (element !== root && !['auto','scroll','overlay'].includes(overflow)) return;
        if (element.scrollHeight <= element.clientHeight + 1) return;
        previous ||= element.scrollTop > 1;
        next ||= element.scrollTop + element.clientHeight < element.scrollHeight - 1;
      }
      for (let element = activeTarget; element; element = element.parentElement) include(element);
      include(root);
      const state = {canPrevious:previous,canNext:next,activeTouch:!!activeTarget};
      const key = JSON.stringify(state);
      if (force || key !== lastState) {
        lastState = key;
        Websandbox.connection.remote.reportScrollBounds(state);
      }
    }
    function schedule() { if (!queued) { queued = true; requestAnimationFrame(()=>report()); } }
    document.addEventListener('pointerdown', event => {
      if (!event.isTrusted) return;
      activeTarget = event.target instanceof Element ? event.target : null;
      report(true);
    }, true);
    function release() { activeTarget = null; schedule(); }
    document.addEventListener('pointerup', release, true);
    // Chromium cancels pointer events when it starts touch panning; the finger is still down.
    document.addEventListener('pointercancel', event => { if (event.pointerType !== 'touch') release(); }, true);
    document.addEventListener('touchend', event => { if (!event.touches.length) release(); }, {capture:true,passive:true});
    document.addEventListener('touchcancel', release, {capture:true,passive:true});
    document.addEventListener('scroll', schedule, true);
    window.addEventListener('resize', schedule);
    new ResizeObserver(schedule).observe(document.body);
    report();
  }
  window.AgoraScrollProfile = {install};
})();
