// Pinned to an exact version: an unpinned "@2" can change under you at any time.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";

window.__appLoaded = true; // tells index.html's load watchdog the script arrived
const cfg = window.APP_CONFIG;
const supabase = createClient(cfg.SUPABASE_URL, cfg.SUPABASE_ANON_KEY);
const $ = (id) => document.getElementById(id);

// Labels for messages the assistant sent on its own
const KIND_LABEL = {
  morning: "☀️ Morning plan",
  checkin: "⏰ Check-in",
  evening: "🌙 Evening review",
  nudge: "💬 Check-in",
  reminder: "⏰ Reminder",
  meeting: "📅 Meeting",
  session: "⏱ Session",
  reentry: "👋 Welcome back",
};
const PAGE = 50;
const START_MIN_APP = 25;   // ▶ Start on the focus card (from a notification or a chip: 5)
const HEBREW = /[\u0590-\u05FF]/;

// Opened from a notification? (?m=<message id>, or ?task=<id> from older links / Pushover)
const params = new URLSearchParams(location.search);
let pendingMessageId = Number(params.get("m")) || null;
let pendingTaskId = Number(params.get("task")) || null;

let started = false;
let firstLoad = true;       // the first message load always scrolls to the bottom
let messages = [];          // loaded messages, oldest first
let hasMore = false;        // older messages exist on the server
let tasksById = new Map();  // open tasks we know about (for task chips)
let drawerTab = "today";
let detailTaskId = null;
const pendingSettings = new Map(); // unsaved settings: key → new text value, or null = back to default
let outgoing = null;        // the chat message being sent: { el, id (once the server saved it) }
let pollTimer = null;       // the 60 s check for new messages
let todayTasks = [];        // for the badge
const pendingDone = new Map(); // task id → timer: ✓ Done waits a few seconds so it can be undone
const UNDO_MS = 5000;
let renderedSigs = [];      // what's on screen: one signature per rendered message
let renderedOn = "";        // the date "Today" / "Yesterday" labels were computed on
let renderedLastDay = "";   // day of the last rendered message (for day separators)
let navDepth = 0;           // history entries pushed for open panels (Back button)
let ignorePops = 0;         // popstate events caused by our own history.go()
let drawerView = null;      // "list" | "detail" | "settings" | null (closed)
// Touch keyboards have no Shift+Enter, so there Enter makes a new line and only ➤ sends.
const touchKeyboard = window.matchMedia("(pointer: coarse)").matches;

// ------------------------------------------------------------------ helpers

/** A short message at the bottom; `action` ({ label, onClick }) adds a button, e.g. Undo. */
function toast(text, ms = 2500, action = null) {
  const t = $("toast");
  t.textContent = text;
  if (action) {
    const b = el("button", "toast-action", action.label);
    b.type = "button";
    b.onclick = () => {
      clearTimeout(toast._t);
      t.hidden = true;
      action.onClick();
    };
    t.appendChild(b);
  }
  t.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (t.hidden = true), ms);
}

/** fetch() to our API with the login token; throws on errors (and logs out on 401). */
async function authFetch(path, options = {}) {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  const res = await fetch(cfg.API_URL + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  });
  if (res.status === 401) {
    await supabase.auth.signOut();
    showLogin();
    throw new Error("Logged out");
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    const detail = body.detail;
    const err = new Error((typeof detail === "string" ? detail : detail?.message) || `HTTP ${res.status}`);
    if (detail?.errors) err.errors = detail.errors; // per-field errors (settings)
    throw err;
  }
  return res;
}

async function api(path, options = {}) {
  return (await authFetch(path, options)).json();
}

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text != null) e.textContent = text;
  return e;
}

const hhmm = (d) => d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });

function fmtTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  const now = new Date();
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  if (d.toDateString() === now.toDateString()) return `Today ${hhmm(d)}`;
  if (d.toDateString() === tomorrow.toDateString()) return `Tomorrow ${hhmm(d)}`;
  return d.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" }) + ` ${hhmm(d)}`;
}

function fmtDay(d) {
  const now = new Date();
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (d.toDateString() === now.toDateString()) return "Today";
  if (d.toDateString() === yesterday.toDateString()) return "Yesterday";
  return d.toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" });
}

/** The user's language (for text the app sends for them, e.g. "What now?"): their latest typed message. */
function userLang() {
  const last = [...messages].reverse().find((m) => m.role === "user" && m.kind === "chat");
  return last && HEBREW.test(last.content) ? "he" : "en";
}

// ------------------------------------------------------------------ auth

function hideLoading() {
  $("loading").hidden = true;
}

function showLogin() {
  started = false;
  clearInterval(pollTimer);
  pollTimer = null;
  hideLoading();
  $("main").hidden = true;
  closeDrawer({ force: true });
  navBack(0);
  $("login").hidden = false;
}

$("email-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const email = $("email").value.trim();
  // shouldCreateUser:false → only the existing account (yours) can log in.
  const { error } = await supabase.auth.signInWithOtp({
    email,
    options: { shouldCreateUser: false, emailRedirectTo: location.origin },
  });
  if (error) {
    $("login-msg").textContent = error.message;
    return;
  }
  $("login-msg").textContent = "Check your email: enter the code, or tap the link.";
  $("email-form").hidden = true;
  $("code-form").hidden = false;
});

$("code-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const { error } = await supabase.auth.verifyOtp({
    email: $("email").value.trim(),
    token: $("code").value.trim(),
    type: "email",
  });
  if (error) $("login-msg").textContent = error.message;
});

$("logout").addEventListener("click", () => supabase.auth.signOut());

supabase.auth.onAuthStateChange((_event, session) => {
  if (session) startApp();
  else showLogin();
});

// ------------------------------------------------------------------ chat

