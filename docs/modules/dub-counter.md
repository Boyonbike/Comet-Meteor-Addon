# Dub Counter

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **dub-counter**. Open **Meteor → Comet → dub-counter**.

Enable **dub-counter** to count the ordinary chest blocks in every chunk your client has loaded, shown as dubs (double chests): two chest blocks make one dub, so 25 chests read **12.5**. Trapped chests, copper chests, barrels and ender chests do not count. The count shows beside the module name in Meteor's active-modules list, and the **dub-counter** HUD element adds the chest and loaded-chunk totals.

The count refreshes five times a second from the chunks' block-entity lists, without reading blocks or allocating anything per chest. It resets when you leave a world or disable the module. Adapted from Majkey's DubCounter 1.0.0 addon with the author's permission; remove `DubCounter-1.0.0-mc26.1.2.jar` when you install Comet, because both register **dub-counter** and Meteor keeps whichever loads last.
