# Live dashboard

[Documentation index](../README.md) · [Project README](../../README.md)

Highway Fly's **Dashboard** settings serve a read-only live page for any phone, tablet or PC on the same network. Turn on **dashboard-enabled**, then open the `http://<this-pc-ip>:8734` URL from chat, or press **Copy URL** on Highway Fly's screen. The dashboard is off by default and can stay on while Highway Fly is off or Minecraft is at the main menu. The former separate Highway Dashboard module migrates into these settings.

- Dashboard settings choose **dashboard-bind-address** (default `0.0.0.0`, every interface), **dashboard-port** (default `8734`) and **dashboard-max-clients** (default 8 live browser streams). Allow Java through the operating system firewall on private networks; the page itself needs no internet connection.
- **dashboard-access-token** can require a shared secret; when set, open the page with `?token=…` or send the `X-Highway-Token` header. Leave it blank for an open LAN page. Credentials in Highway Fly settings are always masked on the page.
- **dashboard-push-interval-ms** sets the update interval (default 250) for browser updates. The page receives snapshots over a server-sent event stream and reconnects automatically; the server keeps only the newest snapshot and drops older ones.
- The page shows the current phase, action and detail; trip and active time; route progress, remaining distance, target and altitude; player health, air, armour, food, coordinates, dimension, server, ping and speed; detour, rubberband and walking counters; the local path preview; portal, lava-safety, elytra and home-recovery state; webhook status; and an event feed of trip events and phase changes. The feed records webhook events even when the Discord webhook itself is off.
- Every Highway Fly setting is listed with its current value and visibility, grouped as in the module. The page is strictly read-only: it exposes no controls and no chat commands.

The dashboard is a local convenience for one player's client. It stops when Minecraft closes, and it shows only what that client knows.