function taskChip(id, title) {
  // Title order: from the message itself (all linked tasks), then the loaded task list.
  const chip = el("button", "task-chip", title || tasksById.get(id)?.title || `Task #${id}`);
  chip.type = "button";
  chip.dir = "auto";
  chip.onclick = () => openTask(id);
  return chip;
}

function messageEl(m) {
  if (m.kind === "event") {
    const ev = el("div", "event", m.content);
    ev.dir = "auto";
    ev.dataset.id = m.id;
    return ev;
  }
  const div = el("div", `msg ${m.role}`);
  div.dataset.id = m.id;
  if (KIND_LABEL[m.kind] || (m.role === "assistant" && m.task_ids?.length)) {
    const head = el("div", "msg-head");
    if (KIND_LABEL[m.kind]) head.appendChild(el("span", "kind", KIND_LABEL[m.kind]));
    (m.task_ids || []).forEach((id) =>
      head.appendChild(taskChip(id, m.task_titles?.[id] || (id === m.task_id ? m.task_title : null))));
    div.appendChild(head);
  }
  const body = el("div", "msg-body", m.content);
  body.dir = "auto";
  div.appendChild(body);
  if (m.created_at) div.appendChild(el("div", "msg-time", hhmm(new Date(m.created_at))));
  return div;
}

// Only new messages are appended, and a refresh with nothing new does no work: a full rebuild would
// clear selected text and cut off the highlight animation. (State: renderedSigs etc. at the top.)
function msgSig(m) {
  return `${m.id}|${(m.task_ids || []).join(",")}|${m.task_status || ""}|${m.task_title || ""}`;
}

function renderMessages() {
  const box = $("messages");
  // Bubbles of messages being sent (or that failed): drop those whose saved copy is loaded now,
  // and keep the rest at the bottom.
  const ids = new Set(messages.map((m) => m.id));
  const bubbles = [...box.querySelectorAll(".msg.outgoing")];
  bubbles.forEach((b) => b.remove());
  const keep = bubbles.filter((b) => !(b.dataset.sentId && ids.has(Number(b.dataset.sentId))));

  const sigs = messages.map(msgSig);
  const today = new Date().toDateString();
  const n = renderedSigs.length;
  const appendOnly = n > 0 && today === renderedOn && sigs.length >= n && renderedSigs.every((s, i) => s === sigs[i]);
  if (!appendOnly) {
    box.textContent = "";
    renderedLastDay = "";
  }
  for (const m of appendOnly ? messages.slice(n) : messages) {
    const d = new Date(m.created_at);
    if (d.toDateString() !== renderedLastDay) {
      renderedLastDay = d.toDateString();
      box.appendChild(el("div", "day-sep", fmtDay(d)));
    }
    box.appendChild(messageEl(m));
  }
  renderedSigs = sigs;
  renderedOn = today;
  keep.forEach((b) => box.appendChild(b));
  $("load-earlier").hidden = !hasMore;
  renderChips();
}

// ---- reply buttons ("chips"): chosen by the server, shown only under the latest message while it's
// the assistant's. Any reply (typed, or a tap that writes an event) makes them disappear.

let chipsBusy = false;

function renderChips() {
  const box = $("messages");
  box.querySelectorAll(".chips").forEach((c) => c.remove());
  const last = messages[messages.length - 1];
  if (!last || last.role !== "assistant" || !last.chips?.length || outgoing || box.querySelector(".msg.outgoing")) return;
  const row = el("div", "chips");
  row.dataset.forId = last.id;
  last.chips.forEach((c, i) => {
    const b = el("button", "chip" + (i === 0 && c.action ? " primary" : ""), c.label);
    b.type = "button";
    b.dir = "auto";
    b.onclick = () => onChip(c, row);
    row.appendChild(b);
  });
  box.appendChild(row);
}

async function onChip(chip, row) {
  if (chipsBusy) return;
  if (!chip.action) {
    row.remove();
    return sendMessage(chip.label); // a text chip is just a reply in the user's words
  }
  chipsBusy = true;
  row.querySelectorAll("button").forEach((b) => (b.disabled = true));
  try {
    await runAction(chip.task_id, chip.action, chip.minutes);
  } finally {
    chipsBusy = false;
  }
}

const ACTION_TOAST = {
  done: "Done ✓", snooze: "Snoozed for 1 hour", cancel: "Cancelled", later: "OK, later", start: "▶ Started",
};

/** A task action without the LLM (chips, the focus card, the task list); refreshes what it changed. */
async function runAction(id, action, minutes, { quiet = false } = {}) {
  try {
    const body = { action };
    if (minutes) body.minutes = minutes;
    await api(`/tasks/${id}/action`, { method: "POST", body: JSON.stringify(body) });
    if (!quiet) toast(action === "start" && minutes ? `▶ ${minutes} min — go` : ACTION_TOAST[action]);
    await Promise.all([loadTasks(), loadNewer(), loadFocus()]);
    if (detailTaskId === id) openTask(id);
  } catch (e) {
    toast(e.message);
    Promise.all([loadTasks(), loadNewer()]).catch(() => {});
  }
}

// ---- Today's 3 (the focus card): display only

let focusFolded = false;
try {
  focusFolded = localStorage.getItem("focusFolded") === "1";
} catch {}

async function loadFocus() {
  const data = await api("/focus");
  renderFocus(data);
}

