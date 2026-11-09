import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

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
};
const DAY_RESET_HOUR = 4; // matches DAY_RESET_TIME on the server (plan card only)
const PAGE = 50;

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

// ------------------------------------------------------------------ helpers

function toast(text, ms = 2500) {
  const t = $("toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (t.hidden = true), ms);
}

async function api(path, options = {}) {
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
    throw new Error(body.detail || `HTTP ${res.status}`);
  }
  return res.json();
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

function chatDayStart(now = new Date()) {
  const s = new Date(now);
  s.setHours(DAY_RESET_HOUR, 0, 0, 0);
  if (s > now) s.setDate(s.getDate() - 1);
  return s;
}

// ------------------------------------------------------------------ auth

function showLogin() {
  started = false;
  $("main").hidden = true;
  closeDrawer();
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
    (m.task_ids || []).forEach((id) => head.appendChild(taskChip(id, id === m.task_id ? m.task_title : null)));
    div.appendChild(head);
  }
  const body = el("div", "msg-body", m.content);
  body.dir = "auto";
  div.appendChild(body);
  if (m.created_at) div.appendChild(el("div", "msg-time", hhmm(new Date(m.created_at))));
  return div;
}

function renderMessages() {
  const box = $("messages");
  box.textContent = "";
  let lastDay = "";
  for (const m of messages) {
    const d = new Date(m.created_at);
    if (d.toDateString() !== lastDay) {
      lastDay = d.toDateString();
      box.appendChild(el("div", "day-sep", fmtDay(d)));
    }
    box.appendChild(messageEl(m));
  }
  $("load-earlier").hidden = !hasMore;
  renderPlan();
}

function renderPlan() {
  const start = chatDayStart();
  const plan = [...messages].reverse().find((m) => m.kind === "morning" && new Date(m.created_at) >= start);
  $("plan").hidden = !plan;
  if (!plan) return;
  $("plan-preview").textContent = plan.content.split("\n").find((l) => l.trim()) || "";
  $("plan-body").textContent = plan.content;
}

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
  input.style.height = Math.min(input.scrollHeight, 160) + "px";
}

$("chat-input").addEventListener("input", autosize);
$("chat-input").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    $("chat-form").requestSubmit();
  }
});

$("chat-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = $("chat-input");
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  autosize();
  const box = $("messages");
  box.appendChild(messageEl({ role: "user", content: text, kind: "chat" }));
  const pending = messageEl({ role: "assistant", content: "…", kind: "chat" });
  pending.classList.add("pending");
  box.appendChild(pending);
  scrollToBottom();
  $("chat-send").disabled = true;
  try {
    const res = await api("/chat", { method: "POST", body: JSON.stringify({ message: text }) });
    await loadLatest(); // real ids, task links and any events
    if (res.tasksChanged) loadTasks().catch(() => {});
  } catch (err) {
    pending.querySelector(".msg-body").textContent = "⚠️ " + err.message;
    pending.classList.remove("pending");
  } finally {
    $("chat-send").disabled = false;
    scrollToBottom();
  }
});

// ------------------------------------------------------------------ tasks drawer

function openDrawer() {
  $("drawer").hidden = false;
  $("drawer-backdrop").hidden = false;
  document.body.classList.add("drawer-open");
}

function closeDrawer() {
  $("drawer").hidden = true;
  $("drawer-backdrop").hidden = true;
  document.body.classList.remove("drawer-open");
  detailTaskId = null;
}

