# Inventory Manager — design

Status: proposed · Tree: 26.2 first, then 26.1.2 / 1.21.11 · Date: 2026-11-01
Builds on: `research_notes/Meteor coordination architecture review/` (Oct 29). Read `comet_recommendations.md` first.

## 1. Problem

Modules write to the inventory in parallel, each in its own way, with no shared idea of who owns what.

Today in 26.2, 16 source files change inventory or hotbar state, using four different mechanisms:

| Mechanism | Who uses it | Ownership today |
|---|---|---|
| `InvUtils.swap(slot, swapBack)` / `swapBack()` | AutoTool/AutoWeapon mixins, BlockCoat, SmartSponge | **Ownerless.** Meteor keeps one static `previousSlot`, so one caller can clear or consume another caller's restore. |
| `inventory.setSelectedSlot()` (+ `ensureHasSentCarriedItem`) | StickySlot (Scaffold/BlockCoat), Speedmine (×3), CometScaffold, MinecraftCombatClient | Local per module. StickySlot and CombatInventoryAdapter each restore only if the selection is unchanged. Nobody else knows. |
| Raw `ServerboundSetCarriedItemPacket` | SpeedmineInventory, ClientPacketSink (combat) | Server slot can differ from client slot. Only Speedmine tracks it. |
| `InvUtils.move()` / `handleContainerInput(SWAP)` / `drop()` | AutoReplenishMixin, InventorySlotLoan, AdaptiveElytraFly, AutoWasp, HighwayFly (×4), BaritoneElytra, InventoryTweaks | InventorySlotLoan has 2 owners (AutoTool, AutoWeapon). Everyone else is a free-for-all. |

The coordination that exists is a set of **blanket guards**: AutoReplenish, AutoWasp chest gear and HighwayFly recovery all `return` whenever *any* loan or deferred weapon attack exists. Two results:

- **Over-blocking.** Continuous AutoTool mining keeps the loan alive, which pauses *all* replenishing, even for unrelated slots.
- **Under-blocking.** AdaptiveElytraFly, Speedmine, Scaffold, BlockCoat, SmartSponge and combat don't check the guards, so they can swap the hotbar slot out from under each other or restore a slot that someone else now owns.

