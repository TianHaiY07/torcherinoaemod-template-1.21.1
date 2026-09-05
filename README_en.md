# Torcherino AE

> The one-liner version: this is an AE2 add-on that packs Torcherino's "time acceleration" into your ME network — whether to accelerate, who to accelerate, and how fast, is entirely up to you. The **AE Accelerator**, wired into the ME network and powered by AE energy, handles "precision acceleration"; the **AE Torcherino**, requiring no wiring and no energy, handles "area acceleration".

## What is it?

If you have played with Torcherino (the acceleration torch), you remember that torch you place down and everything around it speeds up. This mod keeps that idea but turns it into a more "AE2-flavored" variant:

- Acceleration can be a **network service**: connect the Accelerator to your ME network, supply it with AE energy, and its GUI will list every acceleratable device in the network — toggle them one by one, tune each device's multiplier individually, and even let your crafting CPUs automatically "urge" the machines involved in a craft while it runs.

- Acceleration can also be a **torch**: no network, no energy. Place it down and it scans a cuboid region; every acceleratable target inside gets sped up uniformly.

Both approaches **never touch network infrastructure** — storage buses, P2P tunnels, energy cells, and other blocks that "have no work to do" are never treated as targets; the things actually being accelerated are machines with real logic running.

## What does it bring you?

| Type | In-game Name | What it does |
| --- | --- | --- |
| Block | **AE Accelerator** | A true AE2 machine: connects to the ME network and runs on AE energy, with 4 upgrade-card slots + 1 config-card slot; its GUI lists every acceleratable device and crafting CPU in the network |
| Block | **AE Torcherino** | A wireless area acceleration source: scans a cuboid around itself; the GUI lets you adjust the multiplier and the X/Z/Y range, with its own master switch |
| Block | **AE Torcherino I / II** | Tiered variants of the base torch — identical gameplay, only the multiplier cap is fixed at ×64 / ×324 |
| Item | **AE Accelerator Upgrade Card I / II / III** | Insert into the Accelerator's upgrade slots to raise the "max multiplier": the first card of each tier applies in full at ×2 / ×4 / ×8, further cards of the same tier give diminishing returns; can be stacked freely |
| Item | **Accelerator Config Card** | Stores a binding between "an Accelerator + a set of devices / crafting CPU groups" on one card: insert it to auto-inject acceleration, pull it out for precise removal |

## Two acceleration ideas — which to pick?

|  | **AE Accelerator** | **AE Torcherino** |
| --- | --- | --- |
| Needs an ME network? | Yes, plus AE energy | No, and uses no energy |
| Acceleration range | Devices inside the network (plus crafting CPU coordination) | A cuboid region centered on the torch, which may span multiple networks |
| Multiplier granularity | Per-device multiplier | One uniform multiplier for the whole region |
| How to get stronger | Insert upgrade cards to raise the cap | Switch to the tiered torch I / II (caps ×64 / ×324) |

In one sentence: **want fine-grained control → the Accelerator; want "set and forget" → the Torcherino.** The two don't conflict and can be mixed freely.

## First steps: get the Accelerator on the network

1. Like any normal AE2 machine, connect the Accelerator to your ME network (cables / interfaces) and make sure the network has AE energy (energy cells, energy acceptors, etc.).

2. Open its GUI: the status line at the top shows **"Working / Connected to network, awaiting energy / Not connected to a network"** in real time, so you can judge the network's health at a glance; the device list and the crafting CPU list live on the same screen.

3. **Left-click** a device entry = start / stop accelerating that device; **right-click** = pop up a multiplier slider (`1x ~ current max multiplier`, setting it to `1x` cancels it). The list supports search filtering.

4. The block's appearance carries state too: once powered, a glowing "work band" lights up (ONLINE); while actually working, flowing particle effects overlay it (WORKING).

Right next to it are the 4 upgrade-card slots and the 1 config-card slot — next step: slot in some upgrade cards.

## Where does the multiplier come from? How do upgrade cards stack?

The Accelerator has a "max multiplier" that no device's multiplier may exceed; the only way to raise it is to **insert AE Accelerator Upgrade Cards into the upgrade slots**.

