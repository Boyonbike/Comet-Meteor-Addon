# Flight Dashboard

[Documentation index](../README.md) · [Project README](../../README.md)

Flight Dashboard serves one read-only live page for [Highway Fly](highway-fly.md) and
[Auto Boat Fly](auto-boat-fly.md) to any phone, tablet or PC on the same network. Enable the module, then open
the `http://<this-pc-ip>:8734` URL from chat or press **Copy URL** on its screen. It is off by default and can
stay on while both flight modules are off or Minecraft is at the main menu.

The page follows whichever module you are using: it shows Auto Boat Fly while that runs, Highway Fly while that
runs or recovers, and otherwise keeps the last one shown. The header, panels, event feed and settings list switch
with it. Both flight modules show the dashboard's status with a **Settings** shortcut on their screens.

## Settings

- **port** (default `8734`) and **bind-address** (default `0.0.0.0`, every interface; `127.0.0.1` keeps it on
  this PC). Allow Java through the operating system firewall on private networks; the page needs no internet
  connection.
- **access-token** can require a shared secret. When set, open the page with `?token=…` or send the
  `X-Highway-Token` header; the page removes the token from the visible address once read. Leave it blank for
  an open LAN page. Webhook URLs, passwords and tokens are always masked on the page.
- **push-interval-ms** (default 250) sets how often the page updates. Updates arrive over a server-sent event
  stream that reconnects automatically; the server keeps only the newest snapshot.
- **max-clients** (default 8) limits simultaneous browser tabs, and **debug** logs connections and failures.

## What it shows

Both modes show the current state, action and detail; trip and active time; player health, air, armour, food,
coordinates, dimension, server, ping and speed; lobby recovery and Auto Set Home state with home history;
webhook status; an event feed of trip events and phase changes (recorded even when the Discord webhook is off);
and every setting of the module and its helpers with current values.

- **Highway Fly:** route progress, remaining distance, target and active altitude; detour, rubberband and walking
  counters; the local path preview; portal, lava-safety and elytra state; and modules paused for the trip.
- **Auto Boat Fly:** travel heading against current facing, boat speed and speed mode, distance travelled,
  driver seat and server corrections; eGaps, food, eating and the health and eGap logout limits; and which
  helpers are on.

The page is strictly read-only: it exposes no controls and no chat commands. It is a local convenience for one
player's client, stops when Minecraft closes, and shows only what that client knows.

## Upgrading

Highway Fly's and Boat Fly's old **Dashboard** sections move here automatically. If either dashboard was on,
Flight Dashboard starts enabled, and Highway Fly's port, address and token win over the old boat dashboard's
(port 8735). Update bookmarks that pointed at the boat page.
