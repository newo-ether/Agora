package com.newoether.agora.ui.chat.message

/** Trusted, network-free capability check. No generated HTML is served before it passes. */
internal object HtmlPreviewProbe {
    const val COMPLETE_TITLE = "agora-preview-probe-complete"
    const val RESULT_SCRIPT = "window.agoraPreviewProbeResult || ''"
    const val ACCEPTED_RESULT = "\"1,1:0,0\""
    const val POLICY = "default-src 'none'; script-src 'unsafe-inline'; connect-src 'none'; " +
        "worker-src 'none'; base-uri 'none'; form-action 'none'; sandbox allow-scripts"

    fun wrapper(origin: String): String = """
        <!doctype html><html><head><meta charset="utf-8"></head><body>
        <iframe id="control" sandbox="allow-scripts" src="$origin/probe-control.html"></iframe>
        <iframe id="blocked" sandbox="allow-scripts" src="$origin/probe-blocked.html"></iframe>
        <script>
        (() => {
          const results = {};
          addEventListener('message', event => {
            if (event.origin !== 'null') return;
            const id = ['control', 'blocked'].find(id => document.getElementById(id).contentWindow === event.source);
            if (!id || typeof event.data !== 'string') return;
            results[id] = event.data;
            if (results.control !== undefined && results.blocked !== undefined) {
              window.agoraPreviewProbeResult = results.control + ':' + results.blocked;
              document.title = '$COMPLETE_TITLE';
            }
          });
        })();
        </script></body></html>
    """.trimIndent()

    fun frame(origin: String): String = """
        <!doctype html><html><head><meta charset="utf-8"></head><body><script>
        (() => {
          let pc, result = 'failed';
          try {
            const config = {iceCandidatePoolSize:0, iceServers:[{urls:'stun:127.0.0.1:9'}]};
            pc = new RTCPeerConnection(config);
            const initial = pc.getConfiguration().iceServers.length;
            pc.setConfiguration(config);
            result = initial + ',' + pc.getConfiguration().iceServers.length;
          } catch (_) {} finally { if (pc) pc.close(); }
          parent.postMessage(result, '$origin');
        })();
        </script></body></html>
    """.trimIndent()
}