The base multiplier defaults to **×4**, and each tier of card carries a nominal factor of **I = ×2, II = ×4, III = ×8**. To keep stacking cards of the same tier from turning into exponential explosion, "diminishing returns" apply:

```
1st card of a given tier: applied in full at its nominal factor (×2 / ×4 / ×8)
(n+1)th card of the same tier: actual multiplier = 1 + ((n)th card's actual multiplier − 1) × cardDiminishing (default 0.45)

Max multiplier = base (default 4) × tier I compounded gain × tier II compounded gain × tier III compounded gain
```

- One card of each tier (mixed): `4 × 2 × 4 × 8 = 256x`

- Four III cards "maxed out": about **526x** (not truly maxed, though — try 2 II cards plus 2 III cards and see for yourself 😉);

- Don't want diminishing returns? Set `accelerator.cardDiminishing` to `1.0`, and it becomes `base × 2^I × 4^II × 8^III`.

## Advanced play: let the crafting CPU be the "supervisor"

**Left-click** a CPU in the "Crafting CPU" section of the GUI to enable **smart acceleration**:

- **While that CPU is performing a craft**, the Accelerator automatically speeds up the machines involved (molecular assemblers, inscribers, chargers, etc.) and stops the moment the craft finishes — no manual per-device toggling needed;

- The default scope, `crafting.smartAccelerateScope = ALL_ACCELERATABLE`, brings every acceleratable device in the network into the coordination, so third-party AE working machines are compatible **with zero configuration**;

