# Auto Set Home

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **auto-set-home**.

Enable **Auto Set Home** independently to update a named home without Highway Fly, or let Highway Fly request it during trips. Its own settings control the home, timing, cooldown buffer, retry delay and override confirmation. While the helper can act (some servers only allow `/sethome` during flight), it sends `/sethome <home>` using one of two timing modes, and when the server asks whether to replace the existing home, clicks the matching confirmation button for you.

- **home** (default `test`) is the home name to move. If the server asks to override it, the home must already exist.
- **timing** (default **Auto detect**) chooses how the next command is scheduled. **Auto detect** reads the server's sethome cooldown from chat and waits until that cooldown and **cooldown-buffer-seconds** have elapsed. **Fixed timer** sends at the configured interval and deliberately ignores detected cooldowns; choose an interval at least as long as the server's cooldown.
- **interval-seconds** (default 60, range 5–3600) is the fixed-timer schedule, and in Auto detect it is the fallback when the server has not stated a usable delay. The first command goes out on the next eligible tick only when no saved deadline remains; reconnecting or restarting does not reset the timer. In Auto detect, when a stated cooldown ends, the next command goes out once the buffer expires, even if that is sooner than this interval or a GUI was open throughout the countdown. A detected delay also estimates the cooldown after each confirmed home update.
- **cooldown-buffer-seconds** (default 10, range 0–120, Auto detect only) is the extra wait after the server's stated cooldown ends, so the command is not sent on the exact boundary.
- **retry-delay-seconds** (default 60, range 0–3600, Auto detect only) is how long to wait after the server refuses a command without a usable countdown — for example “wait 0m 0sec” — before trying again. Set it to the server's real cooldown if the server keeps reporting zero. 0 disables the extra hold; the buffer and a one-second minimum retry spacing still apply.
- **override** (default on) clicks the confirmation button. Turn it off to confirm the prompt yourself.
- **dialog-match** (default `home`) must appear in the confirmation dialog's title or body, ignoring case, so unrelated dialogs are left alone.
- **button-match** (default `override`) is the text the button label must contain. **Cancel** does not match it.
- **timeout-seconds** (default 10) is how long to wait for the dialog after each command. If no matching dialog appears, the module sends again at the next interval, or when the server's sethome cooldown ends.

The helper recognises `You have to wait 4m 54s to teleport again.` only while a sethome request is pending. It learns the full delay from the previous successful replacement time plus the remaining wait reported by the server. `0m 00s` is still a refusal: automatic mode starts a fresh buffer and waits for the longer of that buffer or **retry-delay-seconds**. Fixed mode continues at the chosen interval regardless of server cooldowns; pending dialogs/responses and open screens prevent overlapping requests.

The existing **Override** dialog click remains automatic. Clicking is not success: `Successfully moved Home: Test` must match the requested home before a replacement is recorded. The dialog timeout uses real seconds; after a click the helper waits up to 15 seconds for the reply. Home names use the same single-argument validation as Chat Tools.

Timing uses Unix timestamps saved atomically in `auto-set-home-state.json` in the Meteor folder, separated by player UUID and server. Commands, cooldown replies and confirmed replacements update that file. Disabling, disconnecting and restarting preserve deadlines; missed intervals never produce a burst. Home updates pause during lobby recovery; while Highway Fly is active they run only on the trip's server and dimension. Independent updates also work while walking or standing still. Changing the home name retains the server cooldown; editing the interval recalculates from the last command's original timestamp.

The dashboard and **auto-set-home-timer** HUD show separate cooldown and buffer countdowns, plus interval, retry and confirmation states. Paused countdowns continue using real time. The dashboard retains the latest **100 confirmed home replacements per account/server**, newest first, with home, local confirmation time (timezone labelled), coordinates and dimension. Coordinates are captured at the override click, or command send when no override is needed. Saved history remains available while flight is stopped or disconnected and is restored for the account's latest server before joining.

## Independent and trip-managed activation

Enable this helper independently in Comet or allow Highway Fly to request it through Integrations. Stopping Highway Fly releases its request and preserves independent activation. **Keep enabled independently** retains a trip-managed helper after the trip; explicitly disabling one also disables its Highway Fly integration. See [Highway Fly](highway-fly.md).
