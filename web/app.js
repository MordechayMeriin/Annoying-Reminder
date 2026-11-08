import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cfg = window.APP_CONFIG;
const supabase = createClient(cfg.SUPABASE_URL, cfg.SUPABASE_ANON_KEY);
const $ = (id) => document.getElementById(id);

const KIND_LABEL = { morning: "☀️ Morning plan", checkin: "⏰ Check-in", evening: "🌙 Evening review" };
const highlightTask = Number(new URLSearchParams(location.search).get("task")) || null;
let started = false;
let currentTab = "today";

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

function fmtTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
  if (sameDay) return `Today ${time}`;
  if (d.toDateString() === tomorrow.toDateString()) return `Tomorrow ${time}`;
  return d.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" }) + ` ${time}`;
}

// ------------------------------------------------------------------ auth

function showLogin() {
  started = false;
  $("main").hidden = true;
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

// ------------------------------------------------------------------ tabs

document.querySelectorAll(".tabs button").forEach((b) =>
  b.addEventListener("click", () => switchTab(b.dataset.tab))
);

function switchTab(tab) {
  currentTab = tab;
  document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  ["today", "tasks", "chat"].forEach((t) => ($(`tab-${t}`).hidden = t !== tab));
  if (tab === "chat") scrollChat();
}

// ------------------------------------------------------------------ tasks

function taskItem(t) {
  const li = document.createElement("li");
  li.className = "task" + (t.id === highlightTask ? " highlight" : "");
  const overdue = t.due_at && new Date(t.due_at) < new Date() && t.kind !== "meeting";
  const prio = t.priority === 3 ? "🚨 " : t.priority === 2 ? "❗ " : "";
  const icon = t.kind === "meeting" ? "📅 " : "";
  const recur = t.recurrence ? ` · 🔁 ${t.recurrence}` : "";

  li.innerHTML = `
    <div class="info">
      <div class="title" dir="auto"></div>
      <div class="meta ${overdue ? "overdue" : ""}"></div>
    </div>
    <div class="btns">
      <button class="snooze-btn" title="Snooze 1h">⏰</button>
      <button class="done-btn" title="Done">✓</button>
    </div>`;
  li.querySelector(".title").textContent = prio + icon + t.title;
  li.querySelector(".meta").textContent =
    (t.due_at ? (overdue ? "Overdue · " : "") + fmtTime(t.due_at) : "No date") + recur;

  li.querySelector(".done-btn").onclick = () => taskAction(t.id, "done");
  li.querySelector(".snooze-btn").onclick = () => taskAction(t.id, "snooze");
  return li;
}

function fill(listId, tasks, emptyText) {
  const ul = $(listId);
  ul.innerHTML = "";
  if (!tasks.length) {
    ul.innerHTML = `<li class="empty">${emptyText}</li>`;
    return;
  }
  tasks.forEach((t) => ul.appendChild(taskItem(t)));
}

async function taskAction(id, action) {
  try {
    await api(`/tasks/${id}/action`, { method: "POST", body: JSON.stringify({ action, minutes: 60 }) });
    toast(action === "done" ? "Done ✓" : "Snoozed for 1 hour");
    loadTasks();
  } catch (e) {
    toast(e.message);
  }
}

async function loadTasks() {
  const [today, upcoming, all] = await Promise.all([
    api("/tasks?scope=today"),
    api("/tasks?scope=upcoming&days=7"),
    api("/tasks?scope=open"),
  ]);
  fill("today-list", today, "Nothing for today 🎉");
  fill("upcoming-list", upcoming, "Nothing in the next 7 days");
  fill("all-list", all, "No open tasks");
}

// ------------------------------------------------------------------ chat + plan

function addMessage(m, pending = false) {
  const div = document.createElement("div");
  div.className = `msg ${m.role}` + (pending ? " pending" : "");
  div.dir = "auto";
  if (m.kind && KIND_LABEL[m.kind]) {
    const k = document.createElement("span");
    k.className = "kind";
    k.textContent = KIND_LABEL[m.kind];
    div.appendChild(k);
  }
  div.appendChild(document.createTextNode(m.content));
  $("messages").appendChild(div);
  return div;
}

function scrollChat() {
  window.scrollTo(0, document.body.scrollHeight);
}

async function loadMessages() {
  const msgs = await api("/messages?limit=60");
  $("messages").innerHTML = "";
  msgs.forEach((m) => addMessage(m));

  const todayStr = new Date().toDateString();
  const plan = [...msgs].reverse().find((m) => m.kind === "morning" && new Date(m.created_at).toDateString() === todayStr);
  const planEl = $("plan");
  if (plan) {
    planEl.innerHTML = `<div class="plan-title">☀️ Today's plan</div>`;
    const body = document.createElement("div");
    body.dir = "auto";
    body.textContent = plan.content;
    planEl.appendChild(body);
    planEl.hidden = false;
  } else {
    planEl.hidden = true;
  }
  if (currentTab === "chat") scrollChat();
}

$("chat-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = $("chat-input");
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  switchTab("chat");
  addMessage({ role: "user", content: text });
  const pending = addMessage({ role: "assistant", content: "…" }, true);
  scrollChat();
  $("chat-send").disabled = true;
  try {
    const res = await api("/chat", { method: "POST", body: JSON.stringify({ message: text }) });
    pending.remove();
    addMessage({ role: "assistant", content: res.reply });
    if (res.tasksChanged) loadTasks();
  } catch (err) {
    pending.textContent = "⚠️ " + err.message;
  } finally {
    $("chat-send").disabled = false;
    scrollChat();
  }
});

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

navigator.serviceWorker?.addEventListener("message", (e) => {
  if (e.data?.type === "tasks-changed") loadTasks();
});

// ------------------------------------------------------------------ start

async function startApp() {
  $("login").hidden = true;
  $("main").hidden = false;
  if (started) return;
  started = true;
  if (location.search.includes("code=") || location.hash.includes("access_token")) {
    history.replaceState(null, "", location.pathname + (highlightTask ? `?task=${highlightTask}` : ""));
  }
  $("refresh").onclick = () => Promise.all([loadTasks(), loadMessages()]).catch((e) => toast(e.message));
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") loadTasks().catch(() => {});
  });
  try {
    await Promise.all([loadTasks(), loadMessages()]);
  } catch (e) {
    toast(e.message);
  }
  setupPush();
}
