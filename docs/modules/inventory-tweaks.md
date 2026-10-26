# Inventory Tweaks

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **inventory-tweaks**. Open **Meteor → Comet → inventory-tweaks**.

Use Meteor's existing **Misc → Inventory Tweaks** and its Steal/Dump controls. Comet schedules transfer clicks on the client thread instead of sleeping on a background worker. Initial, normal and random delays retain their millisecond settings, with actions occurring on the next eligible frame or client tick. A stalled frame does not cause overdue clicks to burst all at once.

Each transfer belongs to one exact screen, menu, player and world. Closing/switching the container, leaving the world, disabling the module, starting a manual sort or picking up an item on the cursor cancels pending work. The source stack, filter and slot pickup permission are checked again immediately before a click, including delayed rotation callbacks. Repeated inventory-content packets coalesce into the current job. Zero-delay transfers share a maximum of eight actions per client tick across frames and requests.

Steal-drop works with or without **drop-backwards**; the original non-backwards path did not issue the drop. A transfer waiting on a drop-backwards rotation is abandoned after one second if the rotation never happens, so the job cannot stall. While riding (when Minecraft sends no rotation packets) drop-backwards drops without turning around. Live Minecraft testing remains needed for your server's click acceptance, repeated container changes and sort/transfer interactions.