function renderFocus(data) {
  const card = $("focus");
  card.hidden = !data.tasks.length;
  card.classList.toggle("folded", focusFolded);
  if (!data.tasks.length) return;
  $("focus-done").textContent = data.doneToday ? `Done today: ${data.doneToday}` : "";
  const list = $("focus-list");
  list.textContent = "";
  data.tasks.forEach((t, i) => {
    const li = el("li", "focus-row" + (t.done ? " done" : ""));
    li.appendChild(el("span", "num", t.done ? "✓" : String(i + 1)));
    const text = el("button", "text");
    text.type = "button";
    const title = el("div", "row-title", t.title);
    title.dir = "auto";
    text.appendChild(title);
    if (t.next_action && !t.done) {
      const step = el("div", "row-step", "▶ " + t.next_action);
      step.dir = "auto";
      text.appendChild(step);
    }
    text.onclick = () => openTask(t.id);
    li.appendChild(text);
    if (!t.done) {
      const start = el("button", "small start", "▶");
      start.type = "button";
      start.title = `Start ${START_MIN_APP} min`;
      start.setAttribute("aria-label", start.title);
      start.onclick = () => runAction(t.id, "start", START_MIN_APP);
      li.appendChild(start);
    }
    list.appendChild(li);
  });
}

$("focus").querySelector(".focus-head").addEventListener("click", (e) => {
  if (e.target.closest("button")) return;
  focusFolded = !focusFolded;
  $("focus").classList.toggle("folded", focusFolded);
  try {
    localStorage.setItem("focusFolded", focusFolded ? "1" : "0");
  } catch {}
});
$("focus-what-now").onclick = () => sendMessage(userLang() === "he" ? "מה עכשיו?" : "What now?");

function mergeMessages(rows) {
  const byId = new Map(messages.map((m) => [m.id, m]));
  rows.forEach((m) => byId.set(m.id, m));
  messages = [...byId.values()].sort((a, b) => a.id - b.id);
}

function nearBottom() {
  return window.innerHeight + window.scrollY >= document.body.scrollHeight - 120;
}

function scrollToBottom() {
  window.scrollTo(0, document.body.scrollHeight);
}

async function loadLatest({ stickToBottom = true } = {}) {
  const wasNearBottom = nearBottom();
  const rows = await api(`/messages?limit=${PAGE}`);
  if (!messages.length) hasMore = rows.length === PAGE;
  mergeMessages(rows);
  renderMessages();
  if (stickToBottom && (wasNearBottom || firstLoad)) scrollToBottom();
  firstLoad = false;
}

/** Only what's new since the last loaded message (the 60 s check). */
async function loadNewer() {
  if (!messages.length) return loadLatest();
  const wasNearBottom = nearBottom();
  const rows = await api(`/messages?limit=${PAGE}&after=${messages[messages.length - 1].id}`);
  if (!rows.length) return;
  if (rows.length === PAGE) {
    // A big gap (the app was closed for a while): start over from the newest page.
    messages = [];
    hasMore = true;
  }
  mergeMessages(rows);
  renderMessages();
  if (wasNearBottom) scrollToBottom();
}

async function loadEarlier() {
  if (!messages.length) return 0;
  const before = messages[0].id;
  const rows = await api(`/messages?limit=${PAGE}&before=${before}`);
  hasMore = rows.length === PAGE;
  const oldHeight = document.body.scrollHeight;
  mergeMessages(rows);
  renderMessages();
  window.scrollBy(0, document.body.scrollHeight - oldHeight); // keep the view where it was
  return rows.length;
}

$("load-earlier").onclick = () => loadEarlier().catch((e) => toast(e.message));

/** Scroll to a message (loading older pages if needed), highlight it, focus the reply box. */
async function showMessage(id) {
  for (let i = 0; i < 6 && !messages.some((m) => m.id === id) && hasMore; i++) {
    if (!(await loadEarlier())) break;
  }
  const target = document.querySelector(`[data-id="${id}"]`);
  if (target) {
    target.scrollIntoView({ block: "center" });
    target.classList.add("highlight");
    setTimeout(() => target.classList.remove("highlight"), 2500);
  } else {
    scrollToBottom();
  }
  $("chat-input").focus({ preventScroll: true });
}

function autosize() {
  const input = $("chat-input");
  input.style.height = "auto";
  const borders = input.offsetHeight - input.clientHeight; // height is border-box; scrollHeight isn't
  input.style.height = Math.min(input.scrollHeight + borders, 160) + "px";
  // The composer is fixed at the bottom; keep the last messages clear of it as it grows.
  $("main").style.paddingBottom = $("chat-form").offsetHeight + 12 + "px";
}

if (touchKeyboard) $("chat-input").enterKeyHint = "enter";
$("chat-input").addEventListener("input", autosize);
$("chat-input").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing && !touchKeyboard) {
    e.preventDefault();
    $("chat-form").requestSubmit();
  }
});

// ---- sending: progress from POST /chat (a short Server-Sent Events stream)

/** Sends a chat message; calls onEvent for each progress event; resolves with the final `done` event. */
async function sendChat(text, onEvent) {
  const lost = () => Object.assign(new Error("Connection lost"), { connectionLost: true });
  let res;
  try {
    res = await authFetch("/chat", { method: "POST", body: JSON.stringify({ message: text }) });
  } catch (err) {
    // fetch() throws a TypeError ("Failed to fetch") when the server can't be reached at all
    throw err instanceof TypeError ? new Error("Couldn't reach the server. Check your connection and try again.") : err;
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  for (;;) {
    let chunk;
    try {
      chunk = await reader.read();
    } catch {
      throw lost();
    }
    const { value, done } = chunk;
    if (done) break;
    buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
    let cut;
    while ((cut = buf.indexOf("\n\n")) >= 0) {
      const block = buf.slice(0, cut);
      buf = buf.slice(cut + 2);
      const data = block.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim()).join("\n");
      if (!data) continue; // keepalive
      let ev;
      try {
        ev = JSON.parse(data);
      } catch {
        console.warn("Skipping a malformed progress event", data);
        continue; // progress is cosmetic; a turn that never sends a readable `done` ends as "connection lost"
      }
      if (ev.type === "done") return ev;
      if (ev.type === "error") throw new Error(ev.detail || "Something went wrong");
      onEvent(ev);
    }
  }
  throw lost();
}

