# Waypoint Delete

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **waypoint-delete**. Open **Meteor → Comet → waypoint-delete**.

A separate module, **waypoint-delete**. Look at a waypoint until its name and distance appear, then press **delete-key** to delete it. The default is the middle mouse button; any key or mouse button, with modifiers, can be bound. Chat shows what was deleted and where.

- **Meteor waypoints** need Meteor's **waypoints** module on. They follow its rule for when a name shows: within **text-render-distance** pixels of the crosshair, not faded out up close, and within the waypoint's max visible distance. If several names show, the one closest to the crosshair is deleted.
- **Xaero waypoints:** the one Xaero highlights in the world, among your own waypoints (deathpoints included). It is removed the same way as from Xaero's menu and saved. Server waypoints and other mods' waypoints (such as Waystones) are left alone.
- A Xaero marker added by **xaero-waypoints** deletes the Meteor waypoint behind it.
- If a Meteor and a Xaero waypoint both show details, the one nearer the crosshair is deleted. Nothing happens while a screen is open or the HUD is hidden (F1).
- When something is deleted, the press is used up: middle click won't also pick a block or trigger Meteor's middle-click modules. When nothing is targeted, the press works as normal.
- There is no undo. Recreate the waypoint from the coordinates in chat.
