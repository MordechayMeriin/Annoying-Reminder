// Service worker: shows push notifications, handles their buttons (▶ Start 5 min / ✓ Done) without
// opening the app, and opens the chat at the right message when a notification is tapped.
//
// Push payload: { title, body, url, priority, api, messageId?, taskId?, token?, actions?: [{action, title}] }
// The server chooses the buttons and their labels (Hebrew or English).

const BUTTON_ACTIONS = new Set(["start", "done", "snooze"]); // snooze: notifications sent before ▶ Start
const START_MIN = 5;

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { title: "Reminder", body: event.data ? event.data.text() : "" };
  }

  const options = {
    body: data.body || "",
    icon: "/icon-192.png",
    badge: "/icon-192.png",
    data,
    // One notification per task (a newer check-in replaces the older one); group messages stack.
    tag: data.taskId ? `task-${data.taskId}` : data.messageId ? `msg-${data.messageId}` : `msg-${Date.now()}`,
    renotify: true,
    requireInteraction: (data.priority || 1) >= 2,
    vibrate: (data.priority || 1) >= 2 ? [200, 100, 200, 100, 400] : [200],
  };
  if (data.taskId && data.token && Array.isArray(data.actions)) {
    // Chrome on Android shows at most two.
    const max = (self.Notification && Notification.maxActions) || 2;
    options.actions = data.actions.filter((a) => BUTTON_ACTIONS.has(a.action)).slice(0, max);
  }
  event.waitUntil(self.registration.showNotification(data.title || "Reminder", options));
});

self.addEventListener("notificationclick", (event) => {
  const n = event.notification;
  const data = n.data || {};
  n.close();

  if (BUTTON_ACTIONS.has(event.action) && data.token && data.api) {
    const minutes = event.action === "start" ? START_MIN : event.action === "snooze" ? 60 : undefined;
    event.waitUntil(
      fetch(`${data.api}/actions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: data.token, action: event.action, minutes }),
      })
        .then((r) => {
          if (!r.ok) throw new Error(`HTTP ${r.status}`);
          return broadcast({ type: "tasks-changed" });
        })
        .catch(() =>
          self.registration.showNotification("Couldn't update the task", {
            body: "Open the app and try again.",
            icon: "/icon-192.png",
            data: { url: data.url || "/", messageId: data.messageId },
          })
        )
    );
    return;
  }

  // Tapping the body = reply: open the chat at this message with the reply box focused.
  // (Chrome on Android has no inline text reply for web notifications.)
  const url = data.messageId ? `/?m=${data.messageId}` : data.url || "/";
  event.waitUntil(openApp(url, data));
});

async function broadcast(message) {
  const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  clients.forEach((c) => c.postMessage(message));
}

async function openApp(url, data) {
  const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  const client = clients.find((c) => new URL(c.url).origin === self.location.origin);
  if (client) {
    await client.focus();
    // The app is already open: jump to the message without reloading.
    if (data.messageId) client.postMessage({ type: "open-message", messageId: data.messageId });
    else if (data.taskId) client.postMessage({ type: "open-task", taskId: data.taskId });
    else if ("navigate" in client) await client.navigate(url);
    return;
  }
  return self.clients.openWindow(url);
}
