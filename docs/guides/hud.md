# HUD elements

[Documentation index](../README.md) · [Project README](../../README.md)

Open Meteor's HUD editor and add elements from the **Comet** group. They look like Meteor's own text elements such as FPS and Speed: each label takes the first of the HUD's **text-colors** and its value the second, and they have the same **shadow**, **alignment**, **border**, **custom-scale** and **background** settings (background off by default).

- **adaptive-elytra-fly-engine**: Adaptive Elytra Fly's state, output, attainable maximum, boost ramps, setbacks and the simulated server glide. Shown while the module is on.
- **auto-set-home-timer**: Auto Set Home timing mode, next attempt countdown (or override-confirmation countdown), home name, the fixed interval or tracked server cooldown, the cooldown buffer, and any retry hold after a refusal that stated no usable countdown. Remains visible with a paused label once home timing/history is loaded, including while disconnected; shows a sample in the editor.
- **highway-fly-status**: Highway Fly's current phase and action — taking off, climbing, flying, waiting, blocked, walking, landing or done, with cruising, routing around an obstacle, maneuvering around terrain, chunk waiting or lobby recovery called out — plus remaining distance and the target. Hidden while the module is off; shows a sample in the editor.
- **highway-fly-runtime**: active running time and total trip time for the current Highway Fly trip. Active time stops during lobby recovery and reconnect waits; total trip time runs from the start of the trip and continues across them. Hidden while the module is off; shows a sample in the editor.
- **tp-home-countdown**: Chat Tools home/TP warmups, cooldowns, outgoing request expiry and incoming request expiry. Hidden when idle; uses the same countdown data as the Chat Tools window.
- **speed-3d**: your measured total 3D speed, climbing and diving included, in blocks/second or km/h with 1–40 ticks of smoothing, plus a **Maneuvering ≤ 200** line while Adaptive Elytra Fly's maneuver mode caps it.
- **player-speed**: your measured speed in blocks/second or km/h, optionally including vertical movement, with 1–40 ticks of smoothing. It uses real time, so Timer cannot skew it, and teleports such as `/home` never show as a spike. It replaces the former **player-speed-hud** module.
- **dub-counter**: dubs (ordinary chest blocks ÷ 2) and the chest and loaded-chunk totals from the **dub-counter** module. Shown while the module is on; the editor reminds you to enable it.
