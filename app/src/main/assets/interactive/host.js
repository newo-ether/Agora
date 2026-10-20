/* Android host adaptation of OpenIntelligentUI's final-frame and validated host bridge.
 * The native MessagePort is transferred only to this trusted main document. Generated
 * content runs in Websandbox's opaque-origin iframe and receives only the named API.
 */
(function () {
  "use strict";
  const MAX_PROMPT = 4000;
  let port, sandbox, nextId = 0, sending = false;
  const pending = new Map();
  let eligible = false, frameReady = false, display = {eInkMode:false,fontScale:1};
  let presentationQueue = Promise.resolve();
  function enqueuePresentation(task) {
    presentationQueue = presentationQueue.then(task).catch(() => {
      if (port) port.postMessage(JSON.stringify({method:"error",value:"Presentation update failed"}));
    });
  }
  async function reportViewport() {
    if (!eligible || !frameReady) return;
    await sandbox.run('Websandbox.connection.remote.reportPageState(window.AgoraEinkPresentation.state())');
  }
  async function applyDisplay() {
    if (!eligible || !frameReady) return;
    await sandbox.run('window.AgoraEinkPresentation.apply('+JSON.stringify(display.eInkMode)+','+JSON.stringify(display.fontScale)+')');
    await reportViewport();
  }
  const LIBRARY_ORIGINS = ["https://cdnjs.cloudflare.com", "https://esm.sh", "https://cdn.jsdelivr.net", "https://unpkg.com"];
  const IMAGE_ORIGINS = ["https://basemap.nationalmap.gov", "https://upload.wikimedia.org", "https://thumb.wikimedia.org"];

  function request(method, value) {
    const id = ++nextId;
    return new Promise((resolve, reject) => {
      pending.set(id, {resolve, reject});
      port.postMessage(JSON.stringify({id, method, value}));
    });
  }
  function userGesture() {
    if (!navigator.userActivation || !navigator.userActivation.isActive) {
      throw new Error("Use a button to perform this action; update Android System WebView if it is unavailable.");
    }
  }
  async function sendPrompt(args) {
    userGesture();
    if (!args || typeof args.text !== "string" || !args.text.trim() || args.text.trim().length > MAX_PROMPT) {
      throw new Error("Follow-up must contain 1–4000 characters.");
    }
    if (args.sourceFiles !== undefined && (!Array.isArray(args.sourceFiles) || args.sourceFiles.length > 16 ||
        args.sourceFiles.some(name => typeof name !== "string" || name.length > 1000 ||
          name.startsWith('/') || /[\\\x00-\x1f\x7f]/.test(name) || name.split('/').some(part => !part.trim() || part === '.' || part === '..')))) {
      throw new Error("Choose at most 16 local files.");
    }
    if (sending) throw new Error("A follow-up is already pending.");
    sending = true;
    try { return await request("sendPrompt", args.sourceFiles ? {text: args.text.trim(), sourceFiles: args.sourceFiles} : args.text.trim()); }
    finally { sending = false; }
  }
  async function openLink(args) {
    userGesture();
    const url = args && new URL(args.url);
    if (!url || url.protocol !== "https:" || url.username || url.password) {
      throw new Error("Only HTTPS links are supported.");
    }
    return request("openLink", url.href);
  }
  function buildFrame(answer) {
    // CSP precedes generated markup. Websandbox additionally supplies sandbox=allow-scripts
    // without allow-same-origin, so content cannot reach the trusted parent or native port.
    const csp = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' " + LIBRARY_ORIGINS.join(" ") +
      "; style-src 'unsafe-inline'; img-src data: blob: " + IMAGE_ORIGINS.join(" ") +
      "; font-src data:; connect-src " + LIBRARY_ORIGINS.join(" ") +
      "; base-uri 'none'; form-action 'none'; frame-src 'none'; worker-src 'none'";
    const imports = JSON.stringify(window.OpenIntelligentDesign.importmap).replace(/</g, "\\u003c");
    return '<html'+(eligible ? ' data-agora-display="'+(display.eInkMode?'eink':'default')+'"' : '')+'><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
      '<meta http-equiv="Content-Security-Policy" content="' + csp + '">' +
      '<script type="importmap">' + imports + '</script>' +
      '<style>' + window.OpenIntelligentDesign.css + '\n' + answer.css +
      (eligible ? '\n'+window.AgoraEinkProfile.css : '') + '\ninput[type=range],[role=slider],[data-agora-drag-control]{touch-action:none!important}' + '</style></head><body>' + answer.html + '</body></html>';
  }
  async function start(rawAnswer, inline) {
    // Kotlin omits default/empty optional fields when persisting an artifact.
    const answer = {css:"", jsFunctions:"", jsExpressions:[], ...rawAnswer};
    eligible = answer.eInkAware === true;
    if (!HTMLScriptElement.supports || !HTMLScriptElement.supports("importmap")) {
      throw new Error("This viewer requires an updated Android System WebView with import-map support.");
    }
    const module = window.Websandbox.default || window.Websandbox;
    sandbox = module.create({sendPrompt, openLink,
      listReferenceFiles: () => request("listReferenceFiles", null),
      listDirectory: args => request("listDirectory", args),
      readTextFile: args => request("readTextFile", args),
      readFile: args => request("readFile", args),
      reportViewport: () => { enqueuePresentation(reportViewport); },
      reportPageState: state => {
        if (!eligible || !frameReady || !state || typeof state.canPrevious !== "boolean" || typeof state.canNext !== "boolean") return;
        port.postMessage(JSON.stringify({method:"pageState",value:{canPrevious:state.canPrevious,canNext:state.canNext}}));
      },
      reportScrollBounds: state => {
        if (!state || typeof state.canPrevious !== "boolean" || typeof state.canNext !== "boolean" || typeof state.activeTouch !== "boolean") return;
        port.postMessage(JSON.stringify({method:"scrollBounds",value:{canPrevious:state.canPrevious,canNext:state.canNext,activeTouch:state.activeTouch}}));
      },
      claimControlGesture: () => { port.postMessage(JSON.stringify({method:"controlTouch"})); },
      reportHeight: ({height}) => {
        if (inline && typeof height === "number" && Number.isFinite(height) && height > 0) {
          port.postMessage(JSON.stringify({method:"height", value:Math.ceil(height)}));
        }
      }, reportError: () => { port.postMessage(JSON.stringify({method:"error", value:"Generated interface failed"})); return {ok:true}; }}, {
      frameContainer: document.getElementById("frame"), frameContent: buildFrame(answer), allowAdditionalAttributes: "",
    });
    await sandbox.promise;
    await sandbox.run('('+window.AgoraScrollProfile.install.toString()+')()');
    if (eligible) {
      await sandbox.run('('+window.AgoraEinkProfile.install.toString()+')('+JSON.stringify(display.eInkMode)+','+JSON.stringify(display.fontScale)+')');
      await sandbox.run('window.addEventListener("scroll",()=>Websandbox.connection.remote.reportViewport());'+
        'window.addEventListener("resize",()=>Websandbox.connection.remote.reportViewport());'+
        'new ResizeObserver(()=>Websandbox.connection.remote.reportViewport()).observe(document.body);');
    }
    await sandbox.run('window.addEventListener("error",()=>Websandbox.connection.remote.reportError());' +
      'window.addEventListener("unhandledrejection",()=>Websandbox.connection.remote.reportError());');
    await sandbox.run(`
      document.addEventListener("pointerdown",event=>{
        if(event.isTrusted && event.target instanceof Element &&
           event.target.closest('input[type="range"],[role="slider"],[data-agora-drag-control]')){
          Websandbox.connection.remote.claimControlGesture();
        }
      },true);
    `);
    if (inline) await sandbox.run(`
      (function(){
        let queued=false, last=0;
        function measure(){
          queued=false;
          const body=document.body, style=getComputedStyle(body);
          const height=Math.ceil(body.scrollHeight+(parseFloat(style.marginTop)||0)+(parseFloat(style.marginBottom)||0));
          if(height>0&&height!==last){last=height;Websandbox.connection.remote.reportHeight({height});}
        }
        function schedule(){if(!queued){queued=true;requestAnimationFrame(measure);}}
        new ResizeObserver(schedule).observe(document.body);
        window.addEventListener("load",schedule);window.addEventListener("resize",schedule);schedule();
      })();
    `);
    if (answer.jsFunctions) await sandbox.run(answer.jsFunctions);
    for (const expression of answer.jsExpressions) await sandbox.run(expression);
    frameReady = true;
    await applyDisplay();
    port.postMessage(JSON.stringify({method:"ready"}));
  }
  // Native postWebMessage transfers a private port; iframe-origin messages have no such port
  // and are never used for bootstrap. Remove the bootstrap listener immediately after binding.
  function connect(event) {
    if (port || event.ports.length !== 1 || (event.source && event.source !== window)) return;
    port = event.ports[0];
    window.removeEventListener("message", connect);
    port.onmessage = event => {
      const response = JSON.parse(event.data);
      if (response.type === "display") {
        if (typeof response.eInkMode !== "boolean") return;
        display = {eInkMode:response.eInkMode,fontScale:Number.isFinite(response.fontScale)?Math.max(1,Math.min(3,response.fontScale)):1};
        enqueuePresentation(applyDisplay); return;
      }
      if (response.type === "page") {
        if (!eligible || !frameReady || !display.eInkMode || !["previous","next"].includes(response.direction)) return;
        enqueuePresentation(async () => {
          if (!display.eInkMode) return;
          await sandbox.run('window.AgoraEinkPresentation.page('+JSON.stringify(response.direction)+')');
          await reportViewport();
        }); return;
      }
      const entry = pending.get(response.id);
      if (!entry) return;
      pending.delete(response.id);
      if (response.cancelled) entry.resolve({ok:false,cancelled:true});
      else if (response.ok) entry.resolve(response.result === undefined ? {ok:true} : response.result); else entry.reject(new Error(response.error || "Action unavailable"));
    };
    const payload=JSON.parse(event.data);
    display = {eInkMode:payload.eInkMode===true,fontScale:Number.isFinite(payload.fontScale)?Math.max(1,Math.min(3,payload.fontScale)):1};
    start(payload.answer || payload, payload.inline===true).catch(error => {
      port.postMessage(JSON.stringify({method:"error", value:String(error.message)}));
    });
  }
  window.addEventListener("message", connect);
  window.addEventListener("pagehide", () => { frameReady=false;eligible=false; if (sandbox) sandbox.destroy(); if (port) port.close(); });
})();