// What the assistant is doing, by tool. t = the tool event ({ name, title?, taskTitle?, kind? }).
const TOOL_LABEL = {
  add_task: (t) => [t.kind === "meeting" ? "📅 Saving a new meeting" : "📝 Writing a new task", t.title],
  list_tasks: () => ["🔎 Checking your tasks"],
  update_task: (t) => ["✏️ Updating task", t.taskTitle || t.title],
  complete_task: (t) => ["✅ Marking as done", t.taskTitle],
  cancel_task: (t) => ["🗑 Cancelling task", t.taskTitle],
  schedule_followup: (t) => ["⏰ Scheduling a follow-up", t.taskTitle],
  add_subtask: (t) => ["➕ Adding a step", t.title],
  log_interaction: (t) => ["🗒 Making a note", t.taskTitle],
  mark_blocked: (t) => ["⛔ Marking as blocked", t.taskTitle],
  set_focus: () => ["🎯 Setting Today's 3"],
};

function toolLabel(t) {
  const [what, which] = (TOOL_LABEL[t.name] || (() => ["⚙️ Working"]))(t);
  return which ? `${what}: ${which}` : what;
}

/** The status area under the messages: tool steps of this turn + "typing…". Not a chat message. */
const status = {
  steps: [],
  typing: false,
  error: "",
  reset() {
    this.steps = [];
    this.typing = false;
    this.error = "";
    this.render();
  },
  render() {
    const box = $("chat-status");
    box.textContent = "";
    this.steps.forEach((s, i) => {
      const running = i === this.steps.length - 1 && !this.typing;
      const line = el("div", "status-step" + (running ? " running" : ""), s);
      line.dir = "auto";
      box.appendChild(line);
    });
    if (this.typing) {
      const t = el("div", "typing");
      t.setAttribute("aria-label", "The assistant is typing");
      t.append(el("span"), el("span"), el("span"));
      box.appendChild(t);
    }
    if (this.error) box.appendChild(el("div", "status-error", "⚠️ " + this.error));
    box.hidden = !box.childElementCount;
  },
};

function setDelivery(bubble, text, cls) {
  const d = bubble.querySelector(".msg-delivery");
  d.textContent = text;
  d.className = "msg-delivery " + (cls || "");
}

$("chat-form").addEventListener("submit", (e) => {
  e.preventDefault();
  if (outgoing) return; // one at a time
  const input = $("chat-input");
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  autosize();
  sendMessage(text);
});

/** Sends one chat message (typed, a text chip, "What now?") and shows its progress and the reply. */
async function sendMessage(text) {
  if (outgoing) return; // one at a time
  const input = $("chat-input");
  $("messages").querySelectorAll(".msg.outgoing.failed").forEach((b) => b.remove()); // old "Not sent"
  const bubble = messageEl({ role: "user", content: text, kind: "chat" });
  bubble.classList.add("outgoing");
  bubble.appendChild(el("div", "msg-delivery"));
  setDelivery(bubble, "Sending…", "sending");
  const sending = { el: bubble, id: null }; // stays readable after `outgoing` is cleared
  outgoing = sending;
  $("messages").appendChild(bubble);
  renderChips(); // a reply hides the buttons
  status.reset();
  scrollToBottom();
  $("chat-send").disabled = true;

  const follow = () => nearBottom() && scrollToBottom();
  try {
    const res = await sendChat(text, (ev) => {
      if (ev.type === "received") {
        sending.id = ev.userMessageId;
        bubble.dataset.sentId = ev.userMessageId;
        setDelivery(bubble, "✓ Sent", "sent");
      } else if (ev.type === "thinking") {
        status.typing = true;
      } else if (ev.type === "tool") {
        status.typing = false;
        status.steps.push(toolLabel(ev));
      }
      status.render();
      follow();
    });
    // Show the reply straight from `done` (it carries both saved messages); sync the rest quietly.
    const stick = nearBottom();
    status.reset();
    outgoing = null;
    if (res.messages?.length) {
      mergeMessages(res.messages);
      renderMessages();
      if (stick) scrollToBottom();
      loadNewer().catch(() => {}); // events this turn created, anything else new
    } else {
      loadLatest().catch(() => {});
    }
    if (res.tasksChanged) Promise.all([loadTasks(), loadFocus()]).catch(() => {});
  } catch (err) {
    const saved = sending.id !== null; // the server has the message (the reply may still come)
    outgoing = null;
    status.reset();
    status.error = err.connectionLost && saved ? "Connection lost. Refresh in a moment to see the reply." : err.message;
    status.render();
    if (saved) {
      loadLatest().catch(() => {});
    } else {
      setDelivery(bubble, "Not sent", "failed"); // stays until the next message is sent
      bubble.classList.add("failed");
      if (!input.value) {
        input.value = text; // give the text back to retry
        autosize();
      }
    }
    follow();
    renderChips();
  } finally {
    $("chat-send").disabled = false;
  }
}

// ------------------------------------------------------------------ Back button
//
// Panels add browser history entries, so Android's Back closes them instead of leaving the app:
// depth 1 = the drawer (task lists, settings, or a task opened from the chat), depth 2 = a task
// opened from the lists. Closing a panel with ✕ / ‹ Back / Esc steps history back to match.

let queuedDepth = 0;  // a push asked for while our history.go() was still on its way

function navPush(depth) {
  if (ignorePops > 0) {
    // history.go() is async: pushing now would add the entry before the back step lands, and that
    // step would then undo it. Push once it has landed (see popstate).
    queuedDepth = Math.max(queuedDepth, depth);
    return;
  }
  while (navDepth < depth) history.pushState({ nav: ++navDepth }, "");
}

