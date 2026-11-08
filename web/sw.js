// Service worker: shows push notifications and handles the Done / Snooze buttons
// without opening the app.

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
    tag: data.taskId ? `task-${data.taskId}` : `msg-${Date.now()}`,
    renotify: true,
    requireInteraction: (data.priority || 1) >= 2,
    vibrate: (data.priority || 1) >= 2 ? [200, 100, 200, 100, 400] : [200],
  };
  if (data.taskId && data.token) {
    options.actions = [
      { action: "done", title: "✓ Done" },
      { action: "snooze", title: "⏰ Snooze 1h" },
    ];
  }
  event.waitUntil(self.registration.showNotification(data.title || "Reminder", options));
});

self.addEventListener("notificationclick", (event) => {
  const n = event.notification;
  const data = n.data || {};
  n.close();

  if ((event.action === "done" || event.action === "snooze") && data.token && data.api) {
    event.waitUntil(
      fetch(`${data.api}/actions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: data.token, action: event.action, minutes: 60 }),
      })
        .then((r) => {
          if (!r.ok) throw new Error(`HTTP ${r.status}`);
          return broadcast({ type: "tasks-changed" });
        })
        .catch(() =>
          self.registration.showNotification("Couldn't update the task", {
            body: "Open the app and try again.",
            icon: "/icon-192.png",
            data: { url: data.url || "/" },
          })
        )
    );
    return;
  }

  event.waitUntil(openApp(data.url || "/"));
});

async function broadcast(message) {
  const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  clients.forEach((c) => c.postMessage(message));
}

async function openApp(url) {
  const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  for (const c of clients) {
    if ("focus" in c) {
      await c.focus();
      if ("navigate" in c) await c.navigate(url);
      return;
    }
  }
  return self.clients.openWindow(url);
}
