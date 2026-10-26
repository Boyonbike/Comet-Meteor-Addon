# Fast Storage ESP

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **fast-storage-esp**. Open **Meteor → Comet → fast-storage-esp**.

Enable **fast-storage-esp** in Meteor's **Comet** category for shader outlines and glow around storage. This uses the actual container models, including double chests, with through-wall rendering. Enabling it switches off Meteor's original Storage ESP to avoid doing the same rendering twice. Its settings are independent; configure the colours and storage selection in this module.

Unchanged models stay in GPU buffers, grouped into 8-block cells. Offscreen groups are skipped; a separate storage depth buffer rejects hidden storage surfaces while keeping terrain transparent to ESP. Nearby fading happens in the shader, so moving the camera or player does not rebuild the stash. Storage changes and lid/shulker animation poses are checked five times a second, and changed geometry is prepared over several frames. New stashes appear progressively while their cache fills.

The usual **storage-blocks**, **shape-mode**, **fill-opacity**, **width**, **glow-multiplier**, colours, **fade-distance**, **tracers**, **hide-opened** and **opened-color** controls are available. Interaction history lasts for the current world and dimension, including after you fly away and its chunks unload, up to 65,536 containers (the least recently opened are forgotten first). Changing dimension or server, disabling the module or **Clear Opened Containers** resets it. Unloading chunks, leaving a world, disabling the module and reloading resources release the render caches; a resource reload keeps the interaction history. If the storage shader cannot be created, the module turns itself off with a chat error instead of failing every frame.

- **max-distance** defaults to **0**, which includes all loaded storage. Set a range such as **128** or **256** blocks to reduce the work further.
- **rebuild-budget-ms** defaults to **2** milliseconds per frame, with a maximum of 128 models prepared per frame. Lowering it spreads loading over more frames; a single model or GPU upload can exceed the time target.
- Tracers default off. Wider outlines and higher resolutions still increase shader cost.

Automated checks cover cache invalidation, camera transforms and retained GPU buffer ownership, including the shared-buffer crash regression. Live Minecraft testing is still needed to measure FPS on a large stash and check the shader appearance with your graphics setup.