function navBack(depth) {
  if (navDepth <= depth) return;
  const steps = navDepth - depth;
  navDepth = depth;
  ignorePops++;
  history.go(-steps);
}

window.addEventListener("popstate", (e) => {
  if (ignorePops > 0) {
    ignorePops--;
    if (!ignorePops && queuedDepth) {
      const depth = queuedDepth;
      queuedDepth = 0;
      navPush(depth);
    }
    return;
  }
  const depth = e.state?.nav || 0;
  if (depth >= navDepth) return;
  navDepth = depth;
  if (depth === 0) {
    if (!closeDrawer()) navPush(1); // kept open (unsaved settings): put the entry back
  } else if (drawerView === "detail") {
    showDrawerTab(drawerTab);
  }
});

// ------------------------------------------------------------------ tasks drawer

function openDrawer() {
  $("drawer").hidden = false;
  $("drawer-backdrop").hidden = false;
  document.body.classList.add("drawer-open");
  navPush(1);
}

/** Closes the drawer. Returns false if the user chose to keep unsaved settings. */
function closeDrawer({ force = false } = {}) {
  if (!force && !$("settings-panel").hidden && pendingSettings.size && !confirm("Discard unsaved settings?")) {
    return false;
  }
  pendingSettings.clear();
  $("settings-panel").hidden = true;
  $("drawer-title").hidden = true;
  $("drawer").hidden = true;
  $("drawer-backdrop").hidden = true;
  document.body.classList.remove("drawer-open");
  detailTaskId = null;
  drawerView = null;
  return true;
}

/** ✕, the backdrop, Esc. */
function requestCloseDrawer() {
  if (closeDrawer()) navBack(0);
}

/** ‹ Back from a task to the lists. */
function backToList() {
  navBack(1);
  showDrawerTab(drawerTab);
}

function showDrawerTab(tab) {
  drawerView = "list";
  drawerTab = tab;
  detailTaskId = null;
  $("task-detail").hidden = true;
  $("settings-panel").hidden = true;
  $("drawer-title").hidden = true;
  $("drawer-back").hidden = true;
  $("drawer-tabs").hidden = false;
  document.querySelectorAll("#drawer-tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  $("tab-today").hidden = tab !== "today";
  $("tab-all").hidden = tab !== "all";
}

$("open-tasks").onclick = () => {
  showDrawerTab(drawerTab);
  openDrawer();
  loadTasks().catch((e) => toast(e.message));
};
$("drawer-close").onclick = requestCloseDrawer;
$("drawer-backdrop").onclick = requestCloseDrawer;
$("drawer-back").onclick = backToList;
document.querySelectorAll("#drawer-tabs button").forEach((b) => (b.onclick = () => showDrawerTab(b.dataset.tab)));
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !$("drawer").hidden) requestCloseDrawer();
});

function taskMeta(t) {
  const overdue = t.due_at && new Date(t.due_at) < new Date() && t.kind !== "meeting";
  const parts = [t.due_at ? (overdue ? "Overdue · " : "") + fmtTime(t.due_at) : "No date"];
  if (t.recurrence) parts.push(`🔁 ${t.recurrence}`);
  if (t.blocked_reason) parts.push("⛔ waiting");
  if (t.parent_id) parts.push(`step of ${tasksById.get(t.parent_id)?.title || "#" + t.parent_id}`);
  return { text: parts.join(" · "), overdue };
}

function taskItem(t) {
  const li = el("li", "task");
  li.dataset.taskId = t.id;
  li.hidden = pendingDone.has(t.id);
  const prio = t.priority === 3 ? "🚨 " : t.priority === 2 ? "❗ " : "";
  const icon = t.kind === "meeting" ? "📅 " : "";
  const info = el("button", "info");
  info.type = "button";
  const title = el("div", "title", prio + icon + t.title);
  title.dir = "auto";
  const { text, overdue } = taskMeta(t);
  info.appendChild(title);
  if (t.next_action && t.status !== "done") {
    const step = el("div", "step", "▶ " + t.next_action);
    step.dir = "auto";
    info.appendChild(step);
  }
  info.appendChild(el("div", "meta" + (overdue ? " overdue" : ""), text));
  info.onclick = () => openTask(t.id);

  const btns = el("div", "btns");
  const snooze = el("button", "snooze-btn", "⏰");
  snooze.title = "Snooze 1h";
  snooze.onclick = () => taskAction(t.id, "snooze");
  const done = el("button", "done-btn", "✓");
  done.title = "Done";
  done.onclick = () => markDone(t.id);
  btns.append(snooze, done);
  li.append(info, btns);
  return li;
}

function fill(listId, list, emptyText) {
  const ul = $(listId);
  ul.textContent = "";
  if (!list.length) {
    ul.appendChild(el("li", "empty", emptyText));
    return;
  }
  list.forEach((t) => ul.appendChild(taskItem(t)));
}

async function loadTasks() {
  const { today, upcoming, open } = await api("/tasks/overview?days=7");
  tasksById = new Map(open.map((t) => [t.id, t]));
  todayTasks = today;
  fill("today-list", today, "Nothing for today 🎉");
  fill("upcoming-list", upcoming, "Nothing in the next 7 days");
  fill("all-list", open, "No open tasks");
  updateBadge();
}

function updateBadge() {
  const n = todayTasks.filter((t) => !pendingDone.has(t.id)).length;
  const badge = $("task-count");
  badge.textContent = n;
  badge.hidden = !n;
}

function taskAction(id, action, { quiet = false } = {}) {
  return runAction(id, action, action === "snooze" ? 60 : undefined, { quiet });
}

// ✓ Done is easy to hit by accident, so it waits UNDO_MS before reaching the server; until then the
// task is just hidden and the toast offers Undo. Leaving the app sends any waiting Done right away.

