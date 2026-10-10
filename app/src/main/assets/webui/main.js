// Agora WebUI entry: the session check, then sign-in or the read-only chat mirror.
import { render } from "./vendor/preact.mjs";
import { useEffect, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { sessionSignedIn } from "./api.js";
import { SignIn } from "./signin.js";
import { Shell } from "./shell.js";
import { installTouchFeedback } from "./material/ripple.js";

function App() {
  // null while the session check is in flight, so neither screen flashes.
  const [signedIn, setSignedIn] = useState(null);
  useEffect(() => {
    sessionSignedIn().then(setSignedIn).catch(() => setSignedIn(false));
  }, []);
  if (signedIn === null) return null;
  return signedIn
    ? html`<${Shell} onSignedOut=${() => setSignedIn(false)} />`
    : html`<main class="sign-in-page"><${SignIn} onSignedIn=${() => setSignedIn(true)} /></main>`;
}

// Press and hover feedback is delegated once here, so every control in every list has it.
installTouchFeedback();
render(html`<${App} />`, document.getElementById("app"));