- Only want the machines that "actually perform the crafting" to be coordinated? Switch to `CRAFTING_MACHINES` (judged by AE2's `ICraftingMachine` / `CRAFTING_MACHINE`, with inscribers and chargers built in as a fallback; extra types can be appended via `grid.craftingMachineExtraTypes`);

- Server-side master switch: `crafting.smartAccelerateEnabled` (default `true`).

## Even further: write your acceleration plan onto a card

Too many targets, too tiring to click? The **Accelerator Config Card** can save an entire set of acceleration relationships offline:

1. Hold the card and **Shift + right-click** an Accelerator to bind it to the card (binding to a different Accelerator clears the old device list);

2. Afterwards, **right-click** a device = add it to / remove it from the binding list; **right-click a crafting CPU multiblock** = bind / unbind the whole group (a CPU group takes only one entry). A single card holds up to 64 entries; you'll be notified when it's full;

3. **Insert the card into the Accelerator's config-card slot**: devices recorded on the card and present in the network get acceleration injected automatically; **removing the card** revokes it precisely — devices you manually toggled in the GUI are never disturbed;

4. Bindings identify devices by "dimension + coordinates", so identical coordinates in different dimensions are never confused. An Accelerator only accepts cards that are "bound to itself" (dimension-checked); slotting the wrong card into the wrong machine is rejected.

> The config card is "plug and play": when a device is dismantled or an Accelerator is broken, the server automatically cleans up related bindings scattered in players' inventories and card slots — no entries pointing at thin air are left behind.

## Try the AE Torcherino

- No ME network needed, and it consumes no energy at all; place it down and it's an "area accelerator";

- The server scans a cuboid centered on the torch, and **every acceleratable target** inside is sped up uniformly at the current multiplier — they may belong to different AE networks, or to no network at all;

- The target criteria are broad: AE grid devices, block entities that provide a ticker, and blocks relying on **random ticks** (e.g. crops in range) are all taken care of; air, purely decorative blocks, and **other torcherinos** are skipped automatically (preventing torches from accelerating each other into recursion);

- Adjustable from the GUI: **the multiplier**, **the X / Z / Y range radii**, plus a **master switch** (when off the torch goes dark, but its multiplier and range settings are kept). The base torch defaults to ×4, X/Z ±3, Y ±2; the adjustable caps are sent down by the server (defaults ×4, X/Z ±8, Y ±4);

- Want a higher multiplier cap? Switch to the **AE Torcherino I (×64)** or **AE Torcherino II (×324)** — these two tiers have fixed caps unaffected by the `torcherino.maxSpeed` config, and they spawn already at their cap.

## Stack to a few hundred times — can the server survive it?

The mod comes with multiple layers of performance protection, on by default and needing no tuning:

- **Only meaningful machines are accelerated**: network infrastructure is excluded; on the torch side, only blocks that "really might be accelerated" are registered, each cell is queried with a single `getBlockEntity` lookup, and unloaded chunks are skipped;

- **Fragmented region scanning + adaptive backoff**: the torch spreads a full scan cycle across several ticks (default window of 20 ticks); if nothing in range changes for a long time, it backs off round by round up to 200 ticks; the moment a block is placed / broken it's immediately woken to resume dense scanning — even huge ranges won't spike the main thread in a single tick;

- **Budget + two-level automatic throttling**: `budget.tickCallsPerSource` can set a per-tick call cap for each acceleration source (unlimited by default); when TPS approaches the 50 ms hard limit, the `adaptive` section steps the budget down automatically in stages; each source also meters its own contributed time (`rate.sourceMsLimit`, default 15 ms), and once exceeded, temporarily lowers the **actually applied multiplier** — the value you set in the GUI is never modified, and the real multiplier climbs back automatically once load falls.

In short: under a healthy load it never interferes; if extreme multipliers drag TPS down, it first "throttles its own power" to protect the server.

(When accelerating machines makes the game lag — that's genuinely unavoidable.)

## Troubleshoot first, then ask for help

1. **Accelerator not working?** First check the GUI status line: `Not connected to a network` → check cables and interfaces; `Connected to network, awaiting energy` → check network power supply and the energy buffer (`power.bufferFraction`); status is fine but nothing happens → confirm the target machine actually shows up in the list.

2. **Can't find the target machine in the list?** Confirm it's a real machine and not infrastructure like a storage bus; the GUI list refreshes on a cycle (default 20 ticks) — wait a moment or reopen the screen.

3. **AE Torcherino not lit / no effect?** Check whether the master switch is on, whether the multiplier is > 1, and whether the range wasn't zeroed out; after an update, if something odd happens, confirm the block state carries the `enabled` property.

4. **Suspect wrong numbers?** Set the server config `debug.enabled` to `true` (off by default) and investigate with the diagnostic log.

5. Still stuck? Open an issue on the GitHub repo with the **version number, screenshots, diagnostic log, and reproduction steps**.

---

## Server config quick reference

After running, two config files are generated in `config/`, and changes take effect **immediately via hot reload**:

- `torcherino_ae_mod-server.toml` — all server-side values and behaviors;

- `torcherino_ae_mod-client.toml` — client-side UI / rendering toggles.

| Group | Key items (defaults) |
| --- | --- |
| `accelerator` | Base multiplier `4` / tier factors `[2, 4, 8]` / same-tier diminishing ratio `0.45` / cap `-1` (unlimited) |
| `power` | Base consumption `1.0` / per card `0.5` / per device `0.5` / shutdown buffer fraction `0.9` |
| `torcherino` | Max multiplier `4` / max X·Z radius `8` / max Y radius `4` |
| `budget` | Per-tick call budget per source `-1` (unlimited) |
| `adaptive` | TPS throttle on `true` / tighten · relax thresholds `45 / 35 ms` / starting tier `256` calls |
| `rate` | Per-source time limit `15 ms` / EMA smoothing `0.25` / tighten · relax ratios `1.0 / 0.7` |
| `cache` / `menu` | Target-cache rebuild / GUI list refresh cycle `20 / 20` ticks |
| `grid` | Non-acceleratable blacklist (three infrastructure types by default) / crafting-machine fallback types (inscriber, charger) |
| `crafting` | Smart acceleration master switch `true` / coordination scope `ALL_ACCELERATABLE` |
| `debug` | Diagnostic log `false` / sample interval `20` |
| `client` | List filter cache `true` / config-card highlight `true` |

## License & acknowledgments

- This mod's `license` is declared as **All Rights Reserved**.

- The project skeleton comes from the NeoForged MDK template (MIT, see `TEMPLATE_LICENSE.txt` in the repo root).

- AE2 / GuideME belong to their respective authors; this mod merely references them as dependencies.

- Thanks to the NeoForge and Applied Energistics 2 communities for the excellent tools and ecosystem.