function setTaskRowsHidden(id, hidden) {
  document.querySelectorAll(`.task[data-task-id="${id}"]`).forEach((li) => (li.hidden = hidden));
}

function markDone(id) {
  if (pendingDone.has(id)) return;
  pendingDone.set(id, setTimeout(() => commitDone(id), UNDO_MS));
  setTaskRowsHidden(id, true);
  updateBadge();
  toast("Done ✓", UNDO_MS, { label: "Undo", onClick: () => undoDone(id) });
}

function undoDone(id) {
  if (!pendingDone.has(id)) return;
  clearTimeout(pendingDone.get(id));
  pendingDone.delete(id);
  setTaskRowsHidden(id, false);
  updateBadge();
  toast("Not done");
}

function commitDone(id) {
  if (!pendingDone.has(id)) return;
  clearTimeout(pendingDone.get(id));
  pendingDone.delete(id);
  taskAction(id, "done", { quiet: true });
}

function flushPendingDone() {
  [...pendingDone.keys()].forEach(commitDone);
}

async function openTask(id) {
  const fromList = !$("drawer").hidden && drawerView === "list";
  openDrawer();
  if (fromList) navPush(2); // Back returns to the list
  drawerView = "detail";
  detailTaskId = id;
  $("tab-today").hidden = $("tab-all").hidden = true;
  $("settings-panel").hidden = $("drawer-title").hidden = true;
  $("drawer-tabs").hidden = true;
  $("drawer-back").hidden = false;
  $("task-detail").hidden = false;
  $("detail-title").textContent = "…";
  let t;
  try {
    t = await api(`/tasks/${id}`);
  } catch (e) {
    $("detail-title").textContent = e.message;
    return;
  }
  if (detailTaskId !== id) return;
  const prio = t.priority === 3 ? "🚨 " : t.priority === 2 ? "❗ " : "";
  $("detail-title").textContent = prio + (t.kind === "meeting" ? "📅 " : "") + t.title;
  const status = t.status !== "open" ? ` · ${t.status}` : "";
  $("detail-meta").textContent = taskMeta(t).text + status +
    (t.remind_at ? ` · next check-in ${fmtTime(t.remind_at)}` : "");
  $("detail-step").hidden = !t.next_action;
  $("detail-step").textContent = t.next_action ? `▶ First step: ${t.next_action}` +
    (t.estimate_min ? ` · whole task ~${t.estimate_min} min` : "") : "";
  $("detail-blocked").hidden = !t.blocked_reason;
  $("detail-blocked").textContent = t.blocked_reason
    ? `⛔ Waiting: ${t.blocked_reason}` + (t.blocked_until ? ` (check again ${fmtTime(t.blocked_until)})` : "")
    : "";

  const desc = $("detail-desc");
  desc.value = t.description || "";
  desc.dataset.original = desc.value;
  $("detail-save").disabled = true;
  ["detail-snooze", "detail-done", "detail-cancel"].forEach((b) => ($(b).hidden = t.status !== "open"));

  const subs = $("detail-subtasks");
  subs.textContent = "";
  if (!t.subtasks.length) subs.appendChild(el("li", "empty", "No steps yet. Ask the assistant to break it down."));
  t.subtasks.forEach((s) => {
    const li = taskItem(s);
    if (s.status === "done") li.classList.add("done");
    subs.appendChild(li);
  });

  const evs = $("detail-events");
  evs.textContent = "";
  if (!t.events.length) evs.appendChild(el("li", "empty", "Nothing yet."));
  t.events.forEach((e) => {
    const li = el("li", "", `${fmtTime(e.created_at)} · ${e.kind.replace("_", " ")}`);
    if (e.note && e.kind !== "description_changed") {
      const note = el("span", "note", ` — ${e.note}`);
      note.dir = "auto";
      li.appendChild(note);
    }
    evs.appendChild(li);
  });
}

$("detail-desc").addEventListener("input", (e) => {
  $("detail-save").disabled = e.target.value === e.target.dataset.original;
});
$("detail-save").onclick = async () => {
  try {
    await api(`/tasks/${detailTaskId}`, {
      method: "PATCH",
      body: JSON.stringify({ description: $("detail-desc").value }),
    });
    toast("Saved");
    openTask(detailTaskId);
  } catch (e) {
    toast(e.message);
  }
};
$("detail-done").onclick = () => {
  const id = detailTaskId;
  backToList();
  markDone(id);
};
$("detail-snooze").onclick = () => taskAction(detailTaskId, "snooze");
$("detail-cancel").onclick = () => {
  if (confirm("Cancel this task?")) taskAction(detailTaskId, "cancel");
};

// ------------------------------------------------------------------ settings
//
// Behaviour settings (times, delays, limits, Shabbat). The server validates and stores them;
// anything left at its default is stored as "no override".

const WEEKDAYS = [["sun", "Sunday"], ["mon", "Monday"], ["tue", "Tuesday"], ["wed", "Wednesday"],
  ["thu", "Thursday"], ["fri", "Friday"], ["sat", "Saturday"]];
let settingsFields = [];               // from GET /settings
const settingControls = new Map();     // key → { get, set, row, error }

function applySettings(data) {
  settingsFields = data.fields;
  return data;
}

function fmtShort(iso) {
  // Fixed English format, so the line reads the same in any phone language
  const d = new Date(iso);
  return d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "numeric" }) + ` ${hhmm(d)}`;
}

function restPreview(rest) {
  const box = el("div", "rest-preview");
  if (!rest) {
    box.textContent = "Off — reminders are sent on Shabbat and holidays too.";
    return box;
  }
  const head = el("div", "", rest.active ? "🕯 Quiet now: " : "🕯 Next quiet time: ");
  const name = el("strong", "", rest.name);
  name.dir = "rtl";
  head.appendChild(name);
  const when = el("div", "rest-when", `${fmtShort(rest.start)} → ${fmtShort(rest.end)}`);
  when.dir = "ltr";
  box.append(head, when);
  if (rest.active) box.classList.add("active");
  return box;
}

