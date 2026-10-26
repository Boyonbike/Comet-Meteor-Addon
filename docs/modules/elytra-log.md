# Elytra Log

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **elytra-log**. Open **Meteor → Comet → elytra-log**.

A separate module, **elytra-log**. While you're flying, once the elytra you're wearing has **durability** or less left (default 20) and there's no spare elytra in your inventory, it logs you out. What's left is what you glide down with after logging back in, about one point per second of gliding.

- A spare counts only if it has more than **durability** left, so you can swap it on before being logged out. It looks at the hotbar and main inventory, not the offhand. Any item with the glider component counts as an elytra, and one that cannot be damaged never wears out.
- Comet no longer swaps elytras for you. Meteor's Elytra Fly has its own **elytra-replace** and **replace-durability** options, which work while Elytra Fly is on.
- It only acts while you're gliding, or in the air with ElytraFly's Packet mode. Standing or walking with a worn-out elytra doesn't log you out.
- **warning-margin** (default 10, 0 disables) warns in chat once when the worn elytra is within that much durability of logging you out with no spare.
- After logging you out it turns itself off, so opening the elytra after you rejoin doesn't log you out again. It also turns off Meteor's AutoReconnect, which would put you straight back in the air.
- You rejoin where you logged out, in mid-air. Open the elytra straight away and glide down.
- For Baritone elytra flights, keep **durability** above Baritone's `elytraMinimumDurability` (default 5), or Baritone lands before you're logged out.
