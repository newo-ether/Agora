// Sign-in card, styled after the app's dialogs.
import { useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { Spinner } from "./material/progress.js";
import { t } from "./i18n.js";
import { icon, ICON_VISIBILITY, ICON_VISIBILITY_OFF, ICON_WEB } from "./icons.js";
import { postJson } from "./api.js";

export function Brand() {
  return html`<div class="brand">${icon(ICON_WEB)}<span>${t.title}</span></div>`;
}

/** Field error for a wrong password; banner text for everything else. */
function describeError(status, body) {
  if (status === 401 && body?.error === "wrong_password") {
    return { field: t.wrongPassword(body.attemptsLeft) };
  }
  if (status === 429) return { banner: t.locked(body?.retryAfterSeconds ?? 300) };
  if (status === 503) return { banner: t.notConfigured };
  return { banner: t.failed };
}

export function SignIn({ onSignedIn }) {
  const [password, setPassword] = useState("");
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState({});

  async function submit(event) {
    event.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    setError({});
    try {
      const response = await postJson("/api/login", { password });
      if (response.ok) {
        onSignedIn();
        return;
      }
      const body = await response.json().catch(() => null);
      setError(describeError(response.status, body));
    } catch {
      setError({ banner: t.failed });
    } finally {
      setBusy(false);
    }
  }

  return html`
    <form class="card" onSubmit=${submit}>
      <${Brand} />
      <h1>${t.signInTitle}</h1>
      <p>${t.signInHint}</p>
      ${error.banner && html`<div class="banner" role="alert">${error.banner}</div>`}
      <div class=${error.field ? "field invalid" : "field"}>
        <input id="password" type=${visible ? "text" : "password"} autocomplete="current-password"
          autofocus placeholder=" " aria-invalid=${error.field ? "true" : "false"}
          aria-describedby="password-supporting"
          value=${password} onInput=${(e) => { setPassword(e.currentTarget.value); setError({}); }} />
        <label for="password">${t.password}</label>
        <button class="icon-button" type="button" onClick=${() => setVisible(!visible)}
          aria-label=${visible ? t.hide : t.show}>
          ${icon(visible ? ICON_VISIBILITY_OFF : ICON_VISIBILITY)}
        </button>
      </div>
      <div class="supporting" id="password-supporting" role=${error.field ? "alert" : null}>
        ${error.field ?? ""}
      </div>
      <div class="actions">
        <button class="button filled" type="submit" disabled=${busy || !password}
          aria-label=${busy ? t.signingIn : null}>
          ${busy ? html`<${Spinner} size=${18} stroke=${2} color="inherit" />` : t.signIn}
        </button>
      </div>
    </form>`;
}