function settingControl(f) {
  const id = `set-${f.key}`;
  const input = el(f.kind === "weekday" || f.kind === "choice" ? "select" : "input");
  input.id = id;
  if (f.kind === "bool") {
    input.type = "checkbox";
    input.className = "switch";
    return { node: input, get: () => (input.checked ? "true" : "false"), set: (v) => (input.checked = v === "true") };
  }
  if (f.kind === "time") {
    input.type = "time";
    return { node: input, get: () => input.value, set: (v) => (input.value = v) };
  }
  if (f.kind === "time_off") {
    input.type = "time";
    const on = el("input");
    on.type = "checkbox";
    on.className = "switch";
    on.setAttribute("aria-label", `${f.label}: on`);
    on.addEventListener("change", () => {
      input.disabled = !on.checked;
      if (on.checked && !input.value) input.value = f.default !== "off" ? f.default : "20:00";
    });
    const wrap = el("div", "inline");
    wrap.append(on, input);
    return {
      node: wrap,
      get: () => (on.checked && input.value ? input.value : "off"),
      set: (v) => {
        on.checked = v !== "off";
        input.disabled = v === "off";
        input.value = v === "off" ? "" : v;
      },
    };
  }
  if (f.kind === "weekday") {
    WEEKDAYS.forEach(([v, label]) => {
      const o = el("option", "", label);
      o.value = v;
      input.appendChild(o);
    });
    return { node: input, get: () => input.value, set: (v) => (input.value = v) };
  }
  if (f.kind === "choice") {
    (f.options || []).forEach(({ value, label }) => {
      const o = el("option", "", label);
      o.value = value;
      input.appendChild(o);
    });
    return { node: input, get: () => input.value, set: (v) => (input.value = v) };
  }
  if (f.kind === "int" || f.kind === "float") {
    input.type = "number";
    input.inputMode = f.kind === "int" ? "numeric" : "decimal";
    input.step = f.kind === "int" ? "1" : "any";
    if (f.min != null) input.min = f.min;
    if (f.max != null) input.max = f.max;
  } else {
    input.type = "text";
    input.spellcheck = false;
    input.autocapitalize = "off";
    if (f.kind === "times") input.placeholder = "13:00, 17:00";
  }
  return { node: input, get: () => input.value.trim(), set: (v) => (input.value = v) };
}

function defaultText(f) {
  if (f.kind === "bool") return f.default === "true" ? "on" : "off";
  if (f.kind === "weekday") return WEEKDAYS.find(([v]) => v === f.default)?.[1] || f.default;
  if (f.kind === "choice") return f.options?.find((o) => o.value === f.default)?.label || f.default;
  return f.default === "" ? "none" : f.default;
}

function onSettingEdited(f) {
  const c = settingControls.get(f.key);
  const v = c.get();
  if (v === f.default) {
    if (f.overridden) pendingSettings.set(f.key, null); // back to the default
    else pendingSettings.delete(f.key);
  } else if (v === f.value) {
    pendingSettings.delete(f.key);
  } else {
    pendingSettings.set(f.key, v);
  }
  c.row.classList.remove("invalid");
  c.error.hidden = true;
  c.reset.hidden = v === f.default;
  c.row.classList.toggle("changed", pendingSettings.has(f.key));
  $("settings-save").disabled = $("settings-discard").disabled = !pendingSettings.size;
}

function pushoverBox(pushover) {
  const box = el("div", "rest-preview");
  if (!pushover?.configured) {
    box.textContent = "Not set up — add PUSHOVER_TOKEN and PUSHOVER_USER on Render to get loud, " +
      "repeating alerts. Until then everything goes to browser push.";
    return box;
  }
  box.appendChild(el("div", "", "Send a test to hear each level:"));
  const row = el("div", "inline");
  for (const [level, label] of [["normal", "Normal"], ["urgent", "Urgent"], ["alarm", "Alarm"]]) {
    const b = el("button", "small ghost", label);
    b.type = "button";
    b.onclick = async () => {
      b.disabled = true;
      try {
        await api("/pushover/test", { method: "POST", body: JSON.stringify({ level }) });
        toast(level === "alarm" ? "Alarm sent — acknowledge it in Pushover" : "Sent to Pushover");
      } catch (e) {
        toast(e.message);
      } finally {
        b.disabled = false;
      }
    };
    row.appendChild(b);
  }
  box.appendChild(row);
  return box;
}

function renderSettings(rest, pushover) {
  const form = $("settings-form");
  form.textContent = "";
  settingControls.clear();
  pendingSettings.clear();
  $("settings-save").disabled = $("settings-discard").disabled = true;

  const groups = new Map();
  settingsFields.forEach((f) => {
    if (!groups.has(f.group)) groups.set(f.group, []);
    groups.get(f.group).push(f);
  });
  for (const [group, list] of groups) {
    const section = el("section", "settings-group");
    section.appendChild(el("h2", "", group));
    if (list.some((f) => f.key === "shabbat_mode")) section.appendChild(restPreview(rest));
    if (list.some((f) => f.key === "pushover_mode")) section.appendChild(pushoverBox(pushover));
    for (const f of list) {
      const c = settingControl(f);
      const row = el("div", `setting kind-${f.kind}`);
      const label = el("label", "setting-label", f.label);
      label.htmlFor = `set-${f.key}`;
      const control = el("div", "setting-control");
      const reset = el("button", "reset", "↺");
      reset.type = "button";
      reset.title = `Back to default (${defaultText(f)})`;
      reset.setAttribute("aria-label", reset.title);
      control.append(c.node, reset);
      const help = el("div", "setting-help", [f.help, `Default: ${defaultText(f)}`].filter(Boolean).join(" · "));
      const error = el("div", "setting-error");
      error.hidden = true;
      row.append(label, control, help, error);
      section.appendChild(row);

      Object.assign(c, { row, error, reset });
      settingControls.set(f.key, c);
      c.set(f.value);
      reset.hidden = f.value === f.default;
      reset.onclick = () => {
        c.set(f.default);
        onSettingEdited(f);
      };
      row.addEventListener("input", () => onSettingEdited(f));
      row.addEventListener("change", () => onSettingEdited(f));
    }
    form.appendChild(section);
  }
}

