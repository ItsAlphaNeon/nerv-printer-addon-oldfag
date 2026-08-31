
# Carpet Printer

The Carpet Printer allows you to build 2D carpet mapart from NBT files.
## Setup
To get the program running we first need to build an area where we will build one 1x1 map at a time. It could look like this but could use any other mechanism to clear the carpets:
![Setup](MapArea.png)

A Restock Station is required to refill the bot inventory. Ideally it is at the north side of the area. All essential components are labeled here:
![Setup](RestockStation.png)

Make sure it fulfills the following points:
- The fluid dispensers and lighting should cover the whole 128x128 MapArea.
- Avoid having grass blocks on the map area since it can lead to mobs spawning in certain biomes.
- The Restock Station should have a DumpStation, FinishedMapChest, MapMaterialChest, Reset Trapped Chest, and Cartography Table. The terms are explained below.
- Avoid having the bot pick up old carpets while restocking. The simplest way to avoid that is to place the carpet dupers a few blocks away from the Map Area and seperate them using slabs (also avoids "noob line").
- Make sure the server loads the entire map when resetting
- If Phantoms are on you need a glass ceiling.
- If you play on hard difficulty don't forget the regeneration 2 beacons

**A litematica file with an example Map Area can be found [here](CarpetPrinter.litematic).**

### Special blocks
Let's go over all the special blocks we need at the restock station.

#### DumpStation
The bot will throw any excess in here. It doesn't matter if you destroy or sort the items as long as the bot won't pick them up again while restocking.

#### FinishedMapChest
Pretty self-explanatory. The bot will put the maps here.

#### MapMaterialChest
This chest should contain empty maps and glass panes for the bot to lock new maps.

#### Reset Trapped Chest
We use a trapped chest to activate the dispensers for a short amount of time. In contrast to a button, it is not destroyed by water, and we can easily confirm that the reset was activated.

### Load NBT files
When the module is started for the first time a "nerv-printer" folder is created in your Minecraft directory. Put in as many 2D 1x1 NBT files as you like. Keep in mind the bot will process them in alphabetical order.
## Workflow
The addon follows these 3 steps:

1. Register important blocks
2. Build Map
3. Create Map Item


### Register important blocks
The module will prompt you to interact with all necessary blocks. Chests only have to be selected once even though the rendered box might only highlight half of the chest. When you are done, interact with one of the start-blocks specified in the start-blocks setting (default are all buttons) to start printing. Every inventory slot containing nothing or carpets will be marked as a slot for future carpets.

### Build Map
The bot will build the map line by line. It calculates the maximum area he can cover with carpets using the free slots he has available and restocks accordingly. When one color is empty he dumps the remaining carpets into the DumpStation and the cycle repeats.

### Create Map Item
When the map is finished the bot grabs an empty map and glass pane from the MapMaterialChest and walks a small circle in the center to fill it. Depending on your render distance this step might be unnecessary. After storing the map the bot will trigger the reset and start with the next nbt file.

If you still need help: There is a YouTube video demonstrating the process (in spanish):

[![Carpet Printer](https://img.youtube.com/vi/4jPSDu2ELxc/0.jpg)](https://www.youtube.com/watch?v=4jPSDu2ELxc)

## Optional

### Save and Load Configurations
Register the blocks as usual, then press **Save Config** in the module settings when you are finished.  
To load a configuration, simply select the file and press the **Load** button. Printing is started in the usual way.

### Multi-User Printing (Hivemind)
The printer can orchestrate multiple accounts simultaneously to print a map on the same map area.
One bot acts as the **master** and works alongside the other **slaves**, coordinating everything over WebSocket connections (no server chat / DMs involved, so no rate limiting or DM signature issues).

- Only **one setup is needed**, on the master: map area, reset button, perimeter corners, dump station, cartography table, finished-map chest and material chests are all transmitted to every slave automatically (`Send Setup` button / auto on slave registration).
- Only the master loads **nbt files** - the map data is transmitted to the slaves over WebSocket as well. Slaves need no local nbt files.
- Rows (0-127) are **dynamically assigned** to master + slaves. If a slave disconnects, its rows are re-split among the remaining bots instantly (the master falls back to building the full map alone until slaves reconnect). Partially built lines are picked up automatically.
- Only the **master finalizes**: it dumps, crafts the map and **wipes the map area** (reset button + perimeter walk). When a slave finishes its rows it reports `finished` and parks at one of the assigned **perimeter corners**, keeping its leftover materials so it can be re-assigned instantly.
- Slaves **restock on demand**: they use their current materials as long as possible and only walk to the material chests (positions received from the master) when they can no longer finish their rows.

**Setup:**
1. On the **master** bot leave the *master-address* setting empty and pick a *master-port* (default 8080).
2. On **every** slave bot just enable the Carpet Printer module - no address is needed if you use the invite flow.
3. Enable the module on every bot. The master starts a WebSocket server on activation; slaves stay in slave mode until invited.
4. Complete the station setup **on the master only** (or load a config).
5. Move all bots into render distance of each other, then press **Invite players in range** on the master. The master sends each nearby bot a one-time DM containing its IP and port; slaves automatically connect to the WebSocket server, register themselves and confirm back (`Slave <name> joined the hivemind via invite.`). The setup is broadcast to them automatically.
   - Only needed if DMs are blocked on your server: manually set *master-address* to the master's IP on each slave bot (e.g. `127.0.0.1` on the same PC, otherwise the master's LAN IP) and use the same *master-port*.
6. Start the print in the usual way (`.startprinter`). The master transmits the map, splits the rows and starts everyone.