Priority exists only locally (`SpeedmineInventory.Priority` NORMAL/EATING/EXTERNAL, combat's `CleanupPriority`). Nothing decides between modules.

## 2. Goals and non-goals

**Goals**
1. One owner-aware place where every Comet inventory action is **requested** with a priority, not executed directly.
2. Fine-grained **resource reservations** (a slot, the selected hotbar slot, the cursor). Unrelated work runs side by side, and only real conflicts wait.
3. **Safe preemption.** A totem pop or a falling elytra swap jumps the queue at a safe boundary, and it never interrupts a half-finished cursor transaction.
4. **Proactive replenishment.** Modules declare what they will need (`StockDemand`). The manager stages it in the hotbar *before* it runs out, during quiet ticks.
5. **Explainable.** Every wait has a reason the user can see ("Replenish waiting: slot 18 loaned to Auto Tool").
6. Pure, unit-testable core, with Minecraft behind a backend interface (same pattern as `CombatInventoryAdapter` / `CombatClient`).

**Non-goals**
- Controlling unmodified third-party addons. The manager can only *detect* their writes and invalidate its snapshots (see §8).
- Replacing `CombatScheduler`. Combat keeps its risk and footprint semantics. It bridges its hotbar resource to the manager (Phase 5).
- Shulker or ender-chest restocking (possible later on top of the same Stock planner).

## 3. Architecture

```
            ┌──────────── modules / mixins (clients) ────────────┐
            │ Scaffold  CrystalCombat  AutoTool  Replenish  Elytra│
            └───────┬───────────────┬───────────────┬────────────┘
             request│/lease     declare StockDemand │  tryNow (sync fast path)
                    ▼               ▼               ▼
  ┌────────────────────────── InventoryManager (core, pure) ─────────────────────────┐
  │ RequestQueue (priority + ageing) │ ResourceTable (leases) │ StockPlanner          │
  │ SelectionArbiter (hotbar owner + restore chain) │ TickBudget │ Context/generation │
  │ AuditLog / status strings                                                         │
  └───────────────────────────────┬──────────────────────────────────────────────────┘
                                  │ InventoryBackend (per-MC-version adapter)
                                  ▼
            handleContainerInput · setSelectedSlot · carried-item packet · menu slot map
```

- **Package:** `dev.tntcoating.core.inventory` (core, no `net.minecraft` imports), plus `dev.tntcoating.core.inventory.mc` (backend, event wiring).
- **Lifecycle:** a singleton service subscribed in `TntCoatingAddon.onInitialize()`, next to `InventorySlotLoan`. It is **always on**. A user-facing **Inventory Manager** module in the Comet category holds settings, the HUD/status line, the debug log and the proactive-stock toggle. Turning the module off disables proactive stocking only. Arbitration keeps running, because turning it off would bring the original bugs back.
- **Thread rule:** all mutation happens on the client thread at `TickEvent.Pre`, plus the synchronous fast path. Network-thread listeners only record immutable evidence (as `InventoryWeaponAttack` already does).

## 4. Core concepts

### 4.1 Resources

```java
sealed interface Res {
    record Slot(int index) implements Res {}   // player inventory 0–35, armour 36–39, offhand 40
    record Selection() implements Res {}       // the selected hotbar slot (client + server carried item)
    record Cursor() implements Res {}          // carried stack + menu click lane (one multi-click txn at a time)
    record HandUse() implements Res {}         // eating / drawing bow / blocking: selection must not change
}
```

A request reserves its whole resource set **atomically**: it gets all of them or none. `QUICK_MOVE` reserves its whole destination range.

### 4.2 Priority

Effective priority = base band + ageing (+1 per N ticks waiting, capped below the next band), so routine work can't starve forever. Survival can still starve everything else, and that's intended.

| Band | Value | Examples |
|---|---|---|
| `SURVIVAL` | 100 | totem to offhand after a pop, elytra/chestplate swap while falling, auto-eat at critical hunger |
| `COMBAT` | 80 | crystal/obsidian/piston selection in CrystalCombat, weapon swap for a hit |
| `INTERACT` | 60 | active placement burst (Scaffold, BlockCoat, SmartSponge), eating, Speedmine break-tick switch |
| `TOOL` | 40 | AutoTool loan, AutoWeapon loan |
| `MAINTAIN` | 20 | replenish, proactive stock, armour durability swap |
| `BULK` | 0 | InventoryTweaks sort / steal / dump |

Bands are defaults. Each `Owner` gets a per-owner override in the module settings, so the user can, for example, raise Scaffold above Combat.

### 4.3 Requests are intents, not click lists

```java
sealed interface InvIntent {
    record Select(ItemMatcher item, int preferredHotbar) implements InvIntent {}   // put matching item in hand
    record SelectSlot(int hotbar) implements InvIntent {}
    record Move(int from, int to, ItemMatcher expect) implements InvIntent {}
    record Equip(EquipSlot target, ItemMatcher item) implements InvIntent {}       // armour or offhand
    record Loan(int inventorySlot, int hotbar) implements InvIntent {}             // InventorySlotLoan semantics
    record Drop(int slot, boolean all) implements InvIntent {}
}

InvTicket ticket = InventoryManager.get().submit(InvRequest.builder()
    .owner(Owner.SCAFFOLD).key("place-block")       // (owner,key) dedups: re-submitting coalesces
    .intent(new Select(ItemMatcher.blocks(), -1))
    .priority(Priority.INTERACT)
    .deadlineTicks(3)                               // expire instead of running late
    .hold(HoldPolicy.untilIdle(20))                 // keep the Selection lease (StickySlot behaviour)
    .restore(RestorePolicy.IF_STILL_OWNED)
    .build());
```

The plan (which clicks, which slots) is computed **when the request is granted**, from fresh inventory state. It is not computed when the request is submitted. A queued refill whose donor has gone, or a totem request that's already satisfied, resolves to `STALE` or `SATISFIED` and never clicks anything. (Recommendation 6 in the review.)

### 4.4 Two call styles

1. **`tryNow(request)` — synchronous fast path.** Placement and combat need the item in hand *this tick*, before the interact packet. `tryNow` grants and executes immediately when the resources are free, or held only by lower-priority, preemptable leases at a safe boundary. Otherwise it returns `DENIED(reason, holder)` and the module skips the action this tick. This keeps the current timing of Scaffold, CrystalCombat and Speedmine.
2. **`submit(request)` — queued.** Used for maintenance, equips, loans and anything that can wait a tick. The manager runs it at `TickEvent.Pre` within budget.

### 4.5 Ticket lifecycle

`QUEUED → GRANTED → DISPATCHED → APPLIED → (CONFIRMED)`
Terminal states: `SATISFIED` (nothing needed), `STALE` (preconditions gone), `EXPIRED` (deadline), `DENIED`, `PREEMPTED`, `CANCELLED`, `REJECTED` (server resync contradicted it).

`DISPATCHED` means the packets left. It does **not** mean the server accepted them. `CONFIRMED` is only used where an operation has real evidence (container content update matching the expectation, `ClientboundSetHeldSlotPacket`, `WeaponAttackReadiness`). There is no indefinite waiting: confirmation has a timeout, after which the manager rebases on current state and logs the ambiguity. Possibly-completed actions are never replayed.

### 4.6 Leases (long holds) vs transactions (short)

- A **transaction** is one click sequence. It holds `Cursor` plus its slots only until the last click is sent and the cursor is empty again. It can't be preempted mid-flight.
- A **lease** is a long hold: a tool loan holds `Slot(src)` and `Slot(hotbar)`; a sticky placement holds `Selection`. Leases are **renewed every tick by the owner** (`ticket.keepAlive()`) or expire after `leaseTimeoutTicks` (default 5). A disabled module whose leases expire can never wedge the inventory.
- A higher-priority request can **preempt a lease** (not a transaction). The lease owner's restore runs first if it is still valid. Then the new grant executes. The preempted ticket gets `PREEMPTED` and the module may resubmit.

### 4.7 Selection arbiter (replaces ownerless `swap/swapBack`)

- Exactly one owner of `Selection` at a time, plus a **restore chain**: each grant records the slot it switched away from.
- On release, restore follows the existing safe rule from StickySlot and CombatInventoryAdapter: restore only if the selection is still the slot this owner set, otherwise drop the restore silently (the player or someone else chose).
- It tracks the **server-side carried slot** by absorbing `SpeedmineInventory`'s packet tracking, so modules that send raw carried-item packets (Speedmine, combat `ClientPacketSink`) go through `backend.sendCarried(slot)` and the arbiter knows the real server state.
- Manual hotbar input (scroll or number key) instantly releases automated `Selection` leases below `SURVIVAL`, and becomes the new restore target for any lease above it.

### 4.8 Budget and context

- `TickBudget`: `max-clicks-per-tick` (default 4) and `max-selection-changes-per-tick` (default 2). `SURVIVAL` gets a reserved slice it can always spend.
- `Context` = identity of player, level, open screen and menu (reuse `InventoryMoveScheduler.Context`). Any change bumps a generation, cancels unsent work and invalidates late rotation callbacks.
- Blocked when: the cursor isn't empty and not owned by us, a foreign container is open (only `BULK` intents that target it may run), or the player is dead.
- Disconnect, death or dimension change: clear everything and restore nothing.

## 5. Proactive replenishment — StockPlanner

This replaces "refill when the slot empties" with "keep the hotbar ready for what the active modules will need".

### 5.1 Demands

```java
InventoryManager.get().declare(StockDemand.builder()
    .owner(Owner.SCAFFOLD)
    .item(ItemMatcher.blocks().exclude(user blacklist))
    .minInHotbar(32).targetInHotbar(64)
    .preferredSlots(8)                       // optional
    .priority(Priority.INTERACT)             // planner priority for slot assignment
    .activeWhile(() -> scaffold.isActive())
    .build());
```

Planned demands per module:

| Module | Demands |
|---|---|
| CometScaffold / BlockCoat | building blocks (their configured list) |
| CrystalCombat | end crystals, obsidian, pistons + redstone (piston mode), totem in offhand, gapples |
| HighwayFly / AdaptiveElytraFly / AutoWasp / BaritoneElytra | fireworks ≥ N, spare elytra in inventory, recovery chestplate |
| SmartSponge | sponges |
| AutoTool / AutoWeapon | none. They use loans, and stock would fight them for slots. |
| Meteor AutoReplenish (via mixin) | an implicit "keep what was in this slot" demand for every hotbar slot (current behaviour, now at `MAINTAIN`) |

### 5.2 Planning (every 4 ticks, cheap)

1. Collect active demands and sort by priority.
2. **Assign hotbar slots.** Prefer the slot the item is already in (stability first, no shuffling). Next prefer `preferredSlots`, then empty slots, then slots holding items no demand wants. Never use user-pinned slots (a setting), slots under a lease, or the selected slot while `HandUse` is held.
3. **Predict need.** Track per-item consumption in a moving average (count drop per second while the owning module is active). Refill when
   `count < max(minInHotbar, ratePerSec × lookaheadSec)` (lookahead default 3 s).
   For example, Scaffold placing 8 blocks/s with lookahead 3 tops up below 24, well before the stack runs dry mid-bridge.
4. Emit `Move` requests at `MAINTAIN`. Escalate to the demand's own priority when the hotbar count reaches 0 and its module is actively trying to use it.
5. **Quiet windows.** Plain `MAINTAIN` work only runs when no `INTERACT` or higher lease was active in the last 2 ticks, and the player isn't using an item. Stock moves land between placement bursts instead of during them.
6. **Hysteresis.** Do not re-plan a slot assignment for 40 ticks after changing it. Do not move a stack for less than 4 items unless the slot is empty.

Donor selection reuses `ReplenishPolicy.chooseSource` with leased slots excluded. It respects the existing `respect-custom-names`, `same-enchants` and `stack-preference` settings.

### 5.3 AutoReplenish integration

`AutoReplenishMixin.comet$refillSlot` stops calling `InvUtils.move` and submits `Move(source,target)` at `MAINTAIN` with key `replenish:<slot>`. The blanket pause in `comet$pauseTransfers` (line ~97) is removed. Conflicts are now per-slot: a refill whose donor or target is under the tool loan waits or picks another donor, and everything else proceeds. That fixes the "mining pauses all refills" problem on day one of Phase 1.

## 6. Module migration map

| Module / file | Today | Becomes | Priority | Phase |
|---|---|---|---|---|
| `InventorySlotLoan` | own singleton, 2 owners | thin facade over `Loan` leases, so its public API stays the same for callers | TOOL | 1 |
| `AutoReplenishMixin` | `InvUtils.move`, blanket pause | `submit(Move)` + per-slot conflicts | MAINTAIN | 1 |
| `StickySlot` (Scaffold, BlockCoat) | `setSelectedSlot` + own restore | `tryNow(Select)` + `HoldPolicy.untilIdle(20)` | INTERACT | 2 |
| `CometScaffold` direct selects (l.382/402) | `setSelectedSlot` | `tryNow(SelectSlot)` | INTERACT | 2 |
| `BlockCoat`, `SmartSponge` | `InvUtils.swap/swapBack` | `tryNow(Select)` with `RestorePolicy.IF_STILL_OWNED` | INTERACT | 2 |
| `Speedmine` + `SpeedmineInventory` | `setSelectedSlot`, raw carried packets, local priority | `tryNow(SelectSlot, silent=true)`; carried tracking moves into the arbiter; `getCurrentPriority()` reads the manager | INTERACT | 2 |
| `AutoToolMixin`, `AutoWeaponMixin` | `InvUtils.swap` / loan | `tryNow(Select)` for hotbar, `Loan` for inventory | TOOL | 2 |
| `CombatInventoryAdapter` / `MinecraftCombatClient` | own select + `ownedElsewhere()` | `ownedElsewhere()` → `manager.holder(Selection)`; switch/restore via the arbiter | COMBAT | 2 |
| `AdaptiveElytraFly`, `AutoWasp`, `HighwayFly`, `BaritoneElytra` chest swaps | `InvUtils.move().toArmor(2)` / SWAP, blanket guards | `submit(Equip(CHEST, elytra))`; falling/takeoff → `tryNow` at SURVIVAL | MAINTAIN / SURVIVAL | 3 |
| Meteor AutoTotem / Offhand (new mixins) | upstream direct clicks | `tryNow(Equip(OFFHAND, totem))` | SURVIVAL | 3 |
| `ElytraSwapRecovery`, `SmartElytraSwapMixin` | timing-critical at glide start | `tryNow` at SURVIVAL inside the existing injection point (no deferral) | SURVIVAL | 3 |
| StockPlanner + demands | — | §5 | per demand | 4 |
| `InventoryTweaksMixin` + `InventoryMoveScheduler` | own scheduler | scheduler stays for pacing; each action is `tryNow` at BULK holding `Cursor` | BULK | 5 |
| `CombatScheduler` hotbar footprint | separate owner | bridge: a combat lease of `Selection` is mirrored as a manager lease | COMBAT | 5 |

Callers that still use `InvUtils` directly after migration get flagged by the audit (§8). The goal is zero.

## 7. Phasing and acceptance

**Phase 0 — instrumentation (small, ship to testers first).**
`InventoryAudit` listens to every outgoing `ServerboundContainerClick` and `ServerboundSetCarriedItemPacket`, plus `setSelectedSlot` via a mixin, and logs `tick, owner (stack-walk or ticket), action, slots`. It also detects pairs of writes in the same tick from different owners. A debug chat or log toggle sits in the new module. *Accept:* tester logs show which module pairs actually collide, which gives a baseline for refill wait time.

**Phase 1 — core + loans + replenish.**
Core types, `ResourceTable`, queue, budget, `Context`, `InventoryBackend` (26.2), `InventorySlotLoan` as a facade, and AutoReplenish via the manager with no blanket pause. Remove the blanket guards in AutoWasp and HighwayFly only after Phase 3. *Accept:* mining with an inventory tool while blocks replenish in other slots works. A refill touching a loaned slot waits or reroutes. All existing `InventoryMoveSchedulerTest` and `ReplenishPolicyTest` tests stay green.

**Phase 2 — selection arbiter.** Migrate everything in the selection rows. *Accept:* Scaffold + Speedmine + CrystalCombat enabled together never restore into a slot another module owns. Manual scroll always wins below SURVIVAL.

**Phase 3 — equips + survival.** Elytra and chest swaps, AutoTotem/Offhand mixins, then delete the blanket guards. *Accept:* a totem pop during a refill or tool loan is handled within the same tick (or the next if a transaction is mid-flight). Takeoff timing is unchanged.

**Phase 4 — StockPlanner.** *Accept:* a 2-minute Scaffold bridge never hits zero blocks while blocks remain in inventory, and no stock move lands during an active placement tick.

**Phase 5 — InventoryTweaks + combat bridge**, then port to 26.1.2 and 1.21.11 (core is version-free; only `InventoryBackend` and mixins differ).

## 8. Foreign writers

Unmodified Meteor modules and other addons still click directly. The manager cannot stop them, but it:
- detects any container or carried-item packet sent while no ticket is dispatching, and treats it as a **foreign write**;
- bumps the inventory generation, so every pending plan re-validates and every lease checks its endpoints (exactly like `InventorySlotLoan.restore()` abandoning on mismatch);
- pauses `MAINTAIN`/`BULK` for a few ticks, and names the culprit in the status line where the stack walk can tell.

## 9. Testing

The core is pure, so it runs under JUnit with a `FakeBackend` (simulated 41-slot inventory, cursor, selected slot, server slot, injectable lag/rejection). Cover the review's verification matrix:

- loan + unrelated refill run in parallel; loan + colliding refill waits or reroutes
- totem pop during refill, tool loan, weapon readiness and chest swap
- two owners want the offhand or chest at once
- non-empty manual cursor; manual hotbar change; manual edit of a leased endpoint
- broken borrowed tool; container reopen with a reused id; death, respawn, dimension change, reconnect
- disabled owner (lease expiry); late rotation callback; rejected/resynced packet (no double-apply)
- repeated submit coalesces (bounded queue); ageing lets MAINTAIN progress under sustained TOOL load
- budget never exceeded; SURVIVAL latency ≤ 1 tick when no transaction is in flight
- invariants checked after every simulated tick: **item conservation, no unexpected drops, no stale restore, at-most-once causative action**

Live verification goes through tester builds with the Phase 0 audit log on, instead of manual play: collision counts, refill wait p95 and foreign-write counts become the before/after metrics.

## 10. Decisions to confirm

1. **Always-on service with the module controlling only stock and UI** (recommended), versus a fully toggleable module that falls back to direct execution when off.
2. **Default priority ladder** in §4.2, especially whether `INTERACT` (placing) should sit above `COMBAT` by default for your use.
3. **User-pinned hotbar slots** that StockPlanner never touches: wanted in v1?
4. **AutoReplenish:** keep it as a client of the manager (recommended, preserves its settings and UI), or fold it fully into StockPlanner and hide Meteor's module.
5. Default click budget (4/tick) versus the anticheat on the servers you play.