async function openSettings() {
  openDrawer();
  drawerView = "settings";
  detailTaskId = null;
  $("tab-today").hidden = $("tab-all").hidden = $("task-detail").hidden = true;
  $("drawer-tabs").hidden = $("drawer-back").hidden = true;
  $("drawer-title").hidden = false;
  $("settings-panel").hidden = false;
  $("settings-form").textContent = "Loading…";
  try {
    const data = applySettings(await api("/settings"));
    renderSettings(data.rest, data.pushover);
  } catch (e) {
    $("settings-form").textContent = e.message;
  }
}

$("open-settings").onclick = () => openSettings();
$("settings-discard").onclick = () => {
  pendingSettings.clear();
  openSettings();
};
$("settings-save").onclick = async () => {
  if (!pendingSettings.size) return;
  const btn = $("settings-save");
  btn.disabled = true;
  const pushChanged = pendingSettings.has("browser_push");
  try {
    const data = applySettings(await api("/settings", {
      method: "PATCH",
      body: JSON.stringify({ values: Object.fromEntries(pendingSettings) }),
    }));
    renderSettings(data.rest, data.pushover);
    toast("Settings saved");
    if (pushChanged) setupPush().catch(() => {});
  } catch (e) {
    for (const [key, msg] of Object.entries(e.errors || {})) {
      const c = settingControls.get(key);
      if (!c) continue;
      c.row.classList.add("invalid");
      c.error.textContent = msg;
      c.error.hidden = false;
    }
    const first = Object.keys(e.errors || {}).map((k) => settingControls.get(k)).find(Boolean);
    first?.row.scrollIntoView({ block: "center", behavior: "smooth" });
    toast(e.errors ? "Some settings need fixing" : e.message);
    btn.disabled = false;
  }
};

// ------------------------------------------------------------------ push

function urlBase64ToUint8Array(base64) {
  const padding = "=".repeat((4 - (base64.length % 4)) % 4);
  const raw = atob((base64 + padding).replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
}

async function subscribePush(vapidPublicKey) {
  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(vapidPublicKey),
    });
  }
  // Re-sent on every app open, so an expired subscription heals itself.
  await api("/push/subscribe", { method: "POST", body: JSON.stringify(sub.toJSON()) });
}

async function setupPush() {
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) return;
  await navigator.serviceWorker.register("/sw.js"); // also needed for installing the app
  const btn = $("enable-push");
  btn.hidden = true;
  let push;
  try {
    push = await fetch(cfg.API_URL + "/config/push").then((r) => r.json());
  } catch {
    return;
  }
  if (!push.enabled) return; // browser notifications are switched off (⚙ Settings) or not set up
  if (Notification.permission === "granted") {
    subscribePush(push.vapidPublicKey).catch((e) => console.warn("push subscribe failed", e));
  } else if (Notification.permission === "default") {
    btn.hidden = false;
    btn.onclick = async () => {
      const perm = await Notification.requestPermission();
      if (perm === "granted") {
        await subscribePush(push.vapidPublicKey);
        btn.hidden = true;
        await api("/push/test", { method: "POST" });
        toast("Notifications enabled — a test is on its way");
      } else {
        toast("Notifications blocked. Enable them in Chrome site settings.");
      }
    };
  }
}

// Messages from the service worker (notification buttons / taps while the app is open)
navigator.serviceWorker?.addEventListener("message", async (e) => {
  const msg = e.data || {};
  if (msg.type === "tasks-changed") {
    Promise.all([loadTasks(), loadNewer(), loadFocus()]).catch(() => {});
  } else if (msg.type === "open-message" && msg.messageId) {
    requestCloseDrawer();
    await loadLatest({ stickToBottom: false }).catch(() => {});
    showMessage(Number(msg.messageId));
  } else if (msg.type === "open-task" && msg.taskId) {
    openTask(Number(msg.taskId));
  }
});

// ------------------------------------------------------------------ start

async function refreshAll() {
  await Promise.all([loadTasks(), loadLatest(), loadFocus()]);
}

// Registered once (startApp runs again after every login).
$("refresh").onclick = () => refreshAll().catch((e) => toast(e.message));
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") flushPendingDone();
  else if (started) refreshAll().catch(() => {});
});
window.addEventListener("pagehide", flushPendingDone);
window.addEventListener("resize", autosize);

async function startApp() {
  hideLoading();
  $("login").hidden = true;
  $("main").hidden = false;
  autosize();
  if (started) return;
  started = true;
  history.replaceState(null, "", location.pathname); // drop ?m= / ?task= / login params

  clearInterval(pollTimer);
  pollTimer = setInterval(() => {
    if (document.visibilityState === "visible") loadNewer().catch(() => {});
  }, 60_000);

  try {
    await Promise.all([loadTasks(), loadLatest(), loadFocus()]);
  } catch (e) {
    toast(e.message);
  }
  if (pendingMessageId) {
    showMessage(pendingMessageId);
    pendingMessageId = null;
  } else if (pendingTaskId) {
    openTask(pendingTaskId);
    pendingTaskId = null;
  }
  setupPush();
}
