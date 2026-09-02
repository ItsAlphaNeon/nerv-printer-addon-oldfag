# HIVEMIND_WORK.md — Session Summary (2026-08-30, updated 2026-08-31)

This file documents everything done in this working session for the next agent to pick up.
Everything below is implemented, compiles, and is built into `nerv-printer-1.21.11.jar`
(via `build-no-test.bat`, which builds and copies the jar to 3 Prism Launcher instances:
`Nerv Printer` (master), `Nerv Printer (Helper)`, `Nerv Printer (Helper 2)`).

---

## 0. 2026-09-01 addendum — error-system + ownership fixes (the "379 errors" session)

Benchmark log `hive-master-2026-09-01_06-28-55.log` exposed three compounding bugs
## 0g. 2026-09-01 v7 addendum — master took middle rows with anchor inactive (silent fallback)

**Symptom:** "Master abandoned their required 1-64 lines for the middle assignment,
leaving the dupers to break." Log: both re-splits tagged `[no afk-anchor]` - with
AFK-anchor inactive, the master deliberately takes the MIDDLE section and walks
away from the duper-adjacent rows; nobody anchors the dupers and their chunks
unload. The fallback was completely silent (no reason, no warning).

**Root cause of the surprise:** `usesAfkAnchorRows()` = afkAnchor toggle ON &&
afkSpot != null. This run had it inactive (toggle OFF, or AFK spot missing - the
config-load prompt "Config has no AFK Spot" can be skipped and nothing re-warns).

**Fixes (all in):**
- New `MapPrinter.afkAnchorStatus()` (default method): interval logs now say WHY -
  `[no afk-anchor: toggle is OFF...]` vs `[no afk-anchor: AFK-anchor is ON but NO
  AFK SPOT is set - master took middle rows, DUPERS MAY BREAK]` vs
  `[AFK-ANCHOR: master on duper-adjacent rows]`.
- `.startprinter` warns loudly (chat + HiveLog `AFK-ANCHOR INACTIVE at start`)
  when the anchor is inactive in hivemind mode, naming the fix (toggle on /
  right-click the AFK spot and re-save the config).
- Heartbeat phase `PAUSED` (a paused bot used to keep reporting BUILDING).

**Operator note:** for duper anchoring, afk-anchor must be ON and the AFK Spot
must be set on the master BEFORE `.startprinter`.


## 0f. 2026-09-01 v6 addendum — invite-flow slaves silently ignored invites (self-hosting default)

**Symptom:** "slaves that receive an invite via whisper no longer do anything"
(all modules on and reset, invite whisper arrives, no "Joining hivemind" message).

**Root cause:** an invite-flow slave has an EMPTY master-address - and
`isMasterMode()` is DEFINED as "empty address". So on activation the slave ran the
MASTER path and `ensureServer()` bound port 8080 (succeeds when the master is on a
different PC; on the old single-PC setup the bind FAILED against the master's 8080,
which masked this). The invite then hit `handleInvite`'s first guard
`if (serverStarted) return;` - silently dropped. The slave also sat in the master
chest-selection flow instead of AwaitSetup.

**Fixes (all in):**
- `handleInvite`: `serverStarted` no longer auto-rejects. If we host but have NO
  hive of our own (no slaves/connections/pending), we are a stranded default-host:
  stop the server and accept the invite. A real master (has slaves) refuses loudly.
- New `MapPrinter.onInviteAccepted()` (Staircased: no-op stub). CarpetPrinter impl:
  syncs the `master-address` SETTING to the joined IP (so a later re-toggle stays
  slave mode instead of re-hosting) and transitions to `AwaitSetup`.

**Lesson:** "empty master-address = master" makes every invite-flow slave a
would-be host on activation. Any new guard keyed on serverStarted must consider
the empty-hive case.


## 0e. 2026-09-01 v5 addendum — dump stall: inventory open/close resync as FIRST effort

The dump-stall escalation chain now starts with a cheap unstick instead of jumping
straight to forced throws:

1. **5s stall (NEW, first effort):** open and close the player inventory ONCE
   (`dumpInvResyncDone` guard - fires only once per stall, re-arms as soon as drops
   go through again or the dump finishes/gives up). Closing a container makes the
   server hand a phantom held/cursor item back to the inventory - the most common
   silent cause of ignored THROW clicks. Logs `DUMP stall Ns ... inventory
   open/close resync (first effort)`.
