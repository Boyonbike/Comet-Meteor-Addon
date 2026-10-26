# Anti Coords

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **anti-coords**. Open **Meteor → Comet → anti-coords**.

Enable **Comet → anti-coords** to make your coordinates as hard as possible to work out from what you show.

**Textures**, adapted from [NoTextureRotations](https://github.com/rfresh2/NoTextureRotations) by rfresh2. Grass, stone, sand and many other blocks pick a rotated or alternative texture from their own coordinates, and flowers, grass, bamboo and dripstone pick a small offset the same way. No world seed is needed: a screenshot or video showing enough of these blocks can be checked against positions until one fits.

- **mode**: **Fixed** (default) gives every block the same rotation and offset, so they reveal nothing. **Keyed** keeps vanilla's variety: each block's place within a 256-block square is hashed with SipHash-2-4 under a secret key, with a separate key derived for each server and dimension. Without the key the textures reveal nothing about where you are; even with it, only your place within that square. The key is saved in `meteor-client/comet/anti-coords.key`, so textures stay the same between sessions. Delete it to get a new one.
- **hide-rotations** and **hide-offsets** both default on. Your own movement, and the integrated server in singleplayer, still collide with the real bamboo and dripstone offsets, so you stay in sync with the server. Your crosshair targets the offsets you see.
- Toggling the module or changing these settings rebuilds the loaded chunks.
- Meteor's **no-render → no-texture-rotations** is a simpler version that also moves your collisions; leave it off while using Anti Coords.

**Screenshots.** With **clean-screenshots** on (default), the screenshot key saves only the world. The capture is taken after the world is drawn and before anything 2D, so it leaves out vanilla's HUD, F3, chat, open menus and toasts, Meteor's HUD and its waypoint labels, nametags and logout spots, and any other mod's overlay. The hand, block outline and vanilla name tags are hidden as F1 hides them. F1 on its own still shows Meteor's waypoint labels, nametags and logout spots. The HUD disappears for the one frame being captured. Outside a world, screenshots work as usual.

Automated tests check the key hashing against Guava's SipHash-2-4 and every Anti Coords hook against Minecraft 1.21.11; in-game rendering still needs verification.
