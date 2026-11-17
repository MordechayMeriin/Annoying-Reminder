// Cognito login over plain HTTPS, no SDK: a code sent by email (the same login as the Android app).
//
// 1. sendCode(email): Cognito emails a code (choice-based sign-in, EMAIL_OTP). Only users that exist
//    in the pool can log in; sign-up is off.
// 2. verifyCode(email, code): trades the code for tokens, kept in localStorage.
// 3. getSession(): the current access token, refreshed first when it's about to expire (they last an
//    hour; the refresh token lasts years).
//
// The shape follows what the app used before (getSession / signOut / onAuthStateChange), so the rest
// of app.js didn't need to change.

const SESSION_KEY = "prominder.session";
const PENDING_KEY = "prominder.pending";   // the half-finished login, so a reload between steps keeps it
const MARGIN_S = 60;

export class AuthError extends Error {
  constructor(message, { code = "", network = false } = {}) {
    super(message);
    this.code = code;
    this.network = network;
  }
}

function friendly(code, message) {
  switch (code) {
    case "CodeMismatchException":
    case "ExpiredCodeException":
      return "That code is wrong or has expired. Check the latest email, or send a new code.";
    case "NotAuthorizedException":
      return /session/i.test(message || "")
        ? "This login attempt has expired. Send a new code."
        : "That code is wrong, or this email can't log in.";
    case "UserNotFoundException":
      return "There's no account with this email.";
    case "TooManyRequestsException":
    case "LimitExceededException":
      return "Too many tries. Wait a minute and try again.";
    case "InvalidParameterException":
      return /email|username/i.test(message || "") ? "That doesn't look like an email address." : message;
    default:
      return message || "Login failed.";
  }
}

export function createAuth({ region, clientId }) {
  const endpoint = `https://cognito-idp.${region}.amazonaws.com/`;
  const listeners = new Set();
  let refreshing = null;

  const read = (store, key) => {
    try {
      return JSON.parse(store.getItem(key) || "null");
    } catch {
      return null;
    }
  };
  const write = (store, key, value) => {
    try {
      if (value == null) store.removeItem(key);
      else store.setItem(key, JSON.stringify(value));
    } catch {
      /* private mode: the login lasts until the tab closes */
    }
  };

  let session = read(localStorage, SESSION_KEY);

  function setSession(next, event) {
    session = next;
    write(localStorage, SESSION_KEY, next);
    for (const cb of listeners) cb(event, next);
  }

  async function call(target, body) {
    let res;
    try {
      res = await fetch(endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/x-amz-json-1.1",
          "X-Amz-Target": `AWSCognitoIdentityProviderService.${target}`,
        },
        body: JSON.stringify(body),
      });
    } catch {
      throw new AuthError("Can't reach the login server. Check the internet connection and try again.", {
        network: true,
      });
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const code = String(data.__type || "").split("#").pop();
      throw new AuthError(friendly(code, data.message || data.Message), { code, network: res.status >= 500 });
    }
    return data;
  }

  function fromResult(result, email, oldRefresh) {
    const claims = JSON.parse(atob(result.AccessToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return {
      access_token: result.AccessToken,
      refresh_token: result.RefreshToken || oldRefresh,
      expires_at: Math.floor(Date.now() / 1000) + (result.ExpiresIn || 3600),
      user: { id: claims.sub, email },
    };
  }

  return {
    async sendCode(email) {
      email = email.trim().toLowerCase();
      let r = await call("InitiateAuth", {
        ClientId: clientId,
        AuthFlow: "USER_AUTH",
        AuthParameters: { USERNAME: email, PREFERRED_CHALLENGE: "EMAIL_OTP" },
      });
      if (r.ChallengeName === "SELECT_CHALLENGE") {
        r = await call("RespondToAuthChallenge", {
          ClientId: clientId,
          ChallengeName: "SELECT_CHALLENGE",
          Session: r.Session,
          ChallengeResponses: { USERNAME: email, ANSWER: "EMAIL_OTP" },
        });
      }
      if (r.ChallengeName !== "EMAIL_OTP") throw new AuthError("Login by email code isn't turned on for this app.");
      write(sessionStorage, PENDING_KEY, { email, session: r.Session });
    },

    async verifyCode(email, code) {
      email = email.trim().toLowerCase();
      const pending = read(sessionStorage, PENDING_KEY);
      if (!pending || pending.email !== email) throw new AuthError("Send a new code first.");
      const r = await call("RespondToAuthChallenge", {
        ClientId: clientId,
        ChallengeName: "EMAIL_OTP",
        Session: pending.session,
        ChallengeResponses: { USERNAME: email, EMAIL_OTP_CODE: code.replace(/\s/g, "") },
      });
      if (!r.AuthenticationResult) {
        // A wrong code can come back as the same challenge with a new session: keep trying with it.
        if (r.Session) write(sessionStorage, PENDING_KEY, { email, session: r.Session });
        throw new AuthError("That code is wrong. Check the latest email.");
      }
      write(sessionStorage, PENDING_KEY, null);
      setSession(fromResult(r.AuthenticationResult, email), "SIGNED_IN");
    },

    /** { data: { session } }, with a fresh access token when the current one is about to expire. */
    async getSession() {
      if (!session) return { data: { session: null } };
      if (session.expires_at - MARGIN_S > Date.now() / 1000) return { data: { session } };
      refreshing ||= (async () => {
        const current = session;
        try {
          const r = await call("InitiateAuth", {
            ClientId: clientId,
            AuthFlow: "REFRESH_TOKEN_AUTH",
            AuthParameters: { REFRESH_TOKEN: current.refresh_token },
          });
          setSession(fromResult(r.AuthenticationResult, current.user.email, current.refresh_token), "TOKEN_REFRESHED");
        } catch (e) {
          // Only Cognito saying the login is gone logs out; a network problem keeps it for later.
          if (!e.network && e.code === "NotAuthorizedException") setSession(null, "SIGNED_OUT");
        } finally {
          refreshing = null;
        }
      })();
      await refreshing;
      return { data: { session } };
    },

    async signOut() {
      const current = session;
      write(sessionStorage, PENDING_KEY, null);
      setSession(null, "SIGNED_OUT");
      if (current?.refresh_token) {
        call("RevokeToken", { ClientId: clientId, Token: current.refresh_token }).catch(() => {});
      }
    },

    /** cb(event, session) now (INITIAL_SESSION) and on every change, also from other tabs. */
    onAuthStateChange(cb) {
      listeners.add(cb);
      setTimeout(() => cb("INITIAL_SESSION", session), 0);
      return () => listeners.delete(cb);
    },

    /** Another tab logged in or out. */
    _syncFromStorage(event) {
      if (event.key !== SESSION_KEY) return;
      const next = read(localStorage, SESSION_KEY);
      const changed = !!next !== !!session;
      session = next;
      if (changed) for (const cb of listeners) cb(next ? "SIGNED_IN" : "SIGNED_OUT", next);
    },
  };
}