2. 10s stall (unchanged): forced full-stack THROW every 2s, cursor PARK into an
   empty slot every 3rd attempt.
3. 20s stall (unchanged): give up, continue with excess items.


## 0d. 2026-09-01 v4 addendum — HOTFIX: zero placements regression (absolute vs relative row)

**Symptom:** "Not a single account placed a single carpet." Log signature: all bots
BUILDING, unfinished counts frozen (43/42/43), progress messages perfectly periodic
(one per line pass, 23s/30s), NO restock or dump activity - meaning the placement
filter rejected every candidate and `tryPlacingBlock` never ran.

**Root cause:** the v2 audit fix #11 (strict interval-bound placement) compared the
ABSOLUTE world X (`blockPos.getX()`, here ~3,401,788) against the RELATIVE row
interval (0-127) - always false, so no bot could ever place. One-line fix:
`Utils.isInInterval(workingInterval, blockPos.getX() - mapCorner.getX())`.
All other isInInterval call sites audited - they all use relative rows already.

**Lesson:** interval rows are always RELATIVE to mapCorner; any new check that
touches world positions must subtract mapCorner first (pattern: `rel =
blockPos.subtract(mapCorner)` like the rest of the placement code).


## 0c. 2026-09-01 v3 addendum — invite advertised the WRONG network IP (slave-join failures)

**Symptom:** "some clients are failing to be slaves, even when invited". Log showed the
master DM-inviting `hivemind:192.168.56.1:8080` - the **VirtualBox Host-Only adapter**.

**Root cause:** `resolveLocalIp()` returned the FIRST site-local IPv4 of any up,
non-loopback NIC and trusted `NetworkInterface.isVirtual()`. On Windows that method
is unreliable - VirtualBox/Hyper-V/WSL adapters usually report `isVirtual() == false`,
so the invite advertised an IP other PCs (and some firewall profiles) can never reach.
Slaves then retried every 5s forever. The one client that worked had `master-address`
manually set to `localhost` from earlier testing.

**Fixes (all in):**
- `resolveLocalIpDetailed()` + name-based virtual-adapter blocklist
  (virtualbox/vbox/vmware/vmnet/hyper-v/vethernet/wsl/docker/tailscale/zerotier/
  hamachi/nordvpn/wireguard/tunnel/teredo/isatap/tap-/tun-/virtual/loopback).
  Clean adapters win; suspect adapters are only a last resort; loopback fallback.
- New master setting **advertised-ip** (Multi User group): manual override for the IP
  invites advertise. Wired via `SlaveSystem.advertisedIpOverride` (set on activate +
  onChanged).
- **Diagnostics:** `Invite players in range` now prints `Advertised IP: <ip>
  (interface: <name>)` + a hint to set advertised-ip; Hivemind Status shows the
  invite IP and whether it is auto-detected/override (with a loud warning when only
  127.0.0.1 is available); slaves print a virtual-adapter/firewall hint every 3rd
  failed connect attempt. New HiveLog marker: `INVITE advertised <ip>`.
- Docs: CarpetGuide invite section updated.

**Operator action for the current setup:** set `advertised-ip` on the master to the
PC's real LAN IP (or keep using `master-address` on slaves as today) and re-invite.


# HIVEMIND_WORK.md — Session Summary (2026-08-30, updated 2026-09-01 v2)

## 0b. 2026-09-01 v2 addendum — audit fixes + pre-finalize VERIFY pass (all in, built & deployed)

A full audit found 14+ failure/loop/deadlock scenarios. All critical/high ones are fixed:

### Pre-finalize VERIFY pass (the headline feature)
- Before ANY finalize assignment (self-finalize OR delegated), the master assigns a
  slave (`verify` command, ACKed) to verify the canvas: it walks the **4 quadrant
  centers** (`+32/+96` — loads every chunk), then runs a full-area scan
  (`listBuildIssuesFullArea`: missing = air at expected block, wrong = block identity
  mismatch, leftover = non-air at null cell; fluids skipped; unknown chunks = missing).
- Up to **3 repair rounds**: break wrong/leftover blocks, walk to air gaps so the
  placement loop re-places them (owner-neutral placement bypass), then rescan.
- Reports `verifyDone:<remaining>`; master proceeds with finalize (0 = clean, >0 =
  wipe gate will hold if the issues matter). Heartbeat phase `VERIFYING`.
