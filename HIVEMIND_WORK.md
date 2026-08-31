# HIVEMIND_WORK.md — Session Summary (2026-08-30)

This file documents everything done in this working session for the next agent to pick up.
Everything below is implemented, compiles, and is built into `nerv-printer-1.21.11.jar`
(via `build-no-test.bat`, which builds and copies the jar to 3 Prism Launcher instances:
`Nerv Printer` (master), `Nerv Printer (Helper)`, `Nerv Printer (Helper 2)`).

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