function showDrawerTab(tab) {
  drawerTab = tab;
  detailTaskId = null;
  $("task-detail").hidden = true;
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
$("drawer-close").onclick = closeDrawer;
$("drawer-backdrop").onclick = closeDrawer;
$("drawer-back").onclick = () => showDrawerTab(drawerTab);
document.querySelectorAll("#drawer-tabs button").forEach((b) => (b.onclick = () => showDrawerTab(b.dataset.tab)));
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !$("drawer").hidden) closeDrawer();
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
  const prio = t.priority === 3 ? "🚨 " : t.priority === 2 ? "❗ " : "";
  const icon = t.kind === "meeting" ? "📅 " : "";
  const info = el("button", "info");
  info.type = "button";
  const title = el("div", "title", prio + icon + t.title);
  title.dir = "auto";
  const { text, overdue } = taskMeta(t);
  info.append(title, el("div", "meta" + (overdue ? " overdue" : ""), text));
  info.onclick = () => openTask(t.id);

  const btns = el("div", "btns");
  const snooze = el("button", "snooze-btn", "⏰");
  snooze.title = "Snooze 1h";
  snooze.onclick = () => taskAction(t.id, "snooze");
  const done = el("button", "done-btn", "✓");
  done.title = "Done";
  done.onclick = () => taskAction(t.id, "done");
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
  const [today, upcoming, all] = await Promise.all([
    api("/tasks?scope=today"),
    api("/tasks?scope=upcoming&days=7"),
    api("/tasks?scope=open"),
  ]);
  tasksById = new Map(all.map((t) => [t.id, t]));
  fill("today-list", today, "Nothing for today 🎉");
  fill("upcoming-list", upcoming, "Nothing in the next 7 days");
  fill("all-list", all, "No open tasks");
  const badge = $("task-count");
  badge.textContent = today.length;
  badge.hidden = !today.length;
}

async function taskAction(id, action) {
  try {
    await api(`/tasks/${id}/action`, { method: "POST", body: JSON.stringify({ action, minutes: 60 }) });
    toast({ done: "Done ✓", snooze: "Snoozed for 1 hour", cancel: "Cancelled" }[action]);
    await Promise.all([loadTasks(), loadLatest()]);
    if (detailTaskId === id) openTask(id);
  } catch (e) {
    toast(e.message);
  }
}

async function openTask(id) {
  openDrawer();
  detailTaskId = id;
  $("tab-today").hidden = $("tab-all").hidden = true;
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
$("detail-done").onclick = () => taskAction(detailTaskId, "done");
$("detail-snooze").onclick = () => taskAction(detailTaskId, "snooze");
$("detail-cancel").onclick = () => {
  if (confirm("Cancel this task?")) taskAction(detailTaskId, "cancel");
};

// ------------------------------------------------------------------ push

function urlBase64ToUint8Array(base64) {
  const padding = "=".repeat((4 - (base64.length % 4)) % 4);
  const raw = atob((base64 + padding).replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
}

async function subscribePush() {
  const reg = await navigator.serviceWorker.ready;
  const { vapidPublicKey } = await fetch(cfg.API_URL + "/config/push").then((r) => r.json());
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
  await navigator.serviceWorker.register("/sw.js");
  const btn = $("enable-push");
  if (Notification.permission === "granted") {
    btn.hidden = true;
    subscribePush().catch((e) => console.warn("push subscribe failed", e));
  } else if (Notification.permission === "default") {
    btn.hidden = false;
    btn.onclick = async () => {
      const perm = await Notification.requestPermission();
      if (perm === "granted") {
        await subscribePush();
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
    Promise.all([loadTasks(), loadLatest()]).catch(() => {});
  } else if (msg.type === "open-message" && msg.messageId) {
    closeDrawer();
    await loadLatest({ stickToBottom: false }).catch(() => {});
    showMessage(Number(msg.messageId));
  } else if (msg.type === "open-task" && msg.taskId) {
    openTask(Number(msg.taskId));
  }
});

// ------------------------------------------------------------------ start

async function refreshAll() {
  await Promise.all([loadTasks(), loadLatest()]);
}

async function startApp() {
  $("login").hidden = true;
  $("main").hidden = false;
  if (started) return;
  started = true;
  history.replaceState(null, "", location.pathname); // drop ?m= / ?task= / login params

  $("refresh").onclick = () => refreshAll().catch((e) => toast(e.message));
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") refreshAll().catch(() => {});
  });
  setInterval(() => {
    if (document.visibilityState === "visible") loadLatest().catch(() => {});
  }, 60_000);

  try {
    await Promise.all([loadTasks(), loadLatest()]);
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