- Watchdog: 5 min without `verifyDone` -> re-assign another slave (max 3 attempts/map,
  `MAX_VERIFY_ATTEMPTS`), then continue without verify (wipe gate remains as backstop).
- Slave `start()` ignores START while `verifyingForMaster || finalizingForMaster`
  (a STALL re-partition START used to wipe the verify/finalize checkpoint path).
- The verifier never touches the bot's own interval - owner-neutral by design.

### Audit fixes (each maps to an audit finding)
1. **Orphaned rows after re-split while master parked**: `SlaveSystem.masterRowsHandedOff`
   (set by `handOffMasterRows`, cleared at map start). `generateIntervals` keeps the
   master at `0:-1` and calls `repartitionAmongSlaves(null)` instead of splitting rows
   to the parked master. Also `repartitionAmongSlaves(String exclude)` - the STALL
   watchdog excludes the wedged bot from the partition (it used to receive fresh rows
   it could never build and burn both attempts).
2. **Late joiner while master is Afk**: `slaveRegistered` now sends map+start in `Afk`
   state too (it used to idle in AwaitMasterMap forever with rows it could not build).
3. **Map-less/setup-less slaves were invisible**: heartbeat phase `NOMAP` + unfinished =
   whole interval when map==null (old code reported 0 -> master waited forever).
   Slave map transfer give-up now sends `mapFailed:<file>`; master drops that slave
   and re-splits instead of stalling.
4. **Master-owned error rows**: at lineEnd, if EVERY unfinished master row holds a
   known error, the master hands its rows off (`handOffMasterRows`) - slaves re-scan
   and repair. (Non-anchor master used to loop on error rows forever / orphan them on
   steal.) The wipe gate now also blocks on **wrong blocks** and leftovers (it only
   saw air gaps before - wrong colors silently ruined the crafted map item).
5. **endBuilding failure loop**: no cartography chest -> HOLD + toggle off with a loud
   message (`proceedMasterSelfFinalize`), instead of the old every-tick
   "Finished building map" infinite loop.
6. **Empty material chest infinite ping-pong**: `endRestocking` gives up when every
   known chest for the material was visited empty (`RESTOCK HOLD` + toggle off); the
   old getBestChest clear+recurse returned the same chest forever.
7. **AwaitAreaClear forever**: 5 min timeout -> re-run wipe (max 3) -> `WIPE HOLD`
   loud message. Counters reset on area clear / map start.
8. **Delegated finalize re-delegation cap**: max 2 re-delegates per map
   (`finalizeRedelegations`); after that - or when no other slave exists - the master
   runs the finalize itself (exits Afk) instead of ping-ponging/waiting forever.
9. **Corner parking blocked the wipe**: parked slaves now stand ~2 blocks AWAY from
   the map (direction from map center) so the wipe perimeter walk is not blocked.
10. **Silent disappearance**: slave module deactivation sends `leaving` (master
    unregisters + re-splits WITHOUT sending "remove" back - it would re-activate the
    just-disabled module); master deactivation pauses all slaves.
11. **Placement cross-ownership**: placement window now strictly interval-bound
    (`isInInterval`), except verify/finalize phases (owner-neutral by design).
12. Late joiners during AwaitVerify are parked (finalizePhase) - unchanged.

### Crash-risk guards added
- After `endBuilding` assigns verify (state `AwaitVerify`, empty checkpoints), both
  call sites bail out instead of falling into `checkpoints.get(0)`.

### New protocol commands
- `verify` (M->S, ACKed), `verifyDone:<n>` (S->M), `mapFailed:<file>` (S->M),
  `leaving` (S->M). `MapPrinter` gained `runVerify()` + `verifyDone(String,int)`
  (Staircased: no-op stubs).

### New log markers
`VERIFY assigned/accepted/round/scan clean/timeout/cap`, `MAP FAILED`, `LEAVING`,
`RESTOCK HOLD`, `WIPE HOLD`, `WIPE retry`, `FINALIZE fallback`, `INTERVALS reassigned
-> master: none (anchored)`, `ERRORS occupy all N of my unfinished rows`.

### Deliberately deferred (audit findings, lower priority)
- AwaitBlockBreak attempt cap (a protected block hangs the verifier -> the verify
  watchdog re-assigns, so it self-heals slowly).
- mapOk handshake gating "start" (NOMAP heartbeat + mapFailed drop cover the flow).
- AwaitButtonPress state verification before "Continuing anyway" (AwaitAreaClear
  timeout now bounds the damage).


(slaves stuck in repair loops for 26+ min, tearing up corrected blocks; hive never
finished). All fixed:

- **knownErrors was append-only** (`getInvalidPlacements` skipped positions already in
  the list; nothing ever removed them). Fixed blocks stayed "errors" forever -> the
  heartbeat error count froze (379 for 26 min) and the Repair loop re-broke fixed
  blocks every pass. FIX: `Utils.getInvalidPlacements` re-checks every position (no
  skip) and the lineEnd handler RECONCILES (`clear(); addAll(newErrors)`) so the list
  is always exactly the currently-wrong set.
- **Repair route re-broke everything** (`ErrorAction.Repair` built break-checkpoints
  from the full knownErrors list unverified). FIX: verify-before-break - only positions
  whose current verified state still mismatches the map get a break checkpoint; empty
  result -> just re-scan.
- **Leftover blocks where the map expects nothing** (dirty canvas from an incomplete
  wipe) are now flagged as errors by the scan (air-at-null-cell) so Repair breaks them;
  FLUIDS are explicitly skipped (breaking water is a no-op - flagging them created
  un-removable errors).
- **Union-merge handoff created OVERLAPPING intervals** (master 0-42 + slaves 43-85 /
  86-127 -> 0-85 and 21-127; rows 21-85 double-owned -> cross-bot repair wars).
  FIX: `SlaveSystem.repartitionAmongSlaves()` - full disjoint weighted re-partition of
  0-127 among slaves (master keeps none); `handOffMasterRows` calls it; disjointness
  asserted in the log (`CRITICAL ... OVERLAP` must never fire).
- **Owner-only errors**: `setInterval` drops knownErrors outside the new interval
  (stale errors from an old assignment made bots re-repair other bots' fixed blocks).
- **Stall watchdog** (master sweep): a slave with an unchanged unfinished-row count >0
  for 5 min -> `STALL` log + re-partition among slaves (max 2/map, then loud chat).
  Reset on MAP START.
- **Dirty-canvas scan** at MAP START: non-air leftovers at null cells in the bot's own
  interval get break-checkpoints up front (`CANVAS DIRTY` log, cap 512) instead of
  surfacing as a mid-map error storm.
- Heartbeat phase: a paused AFK master (`AwaitSlaveContinue` w/ oldState Afk) reports
  ANCHORING (no more ANCHORING/BUILDING flicker after pause/resume).

New log markers: `CANVAS DIRTY`, `STALL`, `HANDOFF re-partition`,
`CRITICAL ... OVERLAP` (must never appear).

## 0a. 2026-08-31 addendum — efficiency + protocol hardening (Phase 1-3 COMPLETE)
### Work distribution
- **Work-weighted interval split** (`generateIntervals`): rows split by per-row block
  counts from the parsed map (`MapPrinter.getRowBlocks()`), not equal row counts.
  Falls back to equal-row split without a map. Tag in log: `[AFK-ANCHOR...]` / `[no afk-anchor]`.
- **Work stealing**: slaves report `progress:<unfinishedRows>` at line end; master's 30s
  sweep steals the far tail of the busiest bot for an idle bot (`STEAL` log lines).
  MIN_STEAL_ROWS=8, MIN_STEAL_AMOUNT=4.
- **AFK anchor mode** (`afk-anchor` toggle + AFK Spot in the config setup): the master
  builds the duper-adjacent rows, then parks at the AFK spot (`State.Afk`) to keep duper
  chunks loaded. Master excluded from error repair/reset/toggle-off in hivemind mode
  (`ERRORS N skipped by master`). If the master's only remaining rows contain known
  errors, it hands ALL rows off (`HANDOFF` log, union-merged intervals, fires once per
  map via `rowsHandedOff`, master interval set to 0:-1 empty) and goes AFK.
- **Delegated finalize**: last `finished` slave receives `finalize` and runs
  dump->cartography->finished chest->wipe; master does bookkeeping only
  (`delegatedMapFileName` captured to protect the rename; 3min watchdog re-delegates).
  Slave replies `finalizeDone` -> master loads next map.

### Map transfer reliability
- Chunked transfer: `map:<file>:<crc32>:<totalChunks>:<chunkIdx>:<data>` (~300KB base64
  chunks), slave reassembles + CRC-verifies, auto re-requests via `remap` up to 5
  attempts (`requestRemap`), 5s stall watchdog, per-map attempt counter resets.
- Slave-side write verifies byte count; parse failures are stage-logged
  (`NBT LOAD <f> STAGE parse FAILED (file N bytes): <exception>`).

### Dump/restock hardening
- Dump watchdog: 3s `DUMP slow` telemetry -> 10s forced full-stack THROW -> cursor-reset
  intervention (PICKUP on an empty player slot, fixes server-side phantom-cursor desync)
  -> 20s give up and continue with excess items. `timeoutTicks` always ticks during dump.

### Phase 1 (bug fixes, all in)
- `MapAreaCache.getVerifiedBlockState()` returns **null for unknown/unloaded chunks**;
  ALL completion checks treat null as NOT finished (line-finished scan,
  `countUnfinishedRowsInInterval`, `Utils.getInvalidPlacements`, `getRequiredItems`).
  Legacy `getCachedBlockState` kept for placement, warning rate-limited 5s.
- **Wipe-completeness gate** in `AwaitFinishedMapChestResponse` (covers master AND
  delegate): `listMissingBlocksFullArea(25)` scan before any wipe; missing -> repair
  round (break + lineEnd re-place, max 3) -> `WIPE BLOCKED` logs; 3 failures -> HOLD,
  never wipes.
- Commands now sequenced/ACKed (below) at all assign/steal/handoff/park/finalize sites.

### Phase 2 (protocol redesign, all in)
- `utils/HiveCommand.java`: typed enum, every command declares Direction
  (TO_SLAVE/TO_MASTER/BOTH) + needsAck. `handleMessage` parses once and dispatches on
  direction - wrong-side handlers are structurally impossible. Unknown commands logged
  and dropped. Wire compat: `parseCompat` keeps legacy names.
- ACKs: `interval/start/pause/finalize/goToCorner` carry `<seq>:` prefix; slave applies
  then replies `ack:<seq>`; master retries every 2s x3 then `CMD ... FAILED after 3
  attempts`. NOTE: ack parsing accepts both `ack:<seq>` and `<seq>:ack` forms (bug found
  in self-audit: "ack:5" has no leading digit, seq must come from args).
- Heartbeats every 10s: slave sends `hb:<phase>,<start>,<end>,<unfinished>,<errors>`
  (phase BUILDING/ANCHORING/FINALIZING/PARKED/IDLE via `MapPrinter.getHeartbeatData()`);
  master feeds `onSlaveProgress`, **drift-corrects** interval mismatches (re-sends
  interval), warns on 30s heartbeat gaps. Master logs its own heartbeat.
- Idempotent handlers: duplicate `accept` and duplicate `finished` ignored + logged.
- `config:` broadcast debounced per-slave per-payload (`slavesWithCurrentSetup`; a new
  slave always receives it; payload change resets the set).

### Phase 3 (cleanup, all in)
- `ERRORS N pending on master` log throttled to 30s. MapAreaCache fallback warning
  throttled. Dead `mine`/`skip` commands retained (harmless no-ops on CarpetPrinter).
- **Log markers to know**: `CMD`, `ACK`, `HEARTBEAT`, `HEARTBEAT MISSING`, `HEARTBEAT
  drift`, `STEAL`, `HANDOFF`, `PARK`, `FINALIZE delegated/complete`, `WIPE BLOCKED`,
  `MAP SEND/RECEIVE`, `SETUP broadcast skipped`, `DUMP ...`, `INTERVALS reassigned`.

### Benchmark / operator commands (master)
- `.startprinter` — starts printing (requires module active, correct state, chests set,
  map NBT loaded; each failure mode has a distinct chat message instead of crashing).
- `.pauseprint` / `.resumeprint` — pause/resume the ENTIRE hivemind: master pauses
  in place (`AwaitSlaveContinue`) and every slave gets pause/start. Logged as
  `HIVE PAUSE requested` / `HIVE RESUME requested`. Solo printing still works (pauses
  just the local bot).
- `afk-anchor` toggle (Carpet Printer -> General): ON = master builds duper-adjacent
  rows, anchors dupers, delegates finalize. OFF = raw speed (master takes middle
  section, steals when idle, runs finalize itself). Honored live at the next 30s sweep;
  also gates whether the AFK Spot is required by `hasFullSetup()`.

### Hive log (observability)
- `utils/HiveLog.java`: master-only, thread-safe, auto-flush. Location:
  `.minecraft/nerv-printer/hive_logs/hive-master-<timestamp>.log` (same folder as the
  NBTs/configs). Created LAZILY on the first slave socket message - a solo master never
  creates one. Payloads >160 chars truncated. `IN `/`OUT ` wire lines for every message.
- `HiveLog.enable()` is called from `onSocketMessage` when a slave connects; logging
  no-ops on slaves and when no printer module is active.
- Map NBT transfer now CHUNKED with CRC32 (`map:<file>:<crc>:<total>:<idx>:<data>`),
  slave auto re-requests (`remap`) up to 5x with reasons, 5s stall watchdog.
  `requestRemap(fileName, reason)` is the single failure funnel. Note: remap/progress
  handlers must be on the MASTER side switch - the typed dispatch in HiveCommand now
  enforces this, but know the history: these commands were once silently dead on the
  wrong side for a whole benchmark run.

### Crash/bug fix history (gotchas to not regress)
- **`map` NPE on `.startprinter`**: loading a config while the module is INACTIVE is
  allowed (pre-stages setup) but does NOT load the NBT - `startPrinting` validates
  module active -> state -> chests -> map loaded, each with a clear chat message.
  Required order: NBT in folder -> enable module (loads map) -> `.startprinter`.
- **`sendToSocket` null-player guard**: sends during game shutdown are dropped silently
  (log still written) - do not remove, it fixed a shutdown NPE.
- **Chest interrupt deadlock**: `handleInventoryPacket` must always resolve
  `AwaitRestockResponse` (empty restock list -> `endRestocking()`; needs-0-more ->
  clear backlog + `endRestocking()`), plus a 10s `AwaitRestockResponse` stall watchdog
  that re-plans the refill. Without all three, the bot stares at a chest forever.
- **Error-row logic**: master counts as "done" only when EVERY unfinished row contains a
  known error (`errorRowsInInterval >= ownUnfinished`), never "some error exists".
- **`SlaveSystem` is fully static and shared by BOTH modules** - keep the
  module-instance check in `setupSlaveSystem` intact. Heartbeat/ACK maps are cleared on
  module-instance reset and slave removal.
- **Wire format**: `<seq>:<command>[:args]` where seq is optional; the parse accepts
  both `ack:<seq>` and `<seq>:ack`. Bulk `config:`/`map:` payloads are handled BEFORE
  the split-parse (they contain colons/base64 and must never go through it).

### Known open items
- Master failover (slave takeover) deferred - heartbeats lay groundwork.
- `mine`/`skipBuilding` are empty on CarpetPrinter; kept in protocol.
- StaircasedPrinter multi-bot still deprecated (stubs for all new interface methods).
- Manual "Send Setup" GUI button may be debounce-skipped if payload unchanged and all
  slaves already have it (logged) - change the setup in-game to force a re-send.

---

## 1. Background

The mod is a Fabric/Meteor Client addon (`com.julflips.nerv_printer`, MC 1.21.11) with two
mapart printer modules: `CarpetPrinter` and `StaircasedPrinter`, both implementing
`interfaces.MapPrinter`.

The original multi-bot coordination (`SlaveSystem`) worked over **server chat DMs**:
one bot was the *master*, the others *slaves*, commands were sent as whispers
(`interval`, `pause`, `start`, `skip`, `mine`, `register`, `accept`, `finished`, `error`).
This was fragile (rate limiting, DM signature parsing per server, same NBT files needed
on every client).

## 2. Transport replaced: server DMs → WebSockets

- Dependency: `org.java-websocket:Java-WebSocket:1.5.7` + `org.slf4j:slf4j-api:2.0.13`,
  bundled via loom `include` (nested jars confirmed in the built jar).
- New files:
  - `utils/MasterSocketServer.java` — WebSocket server hosted by the master
    (binds all interfaces on `master-port`, default 8080).
  - `utils/SlaveSocketClient.java` — WebSocket client used by slaves.
- Wire format for socket messages: `("s"|"m"):<senderName>:<command>[:args]`.
  `s:` = slave→master (conn != null), `m:` = master→slave (conn == null).
  Command set preserved from the DM era; all coordination runs on this transport.
- `SlaveSystem` (static, event-subscribed) is the hub: connection→name map, dicts
  (`slaves`, `activeSlavesDict`, `finishedSlavesDict`), reconnect logic, invite queue.
  All WS callbacks are marshaled onto the game thread via `mc.execute(...)`.


## 3. Carpet hivemind (the main feature this session)

Carpet-only. `StaircasedPrinter` got no-op stubs for the new interface methods
(hivemind not supported there) and is effectively deprecated multi-bot-wise.

New `MapPrinter` interface methods: `broadcastSetup()`, `slaveRegistered(String)`,
`applySetup(String)`, `applyMapData(String, String)`, `goToCorner(int)`,
`onIntervalsReassigned()`, `isFinalizePhase()`.

### One-setup broadcast (`config:` message)
- Existing `ConfigSerializer`/`ConfigDeserializer` gained string variants
  (`toJsonString`, `readFromString`) — full station setup (map corner, reset button +
  approach pos, dump station with yaw/pitch, cartography table, finished-map chest,
  all material chests with item ids, 4 perimeter corners) is sent as one compact JSON
  message over the socket.
- Master sends it automatically when slaves register after its setup completes
  (`pendingSetupBroadcast` flag fires on the next tick when `state==SelectingChests`
  and `hasFullSetup()`), plus a manual **Send Setup** GUI button.
- Slaves populate the exact same CarpetPrinter fields and skip all `Selecting*` states.

### NBT transfer (`map:` message)
- Master broadcasts `map:<fileName>:<base64 of compressed NBT>` at map start
  (`startBuilding`) and to late joiners. Slaves write it to their own map folder and
  reuse the untouched `loadNBTFile()` path. **Slaves need no local NBT files.**
- Slaves in slave mode (`master-address` non-empty) never run `prepareNextMapFile`;
  their activation short-circuits into `State.AwaitSetup`.

### Dynamic row assignment
- `generateIntervals()` (message `interval:<start>:<end>`) splits rows 0-127 across
  master + slaves; fires on: slave accept, disconnect, removal, map start.
  A hook `onIntervalsReassigned()` re-activates parked (finished) slaves that got new
  rows — guarded by `isFinalizePhase()` so nobody builds during finalize/wipe.
- Zero slaves → master falls back to the full 0-127 interval automatically (single-user
  fallback, message logged). Re-split is safe any time because `calculateBuildingPath`
  skips already-placed lines via `MapAreaCache`.
- `AwaitMasterAllBuilt` (master waiting) now checks `hasUnfinishedRowsInInterval()`
  (ONLY the master's own interval) and resumes building if its own rows became
  unfinished; otherwise it waits silently (this fixed a chat-spam bug: full-map scan
  made it churn every tick and spam `Waiting for slaves to finish...`).

### Master-only finalize & wipe, corner parking
- New `finalizePhase` flag (set in `endBuilding`, cleared in `startBuilding`).
- When a slave finishes its rows it reports `finished`; the master (via
  `slaveFinished`) assigns it a perimeter corner round-robin (`goToCorner:<i>`,
  counter reset per map in `endBuilding`). The slave walks there via the new
  `parkCorner` checkpoint action and stands (AwaitSlaveNextMap). Leftover materials
  are kept (no dump trip) so re-assignment is instant.
- Master alone runs dump → cartography → finished-map chest → wipe sequence.
- Slaves restock on demand: when a required material is missing,
  `tryPlacingBlock` (slave branch) computes requirements for their own interval,
  subtracts carried items, and injects a `refill` checkpoint to the nearest material
  chest from the received `materialDict`. No dump phase for slaves.


## 4. Bootstrap invite over server DMs (discovery only)

- Chat is used exactly once per slave: the master GUI button **Invite players in
  range** scans players in render distance, resolves its own LAN IPv4
  (`resolveLocalIp()` — site-local, `127.0.0.1` fallback), and sends each a DM
  `hivemind:<ip>:<port>` (queued, spaced 2s apart).
- Slaves parse chat/system packets ONLY for `hivemind:` and `hivemindaccept`
  (prefix/suffix parsing restored, but scoped to these two commands; settings
  `direct-message-command` (default `w`), `sender-prefix`, `sender-suffix` are
  back under Carpet's Multi User group). Guards: not hosting, no open connection,
  no master yet, sender visible in render distance.
- Slave connects, auto-registers, replies `hivemindaccept` via DM; master prints
  `Slave <name> joined the hivemind via invite.` (registration definitely completed)
  or `<name> confirmed the invite (registration pending) - retrying registration...`
  and re-sends `register` to that socket (self-heal).
- Manual `master-address`/`master-port` settings remain as fallback.

## 5. Role selection & persistence

- **Empty `master-address` = host (master). Non-empty = slave.** The master must have
  it empty; a helper with it empty will also try to host on 8080 and conflict.
- `setupSlaveSystem(module, port, address[, dmCommand, prefix, suffix])`:
  - Same module **instance** (re-toggle of the same module): keeps ALL hive state
    (slaves, dicts, connections, master) — only settings refresh; restarts sockets
    only if port/address changed. Fixed the "module toggling wipes the hive" bug.
  - Different module instance: full reset (modules can't poison each other).
  - `healStaleRegistrations()` drops registered slaves with dead sockets + re-splits.
- Master-mode watchdog in `onTick`: if the server isn't running, retries the bind
  every 5s (bind failures are reported: `Socket server failed: <err> - is another
  instance still hosting on port <port>?`).
- Slave reconnect: retries every 5s, always with a **fresh client object**.

## 6. Crash fixes (both verified in the user's crash log)

1. **Chest-interruption crash** (`IndexOutOfBoundsException` clicking slot 49 in a
   46-slot menu, `CarpetPrinter.onTick` restock backlog): `restockBacklogSlots` holds
   indices from a previous chest inventory; interruptions (Escape) or chest size
   changes made them stale. Now bounds-checked per click: on stale → warn
   `Stale chest slots detected (screen changed). Re-opening the chest...`, clear
   backlog, re-interact with `lastInteractedBlockPos`. The `refill` checkpoint also
   clears the backlog before opening a chest.
2. **GUI NPE on duplicates** (`SlaveTableController.rebuild` unboxing null Boolean):
   reconnecting slaves could double-register (accept handler added blindly). Now:
   `accept` is idempotent (logs `Ignored duplicate registration from: <name>`),
   disconnect cleanup and healing use `removeIf`, and `rebuild()` renders a
   de-duplicated view (`LinkedHashSet`) and reads dicts via `Boolean.TRUE.equals(...)`.

## 7. Diagnostics / UX

- Local chat messages at every step (connect attempts, failures with reason, success,
  lost connection, invite flow, interval assignment `Received rows <s>-<e> from
  master.`, map reception, stale slots, setup broadcast feedback).
- **Hivemind Status** GUI button (carpet) — prints hosting/port, open connections,
  registered slaves + finished flag, pending sockets; slave side prints master and
  connection state. Also auto-printed after each module activation.
- Auto-register: master sends `register` to every new connection automatically
  (Register button kept as fallback for staircased only; carpet uses Invite).

## 8. Known facts / gotchas for the next agent

- `SlaveSystem` is fully static and shared by BOTH modules — keep the module-instance
  check in `setupSlaveSystem` intact.
- Java-WebSocket 1.5.7: `WebSocketServer` has no `isRunning()`/`isClosed()` — the mod
  tracks its own `volatile serverStarted` flag set from `onStart`/cleared on stop/error.
- `WebSocketClient.connect()` blocks — it's always run on a dedicated thread, and a
  fresh client object is created per retry.
- `SlaveTableController` is recreated per GUI open and stored in
  `SlaveSystem.tableController`; `rebuild()` is the live-update mechanism.
- The master's own chat name is embedded in `m:`-prefixed messages; slaves trust the
  socket peer as master (sender name comes from the first message).
- Documentation updated: `Documentation/CarpetGuide.md` (hivemind + invite flow).
  `StaircasedGuide.md` still describes the old static-interval WS flow (deprecated).

## 9. Suggested next steps (not yet done)

- Slaves standing at perimeter corners may physically block the master's wipe path —
  consider having parked slaves step one block back/off the corner.
- Uneven-workload rebalance: currently rebalancing only happens on disconnect/accept/
  map start; a proactive "steal rows from slow slaves" rebalance could be added.
- Master stuck waiting (`AwaitMasterAllBuilt`) if a slave wedges in restock — consider
  a timeout/manual nudge.
- StaircasedPrinter multi-bot is deprecated but still functional over sockets; remove
  or migrate it if desired.
- IPv6-only networks are not supported by the invite (IPv4 site-local only).

