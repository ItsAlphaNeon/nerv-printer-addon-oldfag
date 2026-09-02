package com.julflips.nerv_printer.modules;

import com.julflips.nerv_printer.Addon;
import com.julflips.nerv_printer.interfaces.MapPrinter;
import com.julflips.nerv_printer.utils.*;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.utils.StarscriptTextBoxRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.apache.commons.lang3.tuple.Triple;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CarpetPrinter extends Module implements MapPrinter {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced", false);
    private final SettingGroup sgMultiUser = settings.createGroup("Multi User", false);
    private final SettingGroup sgError = settings.createGroup("Error Handling");
    private final SettingGroup sgRender = settings.createGroup("Render", false);

    private final Setting<Integer> linesPerRun = sgGeneral.add(new IntSetting.Builder()
        .name("lines-per-run")
        .description("How many lines to place in parallel per run.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("The maximum range you can place carpets around yourself.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Double> minPlaceDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-place-distance")
        .description("The minimal distance a placement has to have to the player. Avoids placements colliding with the player.")
        .defaultValue(0.8)
        .min(0)
        .sliderRange(0, 2)
        .build()
    );

    private final Setting<List<Block>> ignoredBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("ignored-Blocks")
        .description("Blocks types that will not be placed. Useful to print semi-transparent maps.")
        .defaultValue()
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("How many milliseconds to wait after placing.")
        .defaultValue(50)
        .min(1)
        .sliderRange(10, 300)
        .build()
    );

    

    private final Setting<Integer> mapFillSquareSize = sgGeneral.add(new IntSetting.Builder()
        .name("map-fill-square-size")
        .description("The radius of the square the bot fill walk to explore the map.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 50)
        .build()
    );

    private final Setting<SprintMode> sprinting = sgGeneral.add(new EnumSetting.Builder<SprintMode>()
        .name("sprint-mode")
        .description("How to sprint.")
        .defaultValue(SprintMode.NotPlacing)
        .build()
    );

    public final Setting<Boolean> activationReset = sgGeneral.add(new BoolSetting.Builder()
        .name("activation-reset")
        .description("Resets all values when module is activated or the client relogs. Disable to be able to pause.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate when placing a block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> startNorthToSouth = sgGeneral.add(new BoolSetting.Builder()
        .name("north-to-south")
        .description("Start printing on the north side and go south. Flipped if disabled.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> afkAnchor = sgGeneral.add(new BoolSetting.Builder()
        .name("afk-anchor")
        .description("Hivemind: when the master finishes its rows it stands at the AFK Spot to keep the carpet dupers loaded, and delegates finalize/wipe to a slave. Disable for raw speed (no duper anchoring).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customFolderPath = sgGeneral.add(new BoolSetting.Builder()
        .name("custom-folder-path")
        .description("Allows to set a custom path to the nbt folder.")
        .defaultValue(false)
        .onChanged((value) -> warnPathChanged())
        .build()
    );

    public final Setting<String> mapPrinterFolderPath = sgGeneral.add(new StringSetting.Builder()
        .name("nerv-printer-folder-path")
        .description("The path to your nerv-printer directory.")
        .defaultValue("C:\\Users\\(username)\\AppData\\Roaming\\.minecraft\\nerv-printer")
        .wide()
        .renderer(StarscriptTextBoxRenderer.class)
        .visible(() -> customFolderPath.get())
        .onChanged((value) -> warnPathChanged())
        .build()
    );

    private final Setting<Boolean> useDefaultConfigFile = sgGeneral.add(new BoolSetting.Builder()
        .name("use-default-config-file")
        .description("Load a config file when the module is enabled.")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> configFileName = sgGeneral.add(new StringSetting.Builder()
        .name("config-file-name")
        .description("The config file that is loaded  when the module is enabled.")
        .defaultValue("carpet-printer-config.json")
        .wide()
        .renderer(StarscriptTextBoxRenderer.class)
        .visible(() -> useDefaultConfigFile.get())
        .build()
    );

    //Advanced

    private final Setting<Integer> preRestockDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("pre-restock-delay")
        .description("How many ticks to wait to take items after opening the chest.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> invActionDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("inventory-action-delay")
        .description("How many ticks to wait between each inventory action (moving a stack).")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> postRestockDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("post-restock-delay")
        .description("How many ticks to wait after restocking.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> preSwapDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("pre-swap-delay")
        .description("How many ticks to wait before swapping an item into the hotbar.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> postSwapDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("post-swap-delay")
        .description("How many ticks to wait after swapping an item into the hotbar.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> buttonPressDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("button-press-delay")
        .description("How many ticks to wait after pressing the reset button before walking to the next checkpoint.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> buttonPressRetries = sgAdvanced.add(new IntSetting.Builder()
        .name("button-press-retries")
        .description("How many times to retry the reset button press if it was not confirmed via a block update.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> retryInteractTimer = sgAdvanced.add(new IntSetting.Builder()
        .name("retry-interact-timer")
        .description("How many ticks to wait for chest response before interacting with it again.")
        .defaultValue(80)
        .min(1)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Integer> posResetTimeout = sgAdvanced.add(new IntSetting.Builder()
        .name("pos-reset-timeout")
        .description("How many ticks to wait after the player position was reset by the server.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Double> checkpointBuffer = sgAdvanced.add(new DoubleSetting.Builder()
        .name("checkpoint-buffer")
        .description("The buffer area of the checkpoints. Larger means less precise walking, but might be desired at higher speeds.")
        .defaultValue(0.2)
        .min(0)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<Boolean> breakCarpetAboveReset = sgAdvanced.add(new BoolSetting.Builder()
        .name("break-carpet-above-reset")
        .description("Break the carpet above the reset button before activating. Useful when interactions trough blocks are not allowed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> moveToFinishedFolder = sgAdvanced.add(new BoolSetting.Builder()
        .name("move-to-finished-folder")
        .description("Moves finished NBT files into the finished-maps folder in the nerv-printer folder.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnFinished = sgAdvanced.add(new BoolSetting.Builder()
        .name("disable-on-finished")
        .description("Disables the printer when all nbt files are finished.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugPrints = sgAdvanced.add(new BoolSetting.Builder()
        .name("debug-prints")
        .description("Prints additional information.")
        .defaultValue(false)
        .build()
    );

    //Multi User

    private final Setting<Integer> masterPort = sgMultiUser.add(new IntSetting.Builder()
        .name("master-port")
        .description("Port used for the WebSocket connection between master and slaves.")
        .defaultValue(8080)
        .min(1)
        .sliderRange(1, 65535)
        .onChanged(SlaveSystem::restartServer)
        .build()
    );

    private final Setting<String> masterAddress = sgMultiUser.add(new StringSetting.Builder()
        .name("master-address")
        .description("IP address of the master bot. Leave empty to host the WebSocket server as master.")
        .defaultValue("")
        .onChanged((value) -> SlaveSystem.masterAddress = value)
        .build()
    );

    private final Setting<String> advertisedIp = sgMultiUser.add(new StringSetting.Builder()
        .name("advertised-ip")
        .description("Master only: the IP sent to slaves in invites. Empty = auto-detect. Set this if slaves"
            + " cannot connect - auto-detection can pick a virtual adapter (VirtualBox/Hyper-V/WSL/VPN)"
            + " whose IP other PCs cannot reach. Use this PC's real LAN IP (e.g. 192.168.1.50).")
        .defaultValue("")
        .onChanged((value) -> SlaveSystem.advertisedIpOverride = value)
        .build()
    );

    // Chat transport used ONLY for the bootstrap invite (hivemind:<ip>:<port>)

    private final Setting<String> directMessageCommand = sgMultiUser.add(new StringSetting.Builder()
        .name("direct-message-command")
        .description("The command used to send the one-time hivemind invite via server DMs.")
        .defaultValue("w")
        .onChanged((value) -> SlaveSystem.directMessageCommand = value)
        .build()
    );

    private final Setting<String> senderPrefix = sgMultiUser.add(new StringSetting.Builder()
        .name("sender-prefix")
        .description("The text that always comes before the name of the sender of every direct message.")
        .defaultValue("")
        .onChanged((value) -> SlaveSystem.senderPrefix = value)
        .build()
    );

    private final Setting<String> senderSuffix = sgMultiUser.add(new StringSetting.Builder()
        .name("sender-suffix")
        .description("The text that is always between the name of the sender and the actual message.")
        .defaultValue(" whispers: ")
        .onChanged((value) -> SlaveSystem.senderSuffix = value)
        .build()
    );

    //Error Handling

    private final Setting<Boolean> logErrors = sgError.add(new BoolSetting.Builder()
        .name("log-errors")
        .description("Prints warning when a misplacement is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ErrorAction> errorAction = sgError.add(new EnumSetting.Builder<ErrorAction>()
        .name("error-action")
        .description("What to do when a misplacement is detected.")
        .defaultValue(ErrorAction.Repair)
        .build()
    );

    //Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the selected areas.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderChestPositions = sgRender.add(new BoolSetting.Builder()
        .name("render-chest-positions")
        .description("Highlights the selected chests.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Boolean> renderOpenPositions = sgRender.add(new BoolSetting.Builder()
        .name("render-open-positions")
        .description("Indicate the position the bot will go to in order to interact with the chest.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Boolean> renderCheckpoints = sgRender.add(new BoolSetting.Builder()
        .name("render-checkpoints")
        .description("Indicate the checkpoints the bot will traverse.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Boolean> renderSpecialInteractions = sgRender.add(new BoolSetting.Builder()
        .name("render-special-interactions")
        .description("Indicate the position where the reset button and cartography table will be used.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Double> indicatorSize = sgRender.add(new DoubleSetting.Builder()
        .name("indicator-size")
        .description("How big the rendered indicator will be.")
        .defaultValue(0.15)
        .min(0)
        .sliderRange(0, 1)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("The render color.")
        .defaultValue(new SettingColor(22, 230, 206, 155))
        .visible(() -> render.get())
        .build()
    );

    int timeoutTicks;
    int buttonPressTicks;
    int buttonPressAttempts;
    int cornerSelectCooldown;
    int interactTimeout;
    int toBeSwappedSlot;
    int awaitClearLogTicks;
    boolean debugWipeOnly;
    boolean expectButtonPress;
    boolean isWiping;
    long lastTickTime;
    boolean closeNextInvPacket;
    State state;
    State oldState;
    State debugPreviousState;
    Pair<Integer, Integer> workingInterval;     //Interval the bot should work in 0-127
    Pair<BlockPos, Vec3d> resetButton;
    Pair<BlockPos, Vec3d> cartographyTable;
    Pair<BlockPos, Vec3d> finishedMapChest;
    Pair<BlockPos, Vec3d> afkSpot;                               //Chunk anchor / duper parking spot
    ArrayList<Pair<BlockPos, Vec3d>> mapMaterialChests;
    Pair<Vec3d, Pair<Float, Float>> dumpStation;                    //Pos, Yaw, Pitch
    BlockPos mapCorner;
    BlockPos tempChestPos;
    BlockPos lastInteractedBlockPos;
    BlockPos miningPos;
    Item lastSwappedMaterial;
    InventoryS2CPacket toBeHandledInvPacket;
    HashMap<Integer, Pair<Block, Integer>> blockPaletteDict;       //Maps palette block id to the Minecraft block and amount
    HashMap<Item, ArrayList<Pair<BlockPos, Vec3d>>> materialDict;  //Maps item to the chest pos and the open position
    ArrayList<Integer> availableSlots;
    ArrayList<Integer> availableHotBarSlots;
    ArrayList<Triple<Item, Integer, Integer>> restockList;        //Material, Stacks, Raw Amount
    ArrayList<BlockPos> checkedChests;
    ArrayList<Pair<Vec3d, Pair<String, BlockPos>>> checkpoints;    //(GoalPos, (checkpointAction, targetBlock))
    ArrayList<File> startedFiles;
    ArrayList<Integer> restockBacklogSlots;
    ArrayList<BlockPos> knownErrors;
    ArrayList<Pair<BlockPos, Vec3d>> perimeterCorners;
    Block[][] map;
    File mapFolder;
    File mapFile;
    boolean pendingStart;               // Slave: start received before the map transfer
    boolean pendingSetupBroadcast;      // Master: broadcast config as soon as the setup completes
    boolean finalizePhase;              // Master: currently dumping/cartoing/wiping (slaves are parked)
    int cornerCounter;                  // Master: round-robin counter for perimeter corner assignment

    public CarpetPrinter() {
        super(Addon.CATEGORY, "carpet-printer", "Automatically builds 2D carpet maps from nbt files.");
    }

    @Override
    public void onActivate() {
        lastTickTime = System.currentTimeMillis();
        if (!activationReset.get() && checkpoints != null) {
            return;
        }
        materialDict = new HashMap<>();
        availableSlots = new ArrayList<>();
        availableHotBarSlots = new ArrayList<>();
        restockList = new ArrayList<>();
        checkedChests = new ArrayList<>();
        checkpoints = new ArrayList<>();
        startedFiles = new ArrayList<>();
        restockBacklogSlots = new ArrayList<>();
        knownErrors = new ArrayList<>();
        perimeterCorners = new ArrayList<>();
        resetButton = null;
        mapCorner = null;
        lastInteractedBlockPos = null;
        miningPos = null;
        cartographyTable = null;
        finishedMapChest = null;
        afkSpot = null;
        finalizeDelegate = null;
        finalizeWatchdogTicks = 0;
        finalizingForMaster = false;
        mapMaterialChests = new ArrayList<>();
        dumpStation = null;
        lastSwappedMaterial = null;
        toBeHandledInvPacket = null;
        closeNextInvPacket = false;
        timeoutTicks = 0;
        interactTimeout = 0;
        buttonPressTicks = 0;
        buttonPressAttempts = 0;
        cornerSelectCooldown = 0;
        toBeSwappedSlot = -1;
        awaitClearLogTicks = 0;
        debugWipeOnly = false;
        expectButtonPress = false;
        isWiping = false;
        pendingStart = false;
        pendingSetupBroadcast = false;
        finalizePhase = false;
        cornerCounter = 0;
        oldState = null;
        debugPreviousState = null;

        setInterval(new Pair<>(0, 127));
        // Initialize Slave System settings
        SlaveSystem.advertisedIpOverride = advertisedIp.get();
        SlaveSystem.setupSlaveSystem(this, masterPort.get(), masterAddress.get(),
            directMessageCommand.get(), senderPrefix.get(), senderSuffix.get());

        if (!customFolderPath.get()) {
            mapFolder = new File(Utils.getMinecraftDirectory(), "nerv-printer");
        } else {
            mapFolder = new File(mapPrinterFolderPath.get());
        }
        if (!Utils.createFolders(mapFolder)) {
            toggle();
            return;
        }

        // Hivemind slave mode: no local setup and no local nbt files are needed.
        // The master transmits the station config and the map data via WebSocket.
        if (!SlaveSystem.isMasterMode()) {
            state = State.AwaitSetup;
            info("Hivemind slave mode: waiting for setup from master " + masterAddress.get() + "...");
            return;
        }

        if (!prepareNextMapFile()) return;

        state = State.SelectingMapArea;
        if (useDefaultConfigFile.get()) {
            File configFolder = new File(mapFolder, "_configs");
            if (!loadConfig(new File(configFolder, configFileName.get()))) {
                info("Select the §aMap Building Area (128x128)");
            }
        } else {
            info("Select the §aMap Building Area (128x128)");
        }
    }

    @Override
    public void onDeactivate() {
        Utils.setForwardPressed(false);
        // Notify the hive so nobody waits for a bot that silently vanished:
        // a deactivated slave is unregistered + re-split; a deactivated master
        // pauses its slaves instead of leaving them building into the void.
        if (SlaveSystem.isHiveActive()) {
            if (SlaveSystem.isMasterMode()) {
                if (!SlaveSystem.slaves.isEmpty()) {
                    SlaveSystem.sendToAllSlaves("pause");
                    HiveLog.log("MASTER deactivated - all slaves paused");
                }
            } else if (SlaveSystem.isSlave()) {
                SlaveSystem.queueMasterDM("leaving");
            }
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (state == State.SelectingDumpStation && event.packet instanceof PlayerActionC2SPacket packet
            && (packet.getAction() == PlayerActionC2SPacket.Action.DROP_ITEM || packet.getAction() == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS)) {
            dumpStation = new Pair<>(mc.player.getEntityPos(), new Pair<>(mc.player.getYaw(), mc.player.getPitch()));
            state = State.SelectingFinishedMapChest;
            info("Dump Station selected. Select the §aFinished Map Chest");
            return;
        }
        if (!(event.packet instanceof PlayerInteractBlockC2SPacket packet) || state == null) return;
        switch (state) {
            case SelectingMapArea:
                BlockPos hitPos = packet.getBlockHitResult().getBlockPos().up();
                int adjustedX = Utils.getIntervalStart(hitPos.getX());
                int adjustedZ = Utils.getIntervalStart(hitPos.getZ());
                mapCorner = new BlockPos(adjustedX, hitPos.getY(), adjustedZ);
                MapAreaCache.reset(mapCorner);
                state = State.SelectingResetButton;
                info("Map Area selected. Right-click the §aReset Button §7used to toggle the water (first press to start water, second press to retract).");
                break;
            case SelectingResetButton:
                BlockPos blockPos = packet.getBlockHitResult().getBlockPos();
                if (MapAreaCache.getCachedBlockState(blockPos).getBlock() instanceof ButtonBlock) {
                    resetButton = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("Reset Button selected. Right-click the §a1st Perimeter Corner §7(any block at the first corner).");
                    state = State.SelectingPerimeterCorner1;
                }
                break;
            case SelectingPerimeterCorner1:
                if (cornerSelectCooldown > 0) return;
                blockPos = packet.getBlockHitResult().getBlockPos();
                perimeterCorners.add(new Pair<>(blockPos, mc.player.getEntityPos()));
                info("Corner 1 selected. Right-click the §a2nd Perimeter Corner.");
                cornerSelectCooldown = 20;
                state = State.SelectingPerimeterCorner2;
                break;
            case SelectingPerimeterCorner2:
                if (cornerSelectCooldown > 0) return;
                blockPos = packet.getBlockHitResult().getBlockPos();
                perimeterCorners.add(new Pair<>(blockPos, mc.player.getEntityPos()));
                info("Corner 2 selected. Right-click the §a3rd Perimeter Corner.");
                cornerSelectCooldown = 20;
                state = State.SelectingPerimeterCorner3;
                break;
            case SelectingPerimeterCorner3:
                if (cornerSelectCooldown > 0) return;
                blockPos = packet.getBlockHitResult().getBlockPos();
                perimeterCorners.add(new Pair<>(blockPos, mc.player.getEntityPos()));
                info("Corner 3 selected. Right-click the §a4th Perimeter Corner.");
                cornerSelectCooldown = 20;
                state = State.SelectingPerimeterCorner4;
                break;
            case SelectingPerimeterCorner4:
                if (cornerSelectCooldown > 0) return;
                blockPos = packet.getBlockHitResult().getBlockPos();
                perimeterCorners.add(new Pair<>(blockPos, mc.player.getEntityPos()));
                info("Perimeter Corners selected. Select the §aCartography Table.");
                cornerSelectCooldown = 20;
                state = State.SelectingTable;
                break;
            case SelectingTable:
                blockPos = packet.getBlockHitResult().getBlockPos();
                if (MapAreaCache.getCachedBlockState(blockPos).getBlock().equals(Blocks.CARTOGRAPHY_TABLE)) {
                    cartographyTable = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("Cartography Table selected. Please throw an item into the §aDump Station.");
                    state = State.SelectingDumpStation;
                }
                break;
            case SelectingFinishedMapChest:
                blockPos = packet.getBlockHitResult().getBlockPos();
                if (MapAreaCache.getCachedBlockState(blockPos).getBlock() instanceof AbstractChestBlock) {
                    finishedMapChest = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("Finished Map Chest selected. Select the §aAFK Spot §7(near the carpet dupers - the master will stand here while slaves build).");
                    state = State.SelectingAfkSpot;
                }
                break;
            case SelectingAfkSpot:
                blockPos = packet.getBlockHitResult().getBlockPos();
                afkSpot = new Pair<>(blockPos, mc.player.getEntityPos());
                info("AFK Spot selected. Select all §aMap- and Material-Chests. Type §a.startprinter §7to start printing.");
                state = State.SelectingChests;
                break;
            case SelectingChests:
                blockPos = packet.getBlockHitResult().getBlockPos();
                BlockState blockState = MapAreaCache.getCachedBlockState(blockPos);
                if (blockState.getBlock().equals(Blocks.CHEST)) {
                    tempChestPos = blockPos;
                    state = State.AwaitRegisterResponse;
                }
                break;
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (state == null) return;

        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            timeoutTicks = posResetTimeout.get();
            if (timeoutTicks > 0) Utils.setForwardPressed(false);
        }

        // Confirm reset button presses via block updates (the server sends one when the button pulses)
        if (event.packet instanceof BlockUpdateS2CPacket blockUpdate) {
            if (state == State.AwaitButtonPress && expectButtonPress && resetButton != null
                && blockUpdate.getPos().equals(resetButton.getLeft())) {
                BlockState blockState = blockUpdate.getState();
                if (blockState.getBlock() instanceof ButtonBlock && blockState.get(ButtonBlock.POWERED)) {
                    expectButtonPress = false;
                    if (debugPrints.get()) info("Reset button press confirmed via block update.");
                }
            }
        }

        if (!(event.packet instanceof InventoryS2CPacket packet)) return;

        if (state.equals(State.AwaitRegisterResponse)) {
            //info("Chest content received.");
            Item foundItem = null;
            boolean isMixedContent = false;
            for (int i = 0; i < packet.contents().size() - 36; i++) {
                ItemStack stack = packet.contents().get(i);
                if (!stack.isEmpty()) {
                    if (foundItem != null && foundItem != stack.getItem().asItem()) {
                        isMixedContent = true;
                    }
                    foundItem = stack.getItem().asItem();
                    if (foundItem == Items.MAP || foundItem == Items.GLASS_PANE) {
                        info("Registered §aMapChest");
                        mapMaterialChests = Utils.saveAdd(mapMaterialChests, tempChestPos, mc.player.getEntityPos());
                        state = State.SelectingChests;
                        return;
                    }
                }
            }
            if (foundItem == null) {
                warning("No items found in chest.");
                state = State.SelectingChests;
                return;
            }
            if (isMixedContent) {
                warning("Different items found in chest. Please only have one item type in the chest.");
                state = State.SelectingChests;
                return;
            }
            info("Registered §a" + foundItem.getName().getString());
            if (!materialDict.containsKey(foundItem)) materialDict.put(foundItem, new ArrayList<>());
            ArrayList<Pair<BlockPos, Vec3d>> oldList = materialDict.get(foundItem);
            ArrayList newChestList = Utils.saveAdd(oldList, tempChestPos, mc.player.getEntityPos());
            materialDict.put(foundItem, newChestList);
            state = State.SelectingChests;
        }

        List<State> allowedStates = Arrays.asList(State.AwaitRestockResponse, State.AwaitMapChestResponse,
            State.AwaitCartographyResponse, State.AwaitFinishedMapChestResponse);
        if (allowedStates.contains(state)) {
            toBeHandledInvPacket = packet;
            timeoutTicks = preRestockDelay.get();
            restockStallTicks = 0; // chest data arrived - not stuck
        }
    }

    private void handleInventoryPacket(InventoryS2CPacket packet) {
        if (debugPrints.get()) info("Handling InvPacket for: " + state);
        closeNextInvPacket = true;
        switch (state) {
            case AwaitRestockResponse:
                interactTimeout = 0;
                // Interrupted-restock recovery: if the restock list is exhausted
                // (everything already counted as taken before an interrupt), just
                // resume - previously this fell through and deadlocked forever.
                if (restockList.isEmpty()) {
                    warning("Restock list is empty - resuming building.");
                    HiveLog.log("RESTOCK recovery: restockList empty at chest - resuming");
                    checkedChests.clear();
                    timeoutTicks = postRestockDelay.get();
                    state = State.Walking;
                    break;
                }
                boolean foundMaterials = false;
                if (restockList.get(0).getMiddle() > 0) {
                    List<Integer> slots = IntStream.rangeClosed(0, packet.contents().size() - 37)
                        .boxed()
                        .collect(Collectors.toList());
                    Collections.shuffle(slots);

                    for (int slot : slots) {
                        ItemStack stack = packet.contents().get(slot);

                        if (restockList.get(0).getMiddle() == 0) {
                            foundMaterials = true;
                            break;
                        }
                        if (!stack.isEmpty() && stack.getCount() == 64) {
                            //info("Taking Stack of " + restockList.get(0).getLeft().getName().getString());
                            foundMaterials = true;
                            int highestFreeSlot = Utils.findHighestFreeSlot(packet);
                            if (highestFreeSlot == -1) {
                                warning("No free slots found in inventory.");
                                checkpoints.add(0, new Pair(dumpStation.getLeft(), new Pair("dump", null)));
                                state = State.Walking;
                                return;
                            }
                            restockBacklogSlots.add(slot);
                            Triple<Item, Integer, Integer> oldTriple = restockList.remove(0);
                            restockList.add(0, Triple.of(oldTriple.getLeft(), oldTriple.getMiddle() - 1, oldTriple.getRight() - 64));
                        }
                    }
                } else {
                    // Nothing more needed from this chest (stale backlog was cleared
                    // after an interrupt) - finish the chest instead of deadlocking.
                    foundMaterials = true;
                    restockBacklogSlots.clear();
                }
                // End the chest visit when the backlog is empty: either nothing was
                // found here (find another chest) or everything needed was taken.
                if (restockBacklogSlots.isEmpty()) endRestocking();
                break;
            case AwaitMapChestResponse:
                int mapSlot = -1;
                int paneSlot = -1;
                //Search for map and glass pane
                for (int slot = 0; slot < packet.contents().size() - 36; slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (stack.getItem() == Items.MAP) mapSlot = slot;
                    if (stack.getItem() == Items.GLASS_PANE) paneSlot = slot;
                }
                if (mapSlot == -1 || paneSlot == -1) {
                    warning("Not enough Empty Maps/Glass Panes in Map Material Chest");
                    return;
                }
                interactTimeout = 0;
                timeoutTicks = postRestockDelay.get();
                Utils.getOneItem(mapSlot, false, availableSlots, availableHotBarSlots, packet);
                Utils.getOneItem(paneSlot, true, availableSlots, availableHotBarSlots, packet);
                mc.player.getInventory().setSelectedSlot(availableHotBarSlots.get(0));

                Vec3d center = mapCorner.add(map.length / 2 - 1, 0, map[0].length / 2 - 1).toCenterPos();
                checkpoints.add(new Pair(center, new Pair("fillMap", null)));
                state = State.Walking;
                break;
            case AwaitCartographyResponse:
                interactTimeout = 0;
                timeoutTicks = postRestockDelay.get();
                boolean searchingMap = true;
                for (int slot : availableSlots) {
                    if (slot < 9) {  //Stupid slot correction
                        slot += 30;
                    } else {
                        slot -= 6;
                    }
                    ItemStack stack = packet.contents().get(slot);
                    if (searchingMap && stack.getItem() == Items.FILLED_MAP) {
                        mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        searchingMap = false;
                    }
                }
                for (int slot : availableSlots) {
                    if (slot < 9) {  //Stupid slot correction
                        slot += 30;
                    } else {
                        slot -= 6;
                    }
                    ItemStack stack = packet.contents().get(slot);
                    if (!searchingMap && stack.getItem() == Items.GLASS_PANE) {
                        mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        break;
                    }
                }
                mc.interactionManager.clickSlot(packet.syncId(), 2, 0, SlotActionType.QUICK_MOVE, mc.player);
                checkpoints.add(new Pair(finishedMapChest.getRight(), new Pair("finishedMapChest", null)));
                state = State.Walking;
                break;
            case AwaitFinishedMapChestResponse:
                interactTimeout = 0;
                timeoutTicks = postRestockDelay.get();
                for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (stack.getItem() == Items.FILLED_MAP) {
                        mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        break;
                    }
                }
                // WIPE-COMPLETENESS GATE: never erase a canvas that isn't fully built.
                // Detects AIR GAPS, WRONG blocks and non-fluid leftovers - the old
                // scan only caught air gaps, so wrong colors silently ruined the
                // crafted map item. Applies to the master's own finalize AND the
                // delegated one - the last line of defense against wiping a bad map.
                ArrayList<BlockPos> issueBlocks = listBuildIssuesFullArea(25);
                if (!issueBlocks.isEmpty()) {
                    finalizeWipeBlockAttempts++;
                    warning("§cWIPE BLOCKED§7 - map incomplete: " + issueBlocks.size()
                        + "+ issue block(s). Repair round " + finalizeWipeBlockAttempts + "/3...");
                    HiveLog.log("WIPE BLOCKED (round " + finalizeWipeBlockAttempts + "): " + issueBlocks.size()
                        + "+ issue blocks, first: " + issueBlocks.get(0).toShortString());
                    if (finalizeWipeBlockAttempts >= 3) {
                        HiveLog.log("WIPE BLOCKED 3x - HOLDING. Map cannot be completed automatically;"
                            + " the wipe will NOT run until the issues are fixed manually.");
                        warning("Map still incomplete after 3 repair rounds - holding here. The map will NOT be wiped."
                            + " Fix the issues manually, then re-enable the module.");
                        return;
                    }
                    // Repair round: break wrong/leftover blocks (placement re-places
                    // them), walk to air gaps so the placement loop fills them, then a
                    // lineEnd re-scan so the next gate check sees the result.
                    for (BlockPos p : issueBlocks) {
                        BlockPos rel = p.subtract(mapCorner);
                        BlockState current = MapAreaCache.getVerifiedBlockState(p);
                        boolean needsBreak = map[rel.getX()][rel.getZ()] == null
                            || (current != null && !current.isAir());
                        checkpoints.add(new Pair(p.toCenterPos(),
                            new Pair<>(needsBreak ? "break" : "", needsBreak ? p : null)));
                    }
                    checkpoints.add(new Pair(issueBlocks.get(0).toCenterPos(), new Pair("lineEnd", null)));
                    state = State.Walking;
                    break;
                }
                finalizeWipeBlockAttempts = 0;
                if (breakCarpetAboveReset.get()) {
                    BlockPos abovePos = resetButton.getLeft().up();
                    if (MapAreaCache.getCachedBlockState(abovePos).getBlock() instanceof CarpetBlock) {
                        checkpoints.add(new Pair(resetButton.getRight(), new Pair("break", abovePos)));
                    }
                }
                if (!triggerWipeSequence()) {
                    warning("Cannot start the wipe sequence (missing reset button/perimeter corners). Moving to the next map...");
                    state = State.AwaitNBTFile;
                    break;
                }
                state = State.Walking;
                break;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (state == null) return;

        // Map chunk reception watchdog (slave): if a chunked map transfer stalls
        // (chunk never arrived), ask the master to re-send the whole map.
        // The partial buffer is kept so the re-send only has to fill gaps.
        if (pendingMapName != null && pendingMapChunks.size() < pendingMapTotal) {
            if (++pendingMapStallTicks > 100) {
                pendingMapStallTicks = 0;
                requestRemap(pendingMapName, "stalled at " + pendingMapChunks.size() + "/" + pendingMapTotal + " chunks");
            }
        } else {
            pendingMapStallTicks = 0;
        }

        // Restock stuck watchdog: if we are waiting for chest data and nothing
        // arrives for ~10s (interrupted interaction, server glitch), close the
        // screen, re-plan the restock run from the current inventory and resume.
        if (state == State.AwaitRestockResponse && toBeHandledInvPacket == null) {
            if (++restockStallTicks >= 200) {
                restockStallTicks = 0;
                warning("Restock stuck at the chest - re-planning the restock run...");
                HiveLog.log("RESTOCK stalled at chest "
                    + (lastInteractedBlockPos != null ? lastInteractedBlockPos.toShortString() : "?")
                    + " - re-planning");
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                restockBacklogSlots.clear();
                closeNextInvPacket = false;
                Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInfo =
                    Utils.getInvInformation(getRequiredItems(), availableSlots);
                refillInventory(invInfo.getRight());
                // refillInventory queued a new refill checkpoint if anything is missing
                state = State.Walking;
                return;
            }
        } else {
            restockStallTicks = 0;
        }

        // Work-balance sweep (master, while building): refresh own progress,
        // log a PROGRESS line every 30s, and either anchor the dupers or steal work.
        if (!SlaveSystem.isSlave() && !finalizePhase && map != null
            && (state == State.Walking || state == State.Dumping || state == State.AwaitMasterAllBuilt || state == State.Afk)) {
            if (++progressSweepTicks >= 600) {
                progressSweepTicks = 0;
                ownUnfinished = countUnfinishedRowsInInterval(workingInterval);
                StringBuilder progress = new StringBuilder("PROGRESS master: ").append(ownUnfinished).append(" rows left");
                for (String slave : SlaveSystem.slaves) {
                    progress.append(", ").append(slave).append(": ").append(slaveRemaining(slave)).append(" (est)");
                }
                HiveLog.log(progress.toString());
                // STALL WATCHDOG: a slave whose unfinished count hasn't moved in
                // 5 minutes (10 sweeps) is wedged. Re-partition the rows among the
                // slaves (max twice per map - a genuinely unfixable block would
                // otherwise cause repartition loops); after that, just scream.
                for (String slave : SlaveSystem.slaves) {
                    int rem = slaveRemaining(slave);
                    if (rem <= 0) {
                        stallUnfinished.remove(slave);
                        stallSweeps.remove(slave);
                        continue;
                    }
                    Integer prev = stallUnfinished.get(slave);
                    int ticks = (prev != null && prev == rem) ? stallSweeps.getOrDefault(slave, 0) + 1 : 0;
                    stallUnfinished.put(slave, rem);
                    stallSweeps.put(slave, ticks);
                    if (ticks == 10) {
                        if (stallRepartitionsThisMap < 2 && !finalizePhase) {
                            stallRepartitionsThisMap++;
                            HiveLog.log("STALL " + slave + ": " + rem + " unfinished rows unchanged for 5 min"
                                + " - re-partitioning rows among slaves (attempt " + stallRepartitionsThisMap + "/2)");
                            warning(slave + " has been stuck on " + rem + " rows for 5 minutes - re-partitioning work.");
                            // Exclude the wedged bot from the partition: giving it
                            // fresh rows would just burn the next attempt too.
                            SlaveSystem.repartitionAmongSlaves(slave);
                            stallUnfinished.clear();
                            stallSweeps.clear();
                        } else {
                            HiveLog.log("STALL " + slave + ": " + rem + " unfinished rows unchanged for 5 min"
                                + " - re-partition limit reached; MANUAL INTERVENTION LIKELY REQUIRED");
                            warning("§c" + slave + " is still stuck on " + rem + " rows. The hivemind cannot finish this map automatically - please check the bot.");
                        }
                    }
                }
                // A row with a permanently failing placement blocks the countdown
                // forever. Only treat the master as done when EVERY unfinished row
                // contains a known error (not merely "some error somewhere").
                boolean onlyErrorRows = ownUnfinished > 0
                    && errorRowsInInterval(workingInterval) >= ownUnfinished;
                if (ownUnfinished == 0 || onlyErrorRows) {
                    if (useAfkAnchor()) {
                        if (!SlaveSystem.allSlavesFinished()) {
                            // Hand any leftover rows (incl. stuck error rows) to the slaves
                            if (ownUnfinished > 0) handOffMasterRows();
                            // Slaves still building - master anchors the dupers
                            goAfk();
                        } else if (finalizeDelegate == null && state != State.Afk) {
                            // Everyone done - delegate the finalize and stay anchored
                            delegateFinalize(null);
                            goAfk();
                        }
                    } else {
                        stealWork("master");
                    }
                }
            }
            // Finalize watchdog: a delegated slave that never reports completion
            // gets swapped for another one after 3 minutes (bounded); once the
            // re-delegation cap is burned - or no other slave exists - the
            // master runs the finalize itself instead of waiting in Afk forever.
            if (finalizeDelegate != null) {
                if (++finalizeWatchdogTicks >= 3600) {
                    finalizeWatchdogTicks = 0;
                    finalizeRedelegations++;
                    String other = null;
                    for (String s : SlaveSystem.slaves) {
                        if (!s.equals(finalizeDelegate)) { other = s; break; }
                    }
                    if (other != null && finalizeRedelegations < 2) {
                        HiveLog.log("FINALIZE TIMEOUT - delegate " + finalizeDelegate
                            + " did not finish; re-delegating to " + other);
                        warning("Delegate " + finalizeDelegate + " did not finish the finalize - re-delegating to " + other + ".");
                        SlaveSystem.queueDM(finalizeDelegate, "pause");
                        delegateFinalize(other);
                    } else {
                        HiveLog.log("FINALIZE TIMEOUT - no alternative delegate" + (finalizeRedelegations >= 2 ? " (re-delegate cap reached)" : "")
                            + " - master runs the finalize itself");
                        warning("Delegate " + finalizeDelegate + " did not finish - the master is taking over the finalize.");
                        SlaveSystem.queueDM(finalizeDelegate, "pause");
                        finalizeDelegate = null;
                        proceedMasterSelfFinalize();
                    }
                }
            } else {
                finalizeWatchdogTicks = 0;
            }
        } else {
            progressSweepTicks = 0;
        }

        // VERIFY watchdog: the assigned verifier must report verifyDone. A
        // wedged/disconnected verifier gets replaced (capped); after the cap
        // the flow continues without verification (the wipe gate remains).
        if (awaitingVerify && ++verifyWatchdogTicks >= VERIFY_WATCHDOG_TICKS) {
            verifyWatchdogTicks = 0;
            if (verifyAttempts < MAX_VERIFY_ATTEMPTS && !SlaveSystem.slaves.isEmpty()) {
                String failed = verifySlave;
                HiveLog.log("VERIFY timeout - " + failed + " did not report verifyDone; re-assigning");
                warning("Verifier " + failed + " did not finish in time - assigning another slave.");
                if (failed != null) SlaveSystem.queueDM(failed, "pause");
                verifySlave = null;
                assignVerify(null);
            } else {
                HiveLog.log("VERIFY cap reached without verifyDone - continuing to finalize (wipe gate active)");
                awaitingVerify = false;
                verifyComplete = true;
                verifySlave = null;
                proceedToFinalize(null);
            }
        }

        if (!state.equals(debugPreviousState)) {
            debugPreviousState = state;
            if (debugPrints.get()) info("State changed to: §a" + state);
        }

        // Auto-broadcast the config once the setup completes (slaves may have
        // registered before the master finished its own setup)
        if (pendingSetupBroadcast && state.equals(State.SelectingChests) && hasFullSetup()) {
            pendingSetupBroadcast = false;
            broadcastSetup();
        }

        if (state.equals(State.AwaitMasterAllBuilt)) {
            // Rows may have become unfinished (re-split after a slave change) - resume
            // building, but only if the unfinished rows are inside OUR OWN interval.
            // Rows still being built by other bots are their job; don't churn here.
            if (hasUnfinishedRowsInInterval()) {
                calculateBuildingPath(startNorthToSouth.get(), true);
                state = State.Walking;
            } else if (SlaveSystem.allSlavesFinished()) {
                if (!endBuilding()) return;
                if (state != State.Walking) return; // AwaitVerify - stop ticking the build path
            } else {
                return;
            }
        }

        long timeDifference = System.currentTimeMillis() - lastTickTime;
        int allowedPlacements = (int) Math.floor(timeDifference / placeDelay.get());
        lastTickTime += (long) allowedPlacements * placeDelay.get();

        if (interactTimeout > 0) {
            interactTimeout--;
            if (interactTimeout == 0) {
                // Don't retry if we're waiting for button press confirmation
                if (state != State.AwaitButtonPress) {
                    info("Interaction timed out. Interacting again...");
                    interactWithBlock(lastInteractedBlockPos);
                }
            }
        }

        if (cornerSelectCooldown > 0) cornerSelectCooldown--;

        if (state == State.AwaitButtonPress) {
            // Wait for the button press to be confirmed (via BlockUpdateS2CPacket) and redstone to settle.
            if (buttonPressTicks > 0) {
                buttonPressTicks--;
                if (!expectButtonPress) {
                    // Press was confirmed - keep waiting for the redstone to settle
                } else if (buttonPressTicks == 0 && buttonPressAttempts < buttonPressRetries.get()) {
                    // Not confirmed and retries remaining - press the button again
                    buttonPressAttempts++;
                    buttonPressTicks = buttonPressDelay.get();
                    info("Reset button press not confirmed. Retrying (" + buttonPressAttempts + "/" + buttonPressRetries.get() + ")...");
                    pressResetButton();
                } else if (buttonPressTicks == 0) {
                    warning("Reset button press could not be confirmed after " + (buttonPressAttempts + 1) + " attempts. Continuing anyway...");
                    buttonPressAttempts = 0;
                }
                Utils.setForwardPressed(false);
                Utils.setBackwardPressed(false);
                Utils.setJumpPressed(false);
                return;
            }
            buttonPressAttempts = 0;
            state = State.Walking;
            return;
        }

        if (timeoutTicks > 0) {
            // Dumping must never be frozen by an onGround quirk - always tick down there
            if (mc.player.isOnGround() || state == State.Dumping) timeoutTicks--;
            Utils.setForwardPressed(false);
            return;
        }

        // Swap into Hotbar
        if (toBeSwappedSlot != -1) {
            swapIntoHotbar(toBeSwappedSlot);
            toBeSwappedSlot = -1;
            if (postSwapDelay.get() != 0) {
                timeoutTicks = postSwapDelay.get();
                return;
            }
        }

        // Restocking
        if (restockBacklogSlots.size() > 0) {
            int slot = restockBacklogSlots.remove(0);
            // Stale screen guard: the backlog slots were captured from a previous
            // chest inventory. If the bot was interrupted (screen closed) or the
            // chest changed size (single vs double chest), the indices no longer
            // match the current screen handler - clicking them crashes the game.
            if (slot >= mc.player.currentScreenHandler.slots.size()) {
                warning("Stale chest slots detected (screen changed). Re-opening the chest...");
                restockBacklogSlots.clear();
                interactWithBlock(lastInteractedBlockPos);
                state = State.AwaitRestockResponse;
                return;
            }
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 1, SlotActionType.QUICK_MOVE, mc.player);
            if (restockBacklogSlots.isEmpty()) {
                if (state.equals(State.AwaitRestockResponse)) {
                    endRestocking();
                }
            } else {
                timeoutTicks = invActionDelay.get();
            }
            return;
        }

        // Break blocks for repair
        if (state == State.AwaitBlockBreak) {
            if (MapAreaCache.getCachedBlockState(miningPos).isAir()) {
                miningPos = null;
                state = State.Walking;
            } else {
                Rotations.rotate(Rotations.getYaw(miningPos), Rotations.getPitch(miningPos), 50);
                BlockUtils.breakBlock(miningPos, true);
                return;
            }
        }

        // Dump unnecessary items
        if (state == State.Dumping) {
            int dumpSlot = getDumpSlot();
            if (dumpSlot == -1) {
                dumpStartMillis = 0;
                dumpForcedThrows = 0;
                dumpInvResyncDone = false;
                HashMap<Item, Integer> requiredItems = getRequiredItems();
                Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInformation = Utils.getInvInformation(requiredItems, availableSlots);
                refillInventory(invInformation.getRight());
                state = State.Walking;
            } else {
                ItemStack stack = mc.player.getInventory().getStack(dumpSlot);
                // Progress detection: a different slot or a shrinking stack means
                // drops ARE going through - reset the watchdog timer.
                if (dumpSlot != lastDumpSlot || stack.getCount() < lastDumpCount) {
                    dumpStartMillis = 0;
                    dumpForcedThrows = 0;
                    dumpInvResyncDone = false; // drops go through again - re-arm the resync
                }
                lastDumpSlot = dumpSlot;
                lastDumpCount = stack.getCount();
                long now = System.currentTimeMillis();
                if (dumpStartMillis == 0) dumpStartMillis = now;
                long stallMillis = now - dumpStartMillis;

                if (stallMillis >= 20000) {
                    // Give up: excess items are not worth hanging at the dumper for.
                    warning("Dump still failing after 20s - skipping the rest and continuing.");
                    HiveLog.log("DUMP GAVE UP after 20s (slot " + dumpSlot + ": " + stack.getCount()
                        + "x " + stack.getName().getString() + ") - continuing with excess items");
                    dumpStartMillis = 0;
                    dumpForcedThrows = 0;
                    dumpInvResyncDone = false;
                    HashMap<Item, Integer> requiredItems = getRequiredItems();
                    refillInventory(Utils.getInvInformation(requiredItems, availableSlots).getRight());
                    state = State.Walking;
                } else if (stallMillis >= 5000 && !dumpInvResyncDone) {
                    // FIRST-effort unstick: open and close the inventory once.
                    // Closing a container makes the server run its close logic,
                    // which hands a phantom held (cursor) item back to the
                    // inventory - the most common reason THROW clicks are
                    // silently ignored. Cheaper than the forced-throw escalation;
                    // fires only once per stall and re-arms on progress.
                    dumpInvResyncDone = true;
                    if (mc.currentScreen != null) mc.player.closeHandledScreen();
                    mc.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(mc.player));
                    mc.player.closeHandledScreen();
                    warning("Dump stalled - opening and closing the inventory once to resync (first effort)...");
                    HiveLog.log("DUMP stall " + (stallMillis / 1000) + "s on slot " + dumpSlot
                        + " (" + stack.getCount() + "x " + stack.getName().getString()
                        + ") - inventory open/close resync (first effort)");
                } else if (stallMillis >= 10000 && now - lastForcedThrowMillis >= 2000) {
                    lastForcedThrowMillis = now;
                    dumpForcedThrows++;
                    if (mc.currentScreen != null) mc.player.closeHandledScreen();
                    int handlerSlot = dumpSlot < 9 ? dumpSlot + 36 : dumpSlot;
                    if (dumpForcedThrows % 3 == 0) {
                        // Anticheat desync workaround: the server can believe the
                        // cursor is still holding an item (phantom held stack), which
                        // silently rejects all THROW clicks. Parking the cursor into
                        // an empty player-inventory slot re-syncs the cursor state.
                        int emptySlot = findEmptyInventoryHandlerSlot();
                        if (emptySlot != -1) {
                            warning("Dump still rejected - resetting inventory cursor state (server desync)...");
                            HiveLog.log("DUMP anticheat desync - parking phantom cursor stack into empty slot "
                                + emptySlot + " then retrying");
                            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, emptySlot, 0, SlotActionType.PICKUP, mc.player);
                            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, emptySlot, 0, SlotActionType.PICKUP, mc.player);
                        }
                    } else {
                        warning("Dump not going through (server lag?) - forcing a full-stack throw of §a"
                            + stack.getName().getString() + "§7 (attempt " + dumpForcedThrows + ")");
                        HiveLog.log("DUMP stalled " + (stallMillis / 1000) + "s on slot " + dumpSlot + " ("
                            + stack.getCount() + "x " + stack.getName().getString() + ") - forcing full-stack throw");
                        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, handlerSlot, 1, SlotActionType.THROW, mc.player);
                    }
                } else if (stallMillis >= 3000 && now - lastDumpLogMillis >= 3000) {
                    lastDumpLogMillis = now;
                    HiveLog.log("DUMP slow: " + (stallMillis / 1000) + "s at the dumper (slot " + dumpSlot
                        + ": " + stack.getCount() + "x " + stack.getName().getString() + ")");
                }
                if (debugPrints.get())
                    info("Dumping §a" + stack.getName().getString() + " (slot " + dumpSlot + ")");
                InvUtils.drop().slot(dumpSlot);
                timeoutTicks = invActionDelay.get();
            }
        }

        // Await map reset
        if (state == State.AwaitAreaClear && MapAreaCache.isMapAreaClear()) {
            awaitClearTicks = 0;
            wipeRetryAttempts = 0;
            if (debugWipeOnly) {
                debugWipeOnly = false;
                isWiping = false;
                info("Debug wipe sequence completed. Map area is clear.");
                state = State.SelectingChests;
                return;
            }
            if (finalizingForMaster) {
                // Delegated finalize finished - hand control back to the master
                finalizingForMaster = false;
                HiveLog.log("FINALIZE complete - wipe done, reporting to master");
                info("Delegated finalize complete. Waiting for the next map...");
                SlaveSystem.queueMasterDM("finalizeDone");
                state = State.AwaitSlaveNextMap;
                Utils.setForwardPressed(false);
                return;
            }
            state = State.AwaitNBTFile;
            return;
        }
        if (state == State.AwaitAreaClear) {
            // TIMEOUT: a missed button press ("Continuing anyway...") or stale
            // unloaded chunks otherwise keep the bot here FOREVER - re-run the
            // wipe (bounded) and then hold loudly.
            if (++awaitClearTicks >= 6000) {
                awaitClearTicks = 0;
                wipeRetryAttempts++;
                if (wipeRetryAttempts >= 3) {
                    HiveLog.log("WIPE HOLD - map area still not clear after " + wipeRetryAttempts
                        + " attempts; the water state is probably wrong. MANUAL INTERVENTION REQUIRED.");
                    warning("§cMap area not clearing after 3 wipe attempts - holding here. Check the reset button / water state manually.");
                    return;
                }
                HiveLog.log("WIPE retry " + wipeRetryAttempts + "/3 - map area not clear, re-running the wipe sequence");
                warning("Map area not clearing - re-running the wipe sequence (attempt " + wipeRetryAttempts + "/3).");
                checkpoints.clear();
                if (!triggerWipeSequence()) {
                    warning("Cannot re-run the wipe sequence (missing reset button/perimeter corners). Holding.");
                    wipeRetryAttempts = 3;
                    return;
                }
                return;
            }
            // Throttled log so a stuck wipe is diagnosable
            if (awaitClearLogTicks > 0) {
                awaitClearLogTicks--;
            } else {
                awaitClearLogTicks = 100;
                if (debugPrints.get())
                    info("Waiting for the map area to clear... (if this takes too long, the reset button presses probably missed and the water state is wrong)");
            }
        }

        // Load next nbt file
        if (state == State.AwaitNBTFile) {
            if (!prepareNextMapFile()) return;
            // Hivemind: transmit the map, re-split rows and start the slaves
            if (!SlaveSystem.isSlave()) {
                if (SlaveSystem.slaves.isEmpty()) {
                    info("No slaves connected - single-user fallback (building the full map alone).");
                } else {
                    for (String slave : SlaveSystem.slaves) sendMapTo(slave);
                }
                SlaveSystem.generateIntervals();
            }
            startBuilding();
        }

        // Handle Block Entity interaction response
        if (toBeHandledInvPacket != null) {
            handleInventoryPacket(toBeHandledInvPacket);
            toBeHandledInvPacket = null;
            return;
        }

        if (closeNextInvPacket) {
            if (mc.currentScreen != null) {
                mc.player.closeHandledScreen();
            }
            closeNextInvPacket = false;
        }

        // Main Loop for building
        if (!state.equals(State.Walking)) return;
        Utils.setForwardPressed(true);
        if (checkpoints.isEmpty()) {
            if (isWiping) {
                // The wipe checkpoint queue ran out - never fall back to building with the previous map here
                warning("Wipe checkpoint queue ran out unexpectedly. Waiting for the map area to clear...");
                isWiping = false;
                state = State.AwaitAreaClear;
                Utils.setForwardPressed(false);
                return;
            }
            // Creating fallback checkpoint
            checkpoints.add(new Pair(mc.player.getEntityPos(), new Pair<>("lineEnd", null)));
        }
        Vec3d goal = checkpoints.get(0).getLeft();
        if (PlayerUtils.distanceTo(goal.add(0, mc.player.getY() - goal.y, 0)) < checkpointBuffer.get()) {
            Pair<String, BlockPos> checkpointAction = checkpoints.get(0).getRight();
            if (debugPrints.get() && checkpointAction.getLeft() != null)
                info("Reached: §a" + checkpointAction.getLeft());
            checkpoints.remove(0);
            switch (checkpointAction.getLeft()) {
                case "lineEnd":
                    boolean reachedNorthSide = goal.z == mapCorner.toCenterPos().z;
                    calculateBuildingPath(reachedNorthSide, false);
                    // Report remaining work for hivemind work balancing
                    int unfinishedRows = countUnfinishedRowsInInterval(workingInterval);
                    if (SlaveSystem.isSlave()) {
                        SlaveSystem.queueMasterDM("progress:" + unfinishedRows);
                    } else {
                        ownUnfinished = unfinishedRows;
                    }
                    ArrayList<BlockPos> newErrors = Utils.getInvalidPlacements(mapCorner, workingInterval, map, knownErrors);
                    for (BlockPos errorPos : newErrors) {
                        BlockPos relativePos = errorPos.subtract(mapCorner);
                        if (logErrors.get()) {
                            Block missingBlock = map[relativePos.getX()][relativePos.getZ()];
                            String missingBlockString = missingBlock == null ? "empty" : missingBlock.getName().getString();
                            info("Error at: " + errorPos.toShortString() + ". Is: "
                                + MapAreaCache.getCachedBlockState(errorPos).getBlock().getName().getString()
                                + ". Should be: " + missingBlockString);
                        }
                    }
                    // RECONCILE, not append: the scan re-checks every position, so the
                    // list becomes exactly the set of currently-wrong blocks. Fixed
                    // positions drop out (the old append-only list kept them forever,
                    // froze the heartbeat error count and made Repair re-break fixed
                    // blocks every pass).
                    knownErrors.clear();
                    knownErrors.addAll(newErrors);
                    // Hivemind master, error-row death spiral guard: if EVERY
                    // unfinished row the master holds contains a known error it
                    // can never fix them itself (it never self-repairs). Hand the
                    // rows to the slaves - their interval scans re-detect and
                    // repair them. Without this the non-anchor master loops on
                    // error rows forever and the finalize never runs.
                    if (!finalizePhase && !SlaveSystem.isSlave() && !SlaveSystem.slaves.isEmpty()
                        && !rowsHandedOff && ownUnfinished > 0
                        && errorRowsInInterval(workingInterval) >= ownUnfinished) {
                        HiveLog.log("ERRORS occupy all " + ownUnfinished
                            + " of my unfinished rows - handing my rows to the slaves for repair");
                        warning("All my remaining rows contain errors - handing them to the slaves (they re-scan and repair).");
                        handOffMasterRows();
                    }
                    // Hivemind master: never self-repair or wipe mid-map - it would
                    // abandon its rows/AFK spot and wander into the duper machines.
                    // Slaves fix errors in their own intervals; the master just retries.
                    boolean masterInHive = !SlaveSystem.isSlave() && !SlaveSystem.slaves.isEmpty();
                    if (!knownErrors.isEmpty() && masterInHive && logErrors.get()) {
                        // Throttle: this fires every line-end; once per 30s is plenty
                        long now = System.currentTimeMillis();
                        if (now - lastErrorsLogMs >= 30000) {
                            lastErrorsLogMs = now;
                            HiveLog.log("ERRORS " + knownErrors.size()
                                + " pending on master - error fixing delegated to slaves (master keeps building)");
                        }
                    }
                    if (!knownErrors.isEmpty() && errorAction.get() == ErrorAction.Reset && !masterInHive) {
                        warning("ErrorAction is Reset: Resetting map because of an error...");
                        checkpoints.clear();
                        if (breakCarpetAboveReset.get()) {
                            BlockPos abovePos = resetButton.getLeft().up();
                            if (MapAreaCache.getCachedBlockState(abovePos).getBlock() instanceof CarpetBlock) {
                                checkpoints.add(new Pair(resetButton.getRight(), new Pair("break", abovePos)));
                            }
                        }
                        if (!triggerWipeSequence()) {
                            warning("Cannot start the wipe sequence (missing reset button/perimeter corners). Stopping the module to avoid building on a dirty canvas...");
                            toggle();
                            return;
                        }
                        startedFiles.remove(mapFile);
                    }
                    break;
                case "mapMaterialChest":
                    BlockPos mapMaterialChest = getBestChest(Items.CARTOGRAPHY_TABLE).getLeft();
                    interactWithBlock(mapMaterialChest);
                    state = State.AwaitMapChestResponse;
                    return;
                case "parkAfk":
                    // Master anchored at the duper spot - stand still until finalize completes
                    checkpoints.clear();
                    state = State.Afk;
                    Utils.setForwardPressed(false);
                    HiveLog.log("AFK master parked at the duper anchor spot");
                    info("Anchoring the dupers - standing by at the AFK spot.");
                    return;
                case "fillMap":
                    mc.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, Utils.getNextInteractID(), mc.player.getYaw(), mc.player.getPitch()));
                    if (mapFillSquareSize.get() == 0) {
                        checkpoints.add(0, new Pair(cartographyTable.getRight(), new Pair<>("cartographyTable", null)));
                    } else {
                        checkpoints.add(new Pair(goal.add(-mapFillSquareSize.get(), 0, mapFillSquareSize.get()), new Pair("sprint", null)));
                        checkpoints.add(new Pair(goal.add(mapFillSquareSize.get(), 0, mapFillSquareSize.get()), new Pair("sprint", null)));
                        checkpoints.add(new Pair(goal.add(mapFillSquareSize.get(), 0, -mapFillSquareSize.get()), new Pair("sprint", null)));
                        checkpoints.add(new Pair(goal.add(-mapFillSquareSize.get(), 0, -mapFillSquareSize.get()), new Pair("sprint", null)));
                        checkpoints.add(new Pair(cartographyTable.getRight(), new Pair("cartographyTable", null)));
                    }
                    return;
                case "cartographyTable":
                    state = State.AwaitCartographyResponse;
                    interactWithBlock(cartographyTable.getLeft());
                    return;
                case "finishedMapChest":
                    state = State.AwaitFinishedMapChestResponse;
                    interactWithBlock(finishedMapChest.getLeft());
                    return;
                case "pressButton":
                    info("Pressing reset button...");
                    state = State.AwaitButtonPress;
                    buttonPressTicks = buttonPressDelay.get();
                    buttonPressAttempts = 0;
                    pressResetButton();
                    return;
                case "dump":
                    state = State.Dumping;
                    Utils.setForwardPressed(false);
                    mc.player.setYaw(dumpStation.getRight().getLeft());
                    mc.player.setPitch(dumpStation.getRight().getRight());
                    return;
                case "refill":
                    state = State.AwaitRestockResponse;
                    // A fresh chest interaction invalidates any stale backlog slots
                    restockBacklogSlots.clear();
                    interactWithBlock(checkpointAction.getRight());
                    return;
                case "awaitClear":
                    state = State.AwaitAreaClear;
                    Utils.setForwardPressed(false);
                    return;
                case "parkCorner":
                    // Slave is parked at its perimeter corner, waiting for the master
                    state = State.AwaitSlaveNextMap;
                    Utils.setForwardPressed(false);
                    return;
                case "verifyPoint":
                    // Interim verify walk point - keep going, nothing to do here
                    return;
                case "verifyScan":
                    // Reached the last quadrant center - full canvas rescan
                    handleVerifyScan();
                    return;
                case "break":
                    state = State.AwaitBlockBreak;
                    miningPos = checkpointAction.getRight();
                    Utils.setForwardPressed(false);
                    Rotations.rotate(Rotations.getYaw(miningPos), Rotations.getPitch(miningPos), 50);
                    BlockUtils.breakBlock(miningPos, true);
                    return;
            }
            if (checkpoints.isEmpty()) {
                if (!knownErrors.isEmpty()) {
                    boolean masterInHive = !SlaveSystem.isSlave() && !SlaveSystem.slaves.isEmpty();
                    if (masterInHive) {
                        // Hivemind master: never repair/toggle-off - keep building (or
                        // anchoring). Unfixable rows stay pending; slaves handle theirs.
                        HiveLog.log("ERRORS " + knownErrors.size()
                            + " skipped by master (hivemind) - no repair/reset/toggle-off");
                        knownErrors.clear();
                    } else if (errorAction.get() == ErrorAction.ToggleOff) {
                        info("Found errors: ");
                        for (int i = knownErrors.size() - 1; i >= 0; i--) {
                            info("Pos: " + knownErrors.get(i).toShortString());
                        }
                        knownErrors.clear();
                        checkpoints.add(new Pair(mc.player.getEntityPos(), new Pair("lineEnd", null)));
                        state = State.Walking;
                        warning("ErrorAction is ToggleOff: Stopping because of an error...");
                        toggle();
                        return;
                    } else if (errorAction.get() == ErrorAction.Repair) {
                        // VERIFY-BEFORE-BREAK: only route to positions that are still
                        // wrong RIGHT NOW. The old code re-broke every known error on
                        // every repair pass - including blocks it had already fixed -
                        // which is the "tearing up corrected blocks" death spiral.
                        ArrayList<BlockPos> toRepair = new ArrayList<>();
                        for (BlockPos errorPos : knownErrors) {
                            BlockPos rel = errorPos.subtract(mapCorner);
                            boolean stillWrong = true;
                            if (map != null && rel.getX() >= 0 && rel.getX() < 128 && rel.getZ() >= 0 && rel.getZ() < 128) {
                                Block expected = map[rel.getX()][rel.getZ()];
                                BlockState current = MapAreaCache.getVerifiedBlockState(errorPos);
                                if (current != null) {
                                    stillWrong = expected == null ? !current.isAir() : !current.getBlock().equals(expected);
                                }
                            }
                            if (stillWrong) toRepair.add(errorPos);
                        }
                        if (toRepair.isEmpty()) {
                            // Everything already fixed (or unverifiable) - re-scan instead
                            knownErrors.clear();
                            checkpoints.add(new Pair(mc.player.getEntityPos(), new Pair("lineEnd", null)));
                            state = State.Walking;
                            return;
                        }
                        info("Fixing errors: ");
                        for (BlockPos errorPos : toRepair) {
                            info("Pos: " + errorPos.toShortString());
                            checkpoints.add(new Pair(errorPos.toCenterPos(), new Pair("break", errorPos)));
                        }
                        checkpoints.add(new Pair(dumpStation.getLeft(), new Pair("dump", null)));
                        for (int i = 0; i < toRepair.size(); i++) {
                            String action = (i == toRepair.size() - 1) ? "lineEnd" : "sprint";
                            checkpoints.add(new Pair(toRepair.get(i).toCenterPos(), new Pair(action, null)));
                        }
                        knownErrors.clear();
                        return;
                    }
                }
                if (SlaveSystem.isSlave()) {
                    SlaveSystem.queueMasterDM("finished");
                    state = State.AwaitSlaveNextMap;
                    Utils.setForwardPressed(false);
                    return;
                }
                if (SlaveSystem.allSlavesFinished()) {
                    if (useAfkAnchor()) {
                        // Master anchors the dupers: delegate the finalize and park
                        delegateFinalize(null);
                        goAfk();
                        return;
                    }
                    if (!endBuilding()) return;
                    // Verify pass assigned: state is AwaitVerify with an empty
                    // checkpoint list - must not fall through to checkpoints.get(0)
                    if (state != State.Walking) return;
                } else {
                    info("Waiting for slaves to finish...");
                    state = State.AwaitMasterAllBuilt;
                    Utils.setForwardPressed(false);
                    return;
                }
            }
            goal = checkpoints.get(0).getLeft();
        }
        mc.player.setYaw((float) Rotations.getYaw(goal));
        String nextAction = checkpoints.get(0).getRight().getLeft();

        if ((nextAction == "" || nextAction == "lineEnd") && sprinting.get() != SprintMode.Always) {
            mc.player.setSprinting(false);
        } else if (sprinting.get() != SprintMode.Off) {
            mc.player.setSprinting(true);
        }
        final List<String> allowPlaceActions = Arrays.asList("", "lineEnd", "sprint");
        if (!allowPlaceActions.contains(nextAction)) return;
        // Never place blocks or inject restock checkpoints while the wipe sequence is running -
        // the map data is still the previous map and the canvas is being cleared.
        if (isWiping) return;

        ArrayList<BlockPos> placements = new ArrayList<>();
        for (int i = 0; i < allowedPlacements; i++) {
            AtomicReference<BlockPos> closestPos = new AtomicReference<>();
            final Vec3d currentGoal = goal;
            BlockPos groundedPlayerPos = new BlockPos(mc.player.getBlockPos().getX(), mapCorner.getY(), mc.player.getBlockPos().getZ());
            Utils.iterateBlocks(groundedPlayerPos, (int) Math.ceil(placeRange.get()) + 1, 0, ((blockPos, blockState) -> {
                Double posDistance = PlayerUtils.distanceTo(blockPos.toCenterPos());
                BlockPos relativePos = blockPos.subtract(mapCorner);
                if (blockState.isAir() && posDistance <= placeRange.get() && posDistance > minPlaceDistance.get()
                    && MapAreaCache.isWithingMap(blockPos) && map[relativePos.getX()][relativePos.getZ()] != null
                    && blockPos.getX() <= currentGoal.getX() + linesPerRun.get() - 1 && !placements.contains(blockPos)
                    && blockPos.getX() >= currentGoal.getX() - 1
                    // Strict interval ownership: the old window bled 1 row past the
                    // interval edge, letting bots place in a neighbour's rows. The
                    // pre-finalize verify pass and the wipe-gate repair rounds are
                    // owner-neutral (their checkpoints target exact issue positions).
                    // NOTE: blockPos is ABSOLUTE - it must be converted to a relative
                    // row before the interval check (comparing ~3.4M world X against
                    // rows 0-127 silently rejected EVERY placement).
                    && (verifyingForMaster || finalizePhase
                        || Utils.isInInterval(workingInterval, blockPos.getX() - mapCorner.getX()))) {
                    if (closestPos.get() == null || posDistance < PlayerUtils.distanceTo(closestPos.get())) {
                        closestPos.set(new BlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
                    }
                }
            }));

            if (closestPos.get() != null) {
                //Stop placing if restocking
                placements.add(closestPos.get());
                if (!tryPlacingBlock(closestPos.get())) {
                    return;
                }
            }
        }
    }

    // Restocking

    private Pair<BlockPos, Vec3d> getBestChest(Item item) {
        Vec3d bestPos = null;
        BlockPos bestChestPos = null;
        ArrayList<Pair<BlockPos, Vec3d>> list;
        if (item.equals(Items.CARTOGRAPHY_TABLE)) {
            list = mapMaterialChests;
        } else if (materialDict.containsKey(item)) {
            list = materialDict.get(item);
        } else {
            warning("No chest found for " + item.getName().getString());
            toggle();
            return null;
        }
        //Get nearest chest
        for (Pair<BlockPos, Vec3d> p : list) {
            //Skip chests that have already been checked
            if (checkedChests.contains(p.getLeft())) continue;
            if (bestPos == null || PlayerUtils.distanceTo(p.getRight()) < PlayerUtils.distanceTo(bestPos)) {
                bestPos = p.getRight();
                bestChestPos = p.getLeft();
            }
        }
        if (bestPos == null || bestChestPos == null) {
            checkedChests.clear();
            return getBestChest(item);
        }
        return new Pair(bestChestPos, bestPos);
    }

    private void refillInventory(HashMap<Item, Integer> invMaterial) {
        //Fills restockList with required items
        restockList.clear();
        HashMap<Item, Integer> requiredItems = getRequiredItems();
        for (Item item : invMaterial.keySet()) {
            int oldAmount = requiredItems.remove(item);
            requiredItems.put(item, oldAmount - invMaterial.get(item));
        }

        for (Item item : requiredItems.keySet()) {
            if (requiredItems.get(item) <= 0) continue;
            int stacks = (int) Math.ceil((float) requiredItems.get(item) / 64f);
            info("Restocking §a" + stacks + " stacks " + item.getName().getString() + " (" + requiredItems.get(item) + ")");
            restockList.add(0, Triple.of(item, stacks, requiredItems.get(item)));
        }
        addClosestRestockCheckpoint();
    }

    private void addClosestRestockCheckpoint() {
        //Determine closest restock chest for material in restock list
        if (restockList.isEmpty()) return;
        double smallestDistance = Double.MAX_VALUE;
        Triple<Item, Integer, Integer> closestEntry = null;
        Pair<BlockPos, Vec3d> restockPos = null;
        for (Triple<Item, Integer, Integer> entry : restockList) {
            Pair<BlockPos, Vec3d> bestRestockPos = getBestChest(entry.getLeft());
            if (bestRestockPos == null) return;
            double chestDistance = PlayerUtils.distanceTo(bestRestockPos.getRight());
            if (chestDistance < smallestDistance) {
                smallestDistance = chestDistance;
                closestEntry = entry;
                restockPos = bestRestockPos;
            }
        }
        //Set closest material as first and as checkpoint
        restockList.remove(closestEntry);
        restockList.add(0, closestEntry);
        checkpoints.add(0, new Pair(restockPos.getRight(), new Pair("refill", restockPos.getLeft())));
    }

    private void endRestocking() {
        // Safety: an interrupted restock can leave the list empty - just resume
        if (restockList.isEmpty()) {
            checkedChests.clear();
            timeoutTicks = postRestockDelay.get();
            state = State.Walking;
            return;
        }
        if (restockList.get(0).getMiddle() > 0) {
            warning("Not all necessary stacks restocked. Searching for another chest...");
            //Search for the next best chest
            checkedChests.add(lastInteractedBlockPos);

            Item foundItem = null;
            for (Item item : materialDict.keySet()) {
                for (Pair<BlockPos, Vec3d> p : materialDict.get(item)) {
                    if (p.getLeft().equals(lastInteractedBlockPos)) {
                        foundItem = item;
                        break;
                    }
                }
            }
            if (foundItem == null) {
                warning("Could not find material for chest position : " + lastInteractedBlockPos.toShortString());
                toggle();
                return;
            }
            // GIVE-UP: every known chest for this material was already visited
            // and came up empty. The old getBestChest recursion cleared
            // checkedChests and returned the SAME chest - an infinite
            // chest<->line ping-pong that stalled the map forever.
            boolean allChestsChecked = true;
            for (Pair<BlockPos, Vec3d> p : materialDict.get(foundItem)) {
                if (!checkedChests.contains(p.getLeft())) {
                    allChestsChecked = false;
                    break;
                }
            }
            if (allChestsChecked) {
                HiveLog.log("RESTOCK HOLD - all chests for " + foundItem.getName().getString() + " are empty");
                warning("§cAll material chests for " + foundItem.getName().getString()
                    + " are empty - stopping. Refill them and re-enable the module.");
                toggle();
                return;
            }
            Pair<BlockPos, Vec3d> bestRestockPos = getBestChest(foundItem);
            if (bestRestockPos == null) return;
            checkpoints.add(0, new Pair<>(bestRestockPos.getRight(), new Pair<>("refill", bestRestockPos.getLeft())));
        } else {
            checkedChests.clear();
            restockList.remove(0);
            addClosestRestockCheckpoint();
        }
        timeoutTicks = postRestockDelay.get();
        state = State.Walking;
    }

    // Block Interactions

    private void interactWithBlock(BlockPos blockPos) {
        Utils.setForwardPressed(false);
        mc.player.setVelocity(0, 0, 0);
        mc.player.setYaw((float) Rotations.getYaw(blockPos.toCenterPos()));
        mc.player.setPitch((float) Rotations.getPitch(blockPos.toCenterPos()));

        BlockHitResult hitResult = new BlockHitResult(blockPos.toCenterPos(), Utils.getInteractionSide(blockPos), blockPos, false);
        BlockUtils.interact(hitResult, Hand.MAIN_HAND, true);
        //Set timeout for chest interaction
        interactTimeout = retryInteractTimer.get();
        lastInteractedBlockPos = blockPos;
    }

    // Returns a position ~1.8 blocks away from the button center, on the same approach direction
    // the user used when registering the button. This guarantees the click is within the server's
    // interaction range even if the registration position was near the reach limit.
    private Vec3d getSafeButtonPressPos() {
        Vec3d center = resetButton.getLeft().toCenterPos();
        Vec3d registered = resetButton.getRight();
        Vec3d dir = registered.subtract(center);
        double len = dir.length();
        if (len < 0.1) return new Vec3d(center.x, registered.y, center.z + 1.8);
        return center.add(dir.multiply(1.8 / len));
    }

    // Sends a right click on the reset button as close to vanilla as possible:
    // 1. Rotate to the button and sync the rotation with the server via a look packet.
    // 2. Compute the hit point with a vanilla-style raycast against the button hitbox.
    // 3. Only send the click if the hit point is within the server's interaction range.
    // 4. Send the click through the interaction manager and swing on accept.
    // The press is then confirmed via BlockUpdateS2CPacket in onReceivePacket and retried
    // in the AwaitButtonPress state if no confirmation arrives.
    private void pressResetButton() {
        Utils.setForwardPressed(false);
        mc.player.setVelocity(0, 0, 0);
        interactTimeout = 0;

        BlockPos buttonPos = resetButton.getLeft();
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d buttonCenter = buttonPos.toCenterPos();

        // 1. Rotate to the button and sync the rotation with the server
        float yaw = (float) Rotations.getYaw(buttonCenter);
        float pitch = (float) Rotations.getPitch(buttonCenter);
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));

        // 2. Vanilla-style raycast to find the exact hit point on the button hitbox
        double reach = mc.player.isCreative() ? 5.0 : 4.5;
        Vec3d lookVec = mc.player.getRotationVec(1.0f);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eyePos, eyePos.add(lookVec.multiply(reach)),
            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(buttonPos)) {
            // Raycast missed (e.g. obstruction) - fall back to the button center with the closest side
            hit = new BlockHitResult(buttonCenter, Utils.getInteractionSide(buttonPos), buttonPos, false);
        }

        // 3. Only send the click if the hit point is within the server's interaction range
        if (eyePos.squaredDistanceTo(hit.getPos()) > (reach - 0.2) * (reach - 0.2)) {
            warning("Reset button out of reach. Walking closer...");
            Vec3d closer = buttonCenter.add(eyePos.subtract(buttonCenter).normalize().multiply(1.5));
            checkpoints.add(0, new Pair(new Vec3d(closer.x, mc.player.getY(), closer.z), new Pair("pressButton", null)));
            state = State.Walking;
            return;
        }

        // 4. Vanilla right click
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);

        // 5. Arm the confirmation (BlockUpdateS2CPacket will clear expectButtonPress)
        expectButtonPress = true;
        lastInteractedBlockPos = buttonPos;
    }

    private boolean tryPlacingBlock(BlockPos pos) {
        BlockPos relativePos = pos.subtract(mapCorner);
        Item material = map[relativePos.getX()][relativePos.getZ()].asItem();
        //info("Placing " + material.getName().getString() + " at: " + relativePos.toShortString());
        //Check hot-bar slots
        for (int slot : availableHotBarSlots) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) continue;
            Item foundMaterial = mc.player.getInventory().getStack(slot).getItem();
            if (foundMaterial.equals(material)) {
                BlockUtils.place(pos, Hand.MAIN_HAND, slot, rotate.get(), 50, true, true, false);
                if (material == lastSwappedMaterial) lastSwappedMaterial = null;
                return true;
            }
        }
        for (int slot : availableSlots) {
            if (mc.player.getInventory().getStack(slot).isEmpty() || availableHotBarSlots.contains(slot)) continue;
            Item foundMaterial = mc.player.getInventory().getStack(slot).getItem();
            if (foundMaterial.equals(material)) {
                lastSwappedMaterial = material;
                toBeSwappedSlot = slot;
                Utils.setForwardPressed(false);
                mc.player.setVelocity(0, 0, 0);
                timeoutTicks = preSwapDelay.get();
                return false;
            }
        }
        if (lastSwappedMaterial == material) return false;      //Wait for swapped material
        if (SlaveSystem.isSlave()) {
            // Slaves keep their leftovers and restock only what is actually
            // missing for their current rows (walk to the material chest themselves).
            HashMap<Item, Integer> requiredItems = getRequiredItems();
            Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInformation = Utils.getInvInformation(requiredItems, availableSlots);
            refillInventory(invInformation.getRight());
            if (!checkpoints.isEmpty() && checkpoints.get(0).getRight().getLeft().equals("refill")) {
                Utils.setForwardPressed(false);
            }
            return false;
        }
        info("No " + material.getName().getString() + " found in inventory. Resetting...");
        checkpoints.add(0, new Pair(mc.player.getEntityPos(), new Pair("sprint", null)));
        checkpoints.add(0, new Pair(dumpStation.getLeft(), new Pair("dump", null)));
        return false;
    }

    // Work balancing state (master side)
    private final HashMap<String, Integer> slaveUnfinished = new HashMap<>();
    private int ownUnfinished = -1;
    private int progressSweepTicks = 0;
    // Stall watchdog: a bot whose unfinished-row count is stuck >0 for minutes is
    // wedged (repair ping-pong, unreachable block) - escalate instead of waiting forever
    private final HashMap<String, Integer> stallUnfinished = new HashMap<>();
    private final HashMap<String, Integer> stallSweeps = new HashMap<>();
    private int stallRepartitionsThisMap = 0;
    /** Ticks spent in AwaitRestockResponse without any chest data arriving. */
    private int restockStallTicks = 0;
    // Master AFK / delegated finalize (hivemind duper anchoring)
    private String finalizeDelegate = null;
    private int finalizeWatchdogTicks = 0;
    private boolean finalizingForMaster = false;
    /** True once the master handed its leftover rows to the slaves this map. */
    private boolean rowsHandedOff = false;
    /** Captured at delegation so a re-delegate can't rename the wrong map file. */
    private String delegatedMapFileName = null;
    /** Repair rounds attempted when the wipe-completeness gate blocks a wipe. */
    private int finalizeWipeBlockAttempts = 0;
    /** Setup broadcast bookkeeping (per-slave, per-payload debounce). */
    private String lastBroadcastPayload = null;
    /** Slaves that already received the current setup payload. */
    private final HashSet<String> slavesWithCurrentSetup = new HashSet<>();
    /** ERRORS-pending log throttle (was spamming once per line-end). */
    private long lastErrorsLogMs = 0;
    // Dump watchdog: track whether items actually leave the inventory at the dumper
    private long dumpStartMillis = 0;
    private long lastForcedThrowMillis = 0;
    private long lastDumpLogMillis = 0;
    private int dumpForcedThrows = 0;
    /** True after the open/close-inventory resync fired for the current stall. */
    private boolean dumpInvResyncDone = false;
    private int lastDumpSlot = -1;
    private int lastDumpCount = -1;
    /** Never steal from a bot with fewer unfinished rows than this (avoids endgame thrashing). */
    private static final int MIN_STEAL_ROWS = 8;
    /** Always steal at least this many rows so the taker's trip is worth it. */
    private static final int MIN_STEAL_AMOUNT = 4;
    /** Max verify (re-)assignments per map before relying on the wipe gate alone. */
    private static final int MAX_VERIFY_ATTEMPTS = 3;
    /** Ticks the verify watchdog waits for verifyDone before re-assigning (5 min). */
    private static final int VERIFY_WATCHDOG_TICKS = 6000;
    /** Max blocks the verifier repairs per round (bounds the checkpoint path). */
    private static final int MAX_VERIFY_REPAIRS = 256;

    // Pre-finalize canvas verification (assigned to a slave before finalize)
    private String verifySlave = null;
    private boolean awaitingVerify = false;
    private boolean verifyComplete = false;
    private int verifyAttempts = 0;
    private int verifyWatchdogTicks = 0;
    /** Slave side: true while running the pre-finalize verify pass. */
    private boolean verifyingForMaster = false;
    private int verifyRound = 0;

    // AwaitAreaClear watchdog (a missed button press / stale chunk otherwise waits forever)
    private int awaitClearTicks = 0;
    private int wipeRetryAttempts = 0;
    /** Delegated-finalize re-assignments this map (cap -> master runs it itself). */
    private int finalizeRedelegations = 0;

    /** Counts distinct rows in the interval that hold a known-error position. */
    private int errorRowsInInterval(Pair<Integer, Integer> interval) {
        if (mapCorner == null || interval == null) return 0;
        HashSet<Integer> rows = new HashSet<>();
        for (BlockPos p : knownErrors) {
            int row = p.getX() - mapCorner.getX();
            if (Utils.isInInterval(interval, row)) rows.add(row);
        }
        return rows.size();
    }

    /**
     * Gives the master's remaining rows away to the slaves (before going AFK).
     * RE-PARTITIONS the entire row space 0-127 among the slaves (disjoint, no
     * union-merge - the old merge created overlapping intervals and bots fought
     * over the same blocks). Fires ONCE per map - the rowsHandedOff flag prevents
     * the sweep from re-handing off every 30s while the master sits parked.
     */
    private void handOffMasterRows() {
        if (rowsHandedOff || SlaveSystem.slaves.isEmpty()) return;
        rowsHandedOff = true;
        // Any FUTURE re-split (slave join/disconnect) must stay slaves-only -
        // the parked master must never receive rows it cannot build.
        SlaveSystem.masterRowsHandedOff = true;
        HiveLog.log("HANDOFF master rows "
            + (workingInterval != null ? workingInterval.getLeft() + "-" + workingInterval.getRight() : "?")
            + " - re-partitioning all rows among slaves (disjoint)");
        info("Handing my rows to the slaves before anchoring.");
        SlaveSystem.repartitionAmongSlaves();
        slaveUnfinished.clear();
        // Empty the master's own interval so its row count reads 0 from now on - a
        // parked master can never rebuild its stuck rows and the sweep would
        // otherwise re-fire this handoff every 30s.
        setInterval(new Pair<>(0, -1));
        ownUnfinished = 0;
    }

    /**
     * Diagnostic suffix for interval logs explaining the AFK-anchor decision -
     * a silent fallback to middle rows left the dupers unloaded without any
     * hint WHY (toggle off vs missing AFK spot).
     */
    public String afkAnchorStatus() {
        if (SlaveSystem.isSlave()) return " [slave - no anchor role]";
        if (!afkAnchor.get()) return " [no afk-anchor: toggle is OFF - master took middle rows]";
        if (afkSpot == null) return " [no afk-anchor: AFK-anchor is ON but NO AFK SPOT is set - master took middle rows, DUPERS MAY BREAK]";
        return " [AFK-ANCHOR: master on duper-adjacent rows]";
    }

    /** True when AFK-anchor mode is on and the spot is configured. */
    @Override
    public boolean usesAfkAnchorRows() {
        return !SlaveSystem.isSlave() && afkAnchor.get() && afkSpot != null;
    }

    @Override
    public String getHeartbeatData() {
        String phase;
        if (state == State.Afk
            || (state == State.AwaitSlaveContinue && oldState == State.Afk)) phase = "ANCHORING";
        else if (state == State.AwaitSlaveContinue) phase = "PAUSED";
        else if (verifyingForMaster) phase = "VERIFYING";
        else if (map == null) phase = "NOMAP";
        else if (finalizePhase) phase = "FINALIZING";
        else if (!isActive()) phase = "IDLE";
        else phase = "BUILDING";
        int start = workingInterval != null ? workingInterval.getLeft() : -1;
        int end = workingInterval != null ? workingInterval.getRight() : -1;
        // A bot without a map cannot build anything - report its whole interval
        // as unfinished (the old "0" made map-less slaves invisible to the
        // stall watchdog and the master waited for them forever).
        int unfinished = map == null
            ? intervalRows(workingInterval)
            : countUnfinishedRowsInInterval(workingInterval);
        int errors = knownErrors != null ? knownErrors.size() : 0;
        return "hb:" + phase + "," + start + "," + end + "," + unfinished + "," + errors;
    }

    /** Block count per map row, for workload-weighted interval splitting. */
    @Override
    public int[] getRowBlocks() {
        if (map == null) return null;
        int[] counts = new int[128];
        for (int x = 0; x < 128; x++) {
            int c = 0;
            for (int z = 0; z < 128; z++) if (map[x][z] != null) c++;
            counts[x] = c;
        }
        return counts;
    }

    @Override
    public void onSlaveProgress(String slave, int unfinishedRows) {
        if (SlaveSystem.isSlave()) return;
        slaveUnfinished.put(slave, unfinishedRows);
    }

    /** Number of rows in the interval that still have at least one block to place. Unknown (unloaded) chunks count as unfinished. */
    private int countUnfinishedRowsInInterval(Pair<Integer, Integer> interval) {
        if (map == null || mapCorner == null || interval == null) return 0;
        int count = 0;
        for (int x = interval.getLeft(); x <= interval.getRight(); x++) {
            for (int z = 0; z < 128; z++) {
                if (map[x][z] == null) continue;
                BlockState state = MapAreaCache.getVerifiedBlockState(mapCorner.add(x, 0, z));
                if (state == null || state.isAir()) {
                    // Unverified (unloaded chunk) counts as unfinished - never as done
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static int intervalRows(Pair<Integer, Integer> interval) {
        return interval == null ? 0 : interval.getRight() - interval.getLeft() + 1;
    }

    private int slaveRemaining(String slave) {
        Pair<Integer, Integer> interval = SlaveSystem.slaveIntervals.get(slave);
        if (interval == null) return 0;
        return slaveUnfinished.getOrDefault(slave, intervalRows(interval));
    }

    /**
     * Steals the tail of the busiest bot's remaining work and hands it to an idle
     * bot (a slave that just finished, or the master itself). Contiguous ranges
     * keep walking paths sane; placed lines are skipped on recalculation.
     *
     * @return true when work was re-assigned (idle bot must NOT be parked)
     */
    private boolean stealWork(String idleSlave) {
        if (finalizePhase || !hasFullSetup()) return false;

        // Find the giver with the most remaining work (master or any other slave)
        Pair<Integer, Integer> giverInterval = null;
        String giverName = null;
        int giverRemaining = 0;
        int masterRemaining = countUnfinishedRowsInInterval(workingInterval);
        if (masterRemaining >= MIN_STEAL_ROWS) {
            giverInterval = workingInterval;
            giverName = "master";
            giverRemaining = masterRemaining;
        }
        for (String slave : SlaveSystem.slaves) {
            if (slave.equals(idleSlave)) continue;
            int rem = slaveRemaining(slave);
            if (rem > giverRemaining) {
                Pair<Integer, Integer> interval = SlaveSystem.slaveIntervals.get(slave);
                if (interval == null) continue;
                giverInterval = interval;
                giverName = slave;
                giverRemaining = rem;
            }
        }
        if (giverInterval == null || giverRemaining < MIN_STEAL_ROWS) return false;

        int steal = Math.max(MIN_STEAL_AMOUNT, giverRemaining / 2);
        int giverSize = intervalRows(giverInterval);
        steal = Math.min(steal, giverSize - 1);
        if (steal < MIN_STEAL_AMOUNT) return false;

        Pair<Integer, Integer> stolen = new Pair<>(giverInterval.getRight() - steal + 1, giverInterval.getRight());
        Pair<Integer, Integer> kept = new Pair<>(giverInterval.getLeft(), giverInterval.getRight() - steal);

        if (giverName.equals("master")) {
            // Master keeps its own (still unfinished) head rows and keeps building
            setInterval(kept);
            calculateBuildingPath(startNorthToSouth.get(), true);
        } else {
            SlaveSystem.slaveIntervals.put(giverName, kept);
            slaveUnfinished.remove(giverName); // stale after the interval change
            // Sequenced + ACKed so a lost interval command can't leave the giver
            // building rows it no longer owns (heartbeat drift would fight it)
            SlaveSystem.sendCommand(giverName, HiveCommand.INTERVAL, kept.getLeft() + ":" + kept.getRight());
        }

        if (idleSlave.equals("master")) {
            // Master finished its own rows and takes over the stolen tail
            setInterval(stolen);
            calculateBuildingPath(startNorthToSouth.get(), true);
            if (state == State.AwaitMasterAllBuilt) state = State.Walking;
        } else {
            SlaveSystem.slaveIntervals.put(idleSlave, stolen);
            SlaveSystem.sendCommand(idleSlave, HiveCommand.INTERVAL, stolen.getLeft() + ":" + stolen.getRight());
            SlaveSystem.sendCommand(idleSlave, HiveCommand.START, null);
            SlaveSystem.finishedSlavesDict.put(idleSlave, false);
            SlaveSystem.activeSlavesDict.put(idleSlave, true);
        }

        HiveLog.log("STEAL rows " + stolen.getLeft() + "-" + stolen.getRight() + " of " + giverName
            + " -> " + idleSlave + " (giver keeps rows " + kept.getLeft() + "-" + kept.getRight()
            + ", est " + giverRemaining + " unfinished)");
        info("Reassigned rows " + stolen.getLeft() + "-" + stolen.getRight() + " from " + giverName
            + " to " + idleSlave + " (work balancing).");
        return true;
    }

    // Path and Building Management

    private void calculateBuildingPath(boolean startNorthSide, boolean sprintFirst) {
        //Iterate over map and skip completed lines. Player has to be able to see the complete map area
        //Fills checkpoints list
        boolean northToSouth = startNorthSide;
        checkpoints.clear();
        for (int x = workingInterval.getLeft(); x <= workingInterval.getRight(); x += linesPerRun.get()) {
            if (!Utils.isInInterval(workingInterval, x)) continue;
            boolean lineFinished = true;
            for (int lineBonus = 0; lineBonus < linesPerRun.get(); lineBonus++) {
                int adjustedX = x + lineBonus;
                if (!Utils.isInInterval(workingInterval, adjustedX)) break;
                for (int z = 0; z < 128; z++) {
                    // VERIFIED lookup: an unloaded/unknown chunk counts as NOT finished -
                    // the old air-fallback made unbuilt rows look done (phantom "finished")
                    BlockState blockState = MapAreaCache.getVerifiedBlockState(mapCorner.add(adjustedX, 0, z));
                    if (blockState != null && blockState.isAir() && map[adjustedX][z] != null) {
                        //If there is a replaceable block and not an ignored block type at the position. Mark the line as not done
                        lineFinished = false;
                        break;
                    }
                }
            }
            if (lineFinished) continue;
            Vec3d cp1 = mapCorner.toCenterPos().add(x, 0, 0);
            Vec3d cp2 = mapCorner.toCenterPos().add(x, 0, 127);
            if (northToSouth) {
                checkpoints.add(new Pair(cp1, new Pair("", null)));
                checkpoints.add(new Pair(cp2, new Pair("lineEnd", null)));
            } else {
                checkpoints.add(new Pair(cp2, new Pair("", null)));
                checkpoints.add(new Pair(cp1, new Pair("lineEnd", null)));
            }
            northToSouth = !northToSouth;
        }
        if (checkpoints.size() > 0 && sprintFirst) {
            //Make player sprint to the start of the map
            Pair<Vec3d, Pair<String, BlockPos>> firstPoint = checkpoints.remove(0);
            checkpoints.add(0, new Pair(firstPoint.getLeft(), new Pair("sprint", firstPoint.getRight().getRight())));
        }
    }

    private void startBuilding() {
        if (map == null || mapCorner == null) {
            warning("Cannot start building: no map loaded or no map area selected.");
            return;
        }
        finalizePhase = false;
        rowsHandedOff = false;
        finalizeDelegate = null;
        finalizeWipeBlockAttempts = 0;
        delegatedMapFileName = null;
        stallRepartitionsThisMap = 0;
        stallUnfinished.clear();
        stallSweeps.clear();
        // Pre-finalize verify pass state resets per map
        verifySlave = null;
        awaitingVerify = false;
        verifyComplete = false;
        verifyAttempts = 0;
        verifyWatchdogTicks = 0;
        verifyingForMaster = false;
        verifyRound = 0;
        finalizeRedelegations = 0;
        awaitClearTicks = 0;
        wipeRetryAttempts = 0;
        // The master owns rows again - interval re-splits include it once more
        SlaveSystem.masterRowsHandedOff = false;
        HiveLog.log("MAP START " + (mapFile != null ? mapFile.getName() : "<none>")
            + " (slaves: " + SlaveSystem.slaves.size() + ", finalize phase off)");
        if (!SlaveSystem.isSlave()) {
            // Hivemind: (re-)transmit the map to every registered slave, then start them
            if (hasFullSetup() && mapFile != null) {
                for (String slave : SlaveSystem.slaves) sendMapTo(slave);
            }
            SlaveSystem.startAllSlaves();
        }
        if (availableSlots.isEmpty()) setupSlots();
        MapAreaCache.reset(mapCorner);
        isWiping = false;
        // DIRTY-CANVAS SCAN: leftover blocks from a previous/incomplete wipe sit at
        // positions where this map expects NOTHING. The build loop can never remove
        // them (it only places at air cells where the map expects a block), so they
        // used to surface as a giant error storm mid-map (379 phantom errors) and
        // trigger the repair teardown loop. Break them up front instead.
        ArrayList<BlockPos> leftovers = new ArrayList<>();
        for (int x = workingInterval.getLeft(); x <= workingInterval.getRight() && x < 128; x++) {
            if (!Utils.isInInterval(workingInterval, x)) continue;
            for (int z = 0; z < 128; z++) {
                if (map[x][z] != null) continue;
                BlockState current = MapAreaCache.getVerifiedBlockState(mapCorner.add(x, 0, z));
                if (current != null && !current.isAir() && current.getFluidState().isEmpty()) leftovers.add(mapCorner.add(x, 0, z));
            }
        }
        calculateBuildingPath(startNorthToSouth.get(), true);
        if (!leftovers.isEmpty()) {
            int cap = Math.min(leftovers.size(), 512);
            HiveLog.log("CANVAS DIRTY: " + leftovers.size() + " leftover block(s) in my rows"
                + (leftovers.size() > cap ? " (breaking first " + cap + ")" : "") + " - e.g. " + leftovers.get(0).toShortString());
            warning("Canvas has §a" + leftovers.size() + "§7 leftover block(s) in my rows - clearing them first.");
            for (int i = 0; i < cap; i++) {
                checkpoints.add(0, new Pair(leftovers.get(i).toCenterPos(), new Pair("break", leftovers.get(i))));
            }
            checkpoints.add(0, new Pair(leftovers.get(0).toCenterPos(), new Pair("lineEnd", null)));
        }
        // Slaves keep their leftover materials (no dump trip) so they can be
        // re-assigned instantly; they restock on demand instead.
        if (!SlaveSystem.isSlave()) checkpoints.add(0, new Pair(dumpStation.getLeft(), new Pair("dump", null)));
        state = State.Walking;
    }

    private boolean endBuilding() {
        info("Finished building map");
        finalizePhase = true;
        cornerCounter = 0;
        HiveLog.log("MAP FINISHED " + (mapFile != null ? mapFile.getName() : "<none>")
            + " - finalize phase ON (master-only dump/cartography/wipe)");
        state = State.Walking;
        knownErrors.clear();
        SlaveSystem.setAllSlavesUnfinished();
        // Pre-finalize verify: a slave walks the quadrant centers (loads every
        // chunk) and rescans the whole canvas BEFORE any finalize assignment.
        // This catches wrong blocks and air gaps in ANY bot's rows - the old
        // flow only detected air gaps at the wipe gate (wrong colors silently
        // ruined the crafted map item).
        if (!SlaveSystem.isSlave() && !SlaveSystem.slaves.isEmpty()
            && !verifyComplete && verifyAttempts < MAX_VERIFY_ATTEMPTS) {
            assignVerify(null);
            state = State.AwaitVerify;
            return true;
        }
        if (!SlaveSystem.slaves.isEmpty() && !verifyComplete) {
            HiveLog.log("VERIFY skipped - attempt cap reached, relying on the wipe gate");
            verifyComplete = true;
        }
        proceedMasterSelfFinalize();
        return true;
    }

    /**
     * The master runs the finalize itself (dump, cartography, finished-map chest).
     * Used in non-anchor mode and as the fallback when no delegate is available.
     */
    private void proceedMasterSelfFinalize() {
        Pair<BlockPos, Vec3d> bestChest = getBestChest(Items.CARTOGRAPHY_TABLE);
        if (bestChest == null) {
            // HOLD loudly instead of looping: the old code returned into the
            // build loop, which re-ran endBuilding every tick forever.
            HiveLog.log("FINALIZE HOLD - no cartography table found; cannot finalize");
            warning("§cCannot finalize: no cartography table found in the material chests. Stopping - fix the setup and re-enable the module.");
            toggle();
            return;
        }
        checkpoints.clear();
        checkpoints.add(new Pair(dumpStation.getLeft(), new Pair("dump", null)));
        checkpoints.add(new Pair(bestChest.getRight(), new Pair("mapMaterialChest", bestChest.getLeft())));
        renameMapFile();
        state = State.Walking;
    }

    /** Picks a slave with a fresh heartbeat for the verify assignment (never the previous verifier). */
    private String pickHealthySlave() {
        for (String s : SlaveSystem.slaves) {
            if (!s.equals(verifySlave) && SlaveSystem.hasFreshHeartbeat(s)) return s;
        }
        for (String s : SlaveSystem.slaves) {
            if (!s.equals(verifySlave)) return s;
        }
        return null;
    }

    /**
     * Assigns a slave to verify the canvas before the finalize runs. The slave
     * walks the four quadrant centers (loading every chunk), scans every cell
     * against the map and repairs what it can, then reports verifyDone:&lt;n&gt;.
     */
    private void assignVerify(String preferred) {
        String slave = preferred != null && SlaveSystem.slaves.contains(preferred)
            && !preferred.equals(verifySlave) ? preferred : pickHealthySlave();
        if (slave == null) {
            // No slave can verify - fall back to the wipe gate as the only guard
            HiveLog.log("VERIFY skipped - no available slave; relying on the wipe gate");
            verifyComplete = true;
            return;
        }
        verifySlave = slave;
        awaitingVerify = true;
        verifyWatchdogTicks = 0;
        verifyAttempts++;
        SlaveSystem.sendCommand(slave, HiveCommand.VERIFY, null);
        HiveLog.log("VERIFY assigned to " + slave + " (attempt " + verifyAttempts + "/" + MAX_VERIFY_ATTEMPTS + ")");
        info("Assigning §a" + slave + "§7 to verify the canvas before finalize...");
    }

    /** Master-side: the verify pass finished; continue with the finalize flow. */
    @Override
    public void verifyDone(String slave, int remaining) {
        if (!SlaveSystem.isSlave() && slave != null && slave.equals(verifySlave)) {
            awaitingVerify = false;
            verifyComplete = true;
            verifySlave = null;
            if (remaining > 0) {
                warning("Canvas verification finished with §c" + remaining + "§7 unresolved issue(s) - the wipe gate will hold if they matter.");
            } else {
                info("Canvas verified clean by §a" + slave + "§7.");
            }
            proceedToFinalize(slave);
        }
    }

    /** Continues the finalize flow after verification: delegate or self-finalize. */
    private void proceedToFinalize(String verifier) {
        if (useAfkAnchor()) {
            delegateFinalize(verifier);
            if (state == State.AwaitVerify) goAfk();
        } else {
            proceedMasterSelfFinalize();
        }
    }

    /** Slave-side: verify the whole canvas (4 quadrant center walks + full rescan + repair rounds). */
    @Override
    public void runVerify() {
        if (!SlaveSystem.isSlave() || map == null || mapCorner == null) return;
        verifyingForMaster = true;
        finalizePhase = true;
        verifyRound = 0;
        checkpoints.clear();
        addVerifyPathCheckpoints();
        state = State.Walking;
        HiveLog.log("VERIFY accepted - walking quadrant centers then rescanning");
        info("Verifying the canvas for the master...");
    }

    /** Appends the four quadrant-center walk points (loads every chunk) ending in a full rescan. */
    private void addVerifyPathCheckpoints() {
        checkpoints.add(new Pair<>(mapCorner.add(32, 0, 32).toCenterPos(), new Pair<>("verifyPoint", null)));
        checkpoints.add(new Pair<>(mapCorner.add(96, 0, 32).toCenterPos(), new Pair<>("verifyPoint", null)));
        checkpoints.add(new Pair<>(mapCorner.add(32, 0, 96).toCenterPos(), new Pair<>("verifyPoint", null)));
        checkpoints.add(new Pair<>(mapCorner.add(96, 0, 96).toCenterPos(), new Pair<>("verifyScan", null)));
    }

    /**
     * Full-canvas scan: missing (air at expected block), wrong (block identity
     * mismatch) and leftover (non-air at null cell, fluids skipped) positions.
     * Unknown (unloaded) chunks count as missing - never as OK.
     */
    private ArrayList<BlockPos> listBuildIssuesFullArea(int limit) {
        ArrayList<BlockPos> issues = new ArrayList<>();
        if (map == null || mapCorner == null) return issues;
        for (int x = 0; x < 128 && (limit <= 0 || issues.size() < limit); x++) {
            for (int z = 0; z < 128 && (limit <= 0 || issues.size() < limit); z++) {
                BlockPos pos = mapCorner.add(x, 0, z);
                BlockState current = MapAreaCache.getVerifiedBlockState(pos);
                if (map[x][z] == null) {
                    if (current != null && !current.isAir() && current.getFluidState().isEmpty()) issues.add(pos);
                } else if (current == null || current.isAir()) {
                    issues.add(pos);
                } else if (!current.getBlock().equals(map[x][z]) && current.getFluidState().isEmpty()) {
                    issues.add(pos);
                }
            }
        }
        return issues;
    }

    /** Moves the finished map NBT to the _finished_maps folder (master-side bookkeeping). */
    private void renameMapFile() {
        try {
            if (mapFile == null) return;
            // Guard: only rename the map that was actually finalized (a re-delegate
            // or map change between delegation and completion must not rename the
            // wrong file)
            if (delegatedMapFileName != null && !delegatedMapFileName.equals(mapFile.getName())) {
                warning("Skipping finished-map rename: current file " + mapFile.getName()
                    + " does not match the delegated map " + delegatedMapFileName + ".");
                return;
            }
            if (moveToFinishedFolder.get()) {
                File finishedFile = new File(mapFile.getParentFile().getAbsolutePath() + File.separator + "_finished_maps" + File.separator + mapFile.getName());
                if (mapFile.renameTo(finishedFile)) {
                    info("Moved finished map to _finished_maps: §a" + mapFile.getName());
                } else {
                    warning("Failed to move map file " + mapFile.getName() + " to finished map folder. It will be skipped on the next map selection.");
                }
            }
        } catch (Exception e) {
            warning("Failed to move map file " + mapFile.getName() + " to finished map folder");
            e.printStackTrace();
        }
    }

    /** Slave-side: reached the last quadrant center - scan, repair, repeat (max 3 rounds). */
    private void handleVerifyScan() {
        ArrayList<BlockPos> issues = listBuildIssuesFullArea(MAX_VERIFY_REPAIRS);
        if (issues.isEmpty()) {
            HiveLog.log("VERIFY scan clean (round " + verifyRound + ")");
            reportVerifyDone(0);
            return;
        }
        verifyRound++;
        if (verifyRound > 3) {
            HiveLog.log("VERIFY gave up after 3 repair rounds - " + issues.size() + "+ issue(s) remain");
            reportVerifyDone(issues.size());
            return;
        }
        HiveLog.log("VERIFY round " + verifyRound + ": repairing " + issues.size() + " issue(s)");
        warning("Canvas verification round §a" + verifyRound + "§7: " + issues.size() + " issue(s) to fix...");
        checkpoints.clear();
        for (BlockPos p : issues) {
            BlockPos rel = p.subtract(mapCorner);
            BlockState current = MapAreaCache.getVerifiedBlockState(p);
            boolean needsBreak = map[rel.getX()][rel.getZ()] == null
                || (current != null && !current.isAir());
            // Break wrong/leftover blocks; for air gaps just walk there - the
            // placement loop re-places them (restock happens via the slave branch).
            checkpoints.add(new Pair(p.toCenterPos(),
                new Pair<>(needsBreak ? "break" : "", needsBreak ? p : null)));
        }
        addVerifyPathCheckpoints();
        state = State.Walking;
    }

    /** Slave-side: report the verify result to the master and park. */
    private void reportVerifyDone(int remaining) {
        verifyingForMaster = false;
        SlaveSystem.queueMasterDM("verifyDone:" + remaining);
        state = State.AwaitSlaveNextMap;
        Utils.setForwardPressed(false);
    }

    // Master AFK anchoring + delegated finalize (hivemind duper anchoring)

    /**
     * True when the master should act as the duper chunk anchor in this hivemind:
     * an AFK spot is configured and at least one slave exists (solo printing keeps
     * today's behavior - the assumed external AFK account handles the dupers).
     */
    private boolean useAfkAnchor() {
        return afkAnchor.get() && !SlaveSystem.isSlave() && afkSpot != null && !SlaveSystem.slaves.isEmpty();
    }

    /** Master walks to the AFK spot and stands there, keeping the duper chunks loaded. */
    private void goAfk() {
        if (state == State.Afk) return;
        checkpoints.clear();
        checkpoints.add(new Pair<>(afkSpot.getRight(), new Pair<>("parkAfk", null)));
        state = State.Walking;
        HiveLog.log("AFK master walking to the duper anchor spot");
        info("My rows are done - walking to the AFK spot to anchor the dupers.");
    }

    /**
     * Hands the physical finalize tasks (dump, cartography, finished-map chest,
     * wipe) to a slave because the master is anchoring the dupers. Master-side
     * bookkeeping (finalize flag, file rename) still happens here.
     */
    private void delegateFinalize(String preferred) {
        // One-time per map: a slave verifies the canvas (quadrant-center walk +
        // full rescan) BEFORE the finalize assignment goes out.
        if (!verifyComplete) {
            if (!SlaveSystem.slaves.isEmpty() && verifyAttempts < MAX_VERIFY_ATTEMPTS) {
                assignVerify(preferred);
                state = State.AwaitVerify;
                return;
            }
            HiveLog.log("VERIFY skipped - no slaves available (attempts: " + verifyAttempts + ")");
            verifyComplete = true;
        }
        // Fallback: no delegate available or the re-delegation cap is burned -
        // the master runs the finalize itself instead of waiting in Afk forever.
        if (SlaveSystem.slaves.isEmpty() || finalizeRedelegations >= 2) {
            HiveLog.log("FINALIZE fallback - no delegate available, master runs the finalize itself"
                + (finalizeRedelegations >= 2 ? " (re-delegate cap reached)" : ""));
            warning("§cNo slave can run the finalize - the master is doing it itself.");
            finalizeDelegate = null;
            proceedMasterSelfFinalize();
            return;
        }
        String slave = preferred != null && SlaveSystem.slaves.contains(preferred)
            ? preferred : SlaveSystem.slaves.get(0);
        finalizeDelegate = slave;
        finalizeWatchdogTicks = 0;
        finalizePhase = true;
        cornerCounter = 0;
        knownErrors.clear();
        SlaveSystem.setAllSlavesUnfinished();
        delegatedMapFileName = mapFile != null ? mapFile.getName() : null;
        renameMapFile();
        SlaveSystem.sendCommand(slave, HiveCommand.FINALIZE, null);
        HiveLog.log("FINALIZE delegated to " + slave + " (master anchoring dupers)");
        info("Finalize delegated to §a" + slave + "§7 - master is anchoring the dupers.");
    }

    /** Slave-side: executes the delegated finalize checkpoint sequence. */
    @Override
    public void runFinalize() {
        if (!SlaveSystem.isSlave()) return;
        if (dumpStation == null || finishedMapChest == null || mapCorner == null) {
            warning("Cannot run delegated finalize: setup incomplete.");
            return;
        }
        Pair<BlockPos, Vec3d> bestChest = getBestChest(Items.CARTOGRAPHY_TABLE);
        if (bestChest == null) {
            warning("Cannot run delegated finalize: no cartography material chest found.");
            return;
        }
        finalizingForMaster = true;
        finalizePhase = true;
        state = State.Walking;
        knownErrors.clear();
        checkpoints.add(new Pair(dumpStation.getLeft(), new Pair("dump", null)));
        checkpoints.add(new Pair(bestChest.getRight(), new Pair("mapMaterialChest", bestChest.getLeft())));
        HiveLog.log("FINALIZE accepted - running dump/cartography/finished-chest/wipe");
        info("Finalize delegated to me: running dump, cartography, finished-map chest and wipe...");
    }

    /** Master-side: the delegated finalize finished - exit AFK and load the next map. */
    @Override
    public void finalizeComplete() {
        if (SlaveSystem.isSlave()) return;
        finalizeDelegate = null;
        finalizeWatchdogTicks = 0;
        info("Delegate finished the finalize - loading the next map...");
        state = State.AwaitNBTFile; // exits the Afk state: next tick loads/broadcasts the next map
    }

    /** Counts blocks the map expects but the world (verified) doesn't have. Unknown chunks count as missing. */
    private int countMissingBlocksFullArea() {
        return listMissingBlocksFullArea(0).size();
    }

    /** Lists missing blocks (up to {@code limit}, 0 = unlimited). Unknown (unloaded) chunks count as missing. */
    private ArrayList<BlockPos> listMissingBlocksFullArea(int limit) {
        ArrayList<BlockPos> missing = new ArrayList<>();
        if (map == null || mapCorner == null) return missing;
        for (int x = 0; x < 128 && (limit <= 0 || missing.size() < limit); x++) {
            for (int z = 0; z < 128 && (limit <= 0 || missing.size() < limit); z++) {
                if (map[x][z] == null) continue;
                BlockState state = MapAreaCache.getVerifiedBlockState(mapCorner.add(x, 0, z));
                if (state == null || state.isAir()) {
                    missing.add(mapCorner.add(x, 0, z));
                }
            }
        }
        return missing;
    }

    public boolean triggerWipeSequence() {
        if (resetButton == null || perimeterCorners == null || perimeterCorners.size() < 4 || mapCorner == null) {
            warning("Cannot trigger wipe sequence: reset button, perimeter corners, or map corner not configured.");
            return false;
        }
        // Ensure the bot stops within reach of the button for both presses
        Vec3d pressPos = getSafeButtonPressPos();
        // 1. Press button to start water
        checkpoints.add(new Pair(pressPos, new Pair("pressButton", null)));
        // 2. Walk perimeter to ensure water clears everything
        for (Pair<BlockPos, Vec3d> corner : perimeterCorners) {
            checkpoints.add(new Pair(corner.getRight(), new Pair("wipe", null)));
        }
        // 3. Press button again to retract water
        checkpoints.add(new Pair(pressPos, new Pair("pressButton", null)));
        // 4. Walk perimeter again to ensure all chunks load and water retracts
        for (Pair<BlockPos, Vec3d> corner : perimeterCorners) {
            checkpoints.add(new Pair(corner.getRight(), new Pair("wipe", null)));
        }
        // 5. Walk to center and check area is clear (map area is always 128x128)
        Vec3d resetCenter = mapCorner.add(64, 0, 64).toCenterPos();
        checkpoints.add(new Pair(resetCenter, new Pair("awaitClear", null)));
        isWiping = true;
        state = State.Walking;
        return true;
    }

    public void triggerDebugWipe() {
        checkpoints.clear();
        if (triggerWipeSequence()) {
            debugWipeOnly = true;
            info("Debug wipe started. Walking to reset button...");
        }
    }

    // Inventory Management

    private boolean setupSlots() {
        availableSlots = Utils.getAvailableSlots(materialDict);
        for (int slot : availableSlots) {
            if (slot < 9) {
                availableHotBarSlots.add(slot);
            }
        }
        info("Inventory slots available for building: " + availableSlots);
        if (availableHotBarSlots.isEmpty()) {
            warning("No free slots found in hot-bar!");
            availableSlots.clear();
            toggle();
            return false;
        }
        if (availableSlots.size() < 2) {
            warning("You need at least 2 free inventory slots!");
            availableSlots.clear();
            toggle();
            return false;
        }
        return true;
    }

    /**
     * Finds an empty player-inventory slot in the current screen handler
     * (indices 36-44 = hotbar, 45+ = main inventory in the player screen handler).
     */
    private int findEmptyInventoryHandlerSlot() {
        try {
            var slots = mc.player.currentScreenHandler.slots;
            for (int i = 0; i < slots.size(); i++) {
                var slot = slots.get(i);
                // Player inventory slots only (not the dumper's own container)
                if (slot.inventory == mc.player.getInventory() && slot.getStack().isEmpty()) {
                    return i;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private int getDumpSlot() {
        HashMap<Item, Integer> requiredItems = getRequiredItems();
        Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInformation = Utils.getInvInformation(requiredItems, availableSlots);
        if (invInformation.getLeft().isEmpty()) {
            return -1;
        }
        return invInformation.getLeft().get(0);
    }

    private HashMap<Item, Integer> getRequiredItems() {
        // Calculate the next items to restock
        HashMap<Item, Integer> requiredItems = new HashMap<>();
        boolean northToSouth = true;
        boolean hasFoundAir = false;
        for (int x = workingInterval.getLeft(); x <= workingInterval.getRight(); x += linesPerRun.get()) {
            int z = 0;
            while (z < 128) {
                boolean restartZLoop = false;
                for (int lineBonus = 0; lineBonus < linesPerRun.get(); lineBonus++) {
                    int adjustedX = x + lineBonus;
                    if (adjustedX > workingInterval.getRight()) break;
                    int adjustedZ = z;
                    if (!northToSouth) adjustedZ = 127 - z;
                    // VERIFIED lookup: an unknown (unloaded) chunk counts as needing
                    // material - conservative over-restock instead of under-restock
                    BlockState blockState = MapAreaCache.getVerifiedBlockState(mapCorner.add(adjustedX, 0, adjustedZ));
                    if ((blockState == null || blockState.isAir()) && map[adjustedX][adjustedZ] != null) {
                        if (!hasFoundAir) {
                            hasFoundAir = true;
                            BlockState oppositeBlockState = MapAreaCache.getVerifiedBlockState(mapCorner.add(adjustedX, 0, 127 - adjustedZ));
                            // If the first air block does not have an opposite air block, the snake pattern got reversed at some point
                            // We reverse the search too (unknown chunks don't trigger a reversal)
                            if (oppositeBlockState != null && !oppositeBlockState.isAir() && z < 64) {
                                northToSouth = !northToSouth;
                                restartZLoop = true;
                                break;
                            }
                        }
                        //ChatUtils.info("Add material for: " + mapCorner.add(x + lineBonus, 0, adjustedZ).toShortString());
                        Item material = map[adjustedX][adjustedZ].asItem();
                        if (!requiredItems.containsKey(material)) requiredItems.put(material, 0);
                        requiredItems.put(material, requiredItems.get(material) + 1);
                        //Check if the item fits into inventory. If not, undo the last increment and return
                        if (Utils.stacksRequired(requiredItems.values()) > availableSlots.size()) {
                            requiredItems.put(material, requiredItems.get(material) - 1);
                            return requiredItems;
                        }
                    }
                }
                if (restartZLoop) {
                    z = 0;
                    continue;
                }
                z++;
            }
            northToSouth = !northToSouth;
        }
        return requiredItems;
    }

    private void swapIntoHotbar(int slot) {
        Map<Item, Integer> itemSlot = new HashMap<>();
        Map<Item, Integer> blocksUntilItemUse = new HashMap<>();
        Map<Item, Integer> itemFrequency = new HashMap<>();

        int targetSlot = availableHotBarSlots.get(0);

        // Scan hotbar
        for (int hotbarSlot : availableHotBarSlots) {
            ItemStack stack = mc.player.getInventory().getStack(hotbarSlot);
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                itemSlot.put(item, hotbarSlot);
                blocksUntilItemUse.put(item, -1); // -1 = never used
                itemFrequency.put(item, 0);
            } else {
                targetSlot = hotbarSlot;
                break;
            }
        }

        // PRIORITY 1: empty slot → instant choice
        if (mc.player.getInventory().getStack(targetSlot).isEmpty()) {
            Utils.performSwap(slot, targetSlot);
            return;
        }

        // Get blocks until next use of items in hotbar
        int blockCounter = 0;
        boolean northToSouth = startNorthToSouth.get();
        boolean hasFoundAir = false;
        for (int x = workingInterval.getLeft(); x <= workingInterval.getRight(); x += linesPerRun.get()) {
            if (!Utils.isInInterval(workingInterval, x)) continue;

            for (int z = 0; z < 128; z++) {
                for (int lineBonus = 0; lineBonus < linesPerRun.get(); lineBonus++) {
                    int adjustedX = x + lineBonus;
                    int adjustedZ = z;
                    if (!northToSouth) adjustedZ = 127 - z;
                    if (!Utils.isInInterval(workingInterval, adjustedX)) break;

                    blockCounter++;
                    BlockState state = MapAreaCache.getCachedBlockState(mapCorner.add(adjustedX, 0, adjustedZ));
                    if (state.isAir()) {
                        if (!hasFoundAir) {
                            hasFoundAir = true;
                            BlockState oppositeBlockState = MapAreaCache.getCachedBlockState(mapCorner.add(adjustedX, 0, 127 - adjustedZ));
                            // If the first air block does not have an opposite air block, the snake pattern got reversed at some point
                            // We reverse the search too
                            if (!oppositeBlockState.isAir() && z < 64) {
                                northToSouth = !northToSouth;
                                adjustedZ = 127 - z;
                            }
                        }

                        Block block = map[adjustedX][adjustedZ];
                        if (block == null) continue;

                        Item item = block.asItem();

                        if (blocksUntilItemUse.containsKey(item) &&
                            blocksUntilItemUse.get(item) == -1) {
                            blocksUntilItemUse.put(item, blockCounter);
                        }
                    }
                }
            }
            northToSouth = !northToSouth;
        }

        // Count frequency of items in hotbar
        for (int hotbarSlot : availableHotBarSlots) {
            ItemStack stack = mc.player.getInventory().getStack(hotbarSlot);
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                itemFrequency.put(item, itemFrequency.get(item) + 1);
            }
        }

        // Choose best candidate
        Item bestItem = null;
        int bestDistance = -2; // lower than -1
        int bestFrequency = -1;

        for (Item item : itemSlot.keySet()) {
            int distance = blocksUntilItemUse.get(item); // -1 = never used
            int frequency = itemFrequency.get(item);

            boolean better = false;

            // PRIORITY 2: never used (-1)
            if (distance == -1 && bestDistance != -1) {
                better = true;
            }
            // PRIORITY 3: hotbar frequency
            else if (frequency > bestFrequency) {
                better = true;
            }
            // PRIORITY 4: distance to next use
            else if (frequency == bestFrequency && distance > bestDistance && bestDistance != -1) {
                better = true;
            }

            if (better) {
                bestItem = item;
                bestDistance = distance;
                bestFrequency = frequency;
            }
        }

        if (bestItem != null) {
            targetSlot = itemSlot.get(bestItem);
        }

        Utils.performSwap(slot, targetSlot);
    }

    // MapPrinter Interface for Slave Logic

    public void startPrinting() {
        if (!isActive()) {
            error("Enable the Carpet Printer module first (it auto-disables when no NBT map files are available).");
            return;
        }
        if (state != State.SelectingChests) {
            error("Cannot start printing in the current state. Please complete all registrations first.");
            return;
        }
        if (materialDict.isEmpty()) {
            warning("No Material Chests selected!");
            return;
        }
        if (mapMaterialChests.isEmpty()) {
            warning("No Map Chests selected!");
            return;
        }
        if (map == null || mapFile == null) {
            warning("No map NBT loaded - put a map file into the map folder and re-enable the module.");
            return;
        }
        if (!setupSlots()) {
            return;
        }
        // Make an inactive AFK-anchor LOUD before anything starts: with the
        // anchor inactive the master takes the middle rows and walks AWAY from
        // the dupers - their chunks unload and the duper farm breaks silently.
        if (!SlaveSystem.isSlave() && !SlaveSystem.slaves.isEmpty()) {
            if (!afkAnchor.get()) {
                warning("AFK-anchor is OFF - the master will build the MIDDLE rows and nobody anchors the dupers."
                    + " Turn the afk-anchor setting ON and set an AFK Spot if the dupers must stay loaded.");
                HiveLog.log("AFK-ANCHOR INACTIVE at start (toggle off) - dupers are NOT anchored");
            } else if (afkSpot == null) {
                warning("§cAFK-anchor is ON but no AFK Spot is configured - the master took middle rows and the dupers are NOT anchored."
                    + " Right-click the AFK spot to set it, then re-save the config.");
                HiveLog.log("AFK-ANCHOR INACTIVE at start (no AFK spot) - dupers are NOT anchored");
            }
        }
        startBuilding();
    }

    public void setInterval(Pair<Integer, Integer> interval) {
        workingInterval = interval;
        // Owner-only errors: a bot may only repair positions it owns. When the
        // interval changes (re-split/steal/handoff), stale errors from the old
        // assignment would make the new owner re-repair blocks another bot just
        // fixed - or vice versa. Errors outside the new interval are dropped;
        // whoever owns those rows now will re-detect them on their own scan.
        if (knownErrors != null && mapCorner != null && interval != null) {
            knownErrors.removeIf(p -> {
                int row = p.getX() - mapCorner.getX();
                return !Utils.isInInterval(interval, row);
            });
        }
    }

    public void addError(BlockPos relativeBlockPos) {
        BlockPos absoluteErrorPos = mapCorner.add(relativeBlockPos);
        if (!knownErrors.contains(absoluteErrorPos)) knownErrors.add(absoluteErrorPos);
    }

    public void pause() {
        if (!state.equals(CarpetPrinter.State.AwaitSlaveContinue)) {
            oldState = state;
            state = CarpetPrinter.State.AwaitSlaveContinue;
            Utils.setForwardPressed(false);
        }
    }

    public void start() {
        if (SlaveSystem.isSlave()) {
            // Never disturb an in-progress verify/finalize: a STALL re-partition
            // sends START to every slave, which used to wipe the checkpoint path.
            if (verifyingForMaster || finalizingForMaster) return;
            // Resume after a pause
            if (state.equals(State.AwaitSlaveContinue) && oldState != null && map != null
                && (oldState == State.Walking || oldState == State.Dumping)) {
                state = oldState;
                pendingStart = false;
                return;
            }
            // Fresh start from a parked/waiting state
            if (map != null && hasFullSetup()) {
                pendingStart = false;
                startBuilding();
            } else {
                pendingStart = true;
                if (state != State.AwaitMasterMap) state = State.AwaitMasterMap;
            }
            return;
        }
        if (availableSlots.isEmpty() || state.equals(State.AwaitSlaveNextMap)) {
            state = State.AwaitNBTFile;
            return;
        }
        if (state.equals(State.AwaitSlaveContinue)) {
            state = oldState;
        }
    }

    public boolean getActivationReset() {
        return activationReset.get();
    }

    public void skipBuilding() {
    }

    public void mineLine(int lines) {
    }

    public void slaveFinished(String slave) {
        slaveUnfinished.put(slave, 0);
        // Work balancing: before parking this slave, give it the tail of the
        // busiest bot's remaining rows (only during the build phase).
        if (!finalizePhase && stealWork(slave)) return;
        // AFK-anchor mode: master is parked at the dupers and every slave is done -
        // delegate the finalize to this (last) slave instead of parking it.
        // (ownUnfinished may be stale if the master handed its rows off already,
        // so a parked master always delegates.)
        if (useAfkAnchor() && !finalizePhase && (state == State.Afk || ownUnfinished <= 0)
            && SlaveSystem.allSlavesFinished()) {
            delegateFinalize(slave);
            return;
        }
        // Master assigns the finished slave one of the perimeter corners to park at
        if (perimeterCorners.isEmpty()) return;
        int corner = cornerCounter % perimeterCorners.size();
        cornerCounter++;
        HiveLog.log("PARK " + slave + " -> perimeter corner " + (corner + 1) + " (corner " + corner + ")");
        SlaveSystem.sendCommand(slave, HiveCommand.GO_TO_CORNER, String.valueOf(corner));
    }

    // Hivemind extensions (WebSocket transport)

    /**
     * Slave-side: this bot accepted an invite. It activated with an empty
     * master-address (so it went through the MASTER setup flow - and was even
     * self-hosting) - leave that flow and wait for the master's setup instead.
     * The master-address setting is synced too, so a later module re-toggle
     * keeps this bot in slave mode instead of re-hosting its own hive.
     */
    @Override
    public void onInviteAccepted() {
        String joinedAddress = SlaveSystem.masterAddress;
        if (!masterAddress.get().equals(joinedAddress)) {
            masterAddress.set(joinedAddress); // onChanged re-syncs SlaveSystem (same value)
        }
        state = State.AwaitSetup;
        info("Invite accepted - waiting for setup from master " + joinedAddress + "...");
    }

    private boolean hasFullSetup() {
        return resetButton != null && cartographyTable != null && finishedMapChest != null
            && dumpStation != null && mapCorner != null && !materialDict.isEmpty()
            && !mapMaterialChests.isEmpty() && perimeterCorners.size() >= 4
            && (!afkAnchor.get() || afkSpot != null);
    }

    @Override
    public void broadcastSetup() {
        if (SlaveSystem.isSlave()) return;
        if (!hasFullSetup()) {
            warning("Setup incomplete, cannot broadcast to slaves.");
            return;
        }
        if (SlaveSystem.slaves.isEmpty()) {
            warning("No registered slaves to broadcast to.");
            return;
        }
        // Debounce per slave: the invite/accept flow can trigger several
        // broadcasts in a burst (~14KB each). A slave only needs the setup once
        // per distinct payload - but a NEWLY registered slave must always
        // receive it, even if the payload is identical to the last broadcast.
        String payload = ConfigSerializer.toJsonString(
            "carpet", resetButton, cartographyTable, finishedMapChest, mapMaterialChests,
            dumpStation, mapCorner, materialDict, perimeterCorners, afkSpot);
        if (!payload.equals(lastBroadcastPayload)) {
            lastBroadcastPayload = payload;
            slavesWithCurrentSetup.clear();
        }
        int sent = 0;
        for (String slave : SlaveSystem.slaves) {
            if (slavesWithCurrentSetup.contains(slave)) continue;
            SlaveSystem.queueDM(slave, "config:" + payload);
            slavesWithCurrentSetup.add(slave);
            sent++;
        }
        if (sent == 0) {
            HiveLog.log("SETUP broadcast skipped (all slaves already have this setup)");
        } else {
            info("Setup broadcast to " + sent + " slave(s).");
        }
    }

    @Override
    public void slaveRegistered(String slave) {
        if (SlaveSystem.isSlave()) return;
        if (!hasFullSetup()) {
            // Setup not done yet - remember to broadcast automatically once it is
            pendingSetupBroadcast = true;
            info("Slave " + slave + " registered - setup incomplete, will broadcast automatically when ready.");
            return;
        }

        // Welcome packet: setup, current map (if one is active) and a fresh interval.
        SlaveSystem.queueDM(slave, "config:" + ConfigSerializer.toJsonString(
            "carpet", resetButton, cartographyTable, finishedMapChest, mapMaterialChests,
            dumpStation, mapCorner, materialDict, perimeterCorners, afkSpot));

        if (finalizePhase) {
            // The map is being finalized/wiped - park the newcomer at a corner,
            // it will be put to work with the next map.
            int corner = cornerCounter % perimeterCorners.size();
            cornerCounter++;
            SlaveSystem.queueDM(slave, "goToCorner:" + corner);
            return;
        }

        if (map != null && mapFile != null && (state == State.Walking || state == State.Dumping
            || state == State.AwaitMasterAllBuilt || state == State.Afk)) {
            // Afk included: while the master anchors the dupers the build phase is
            // still running - a late joiner must receive the map or it idles in
            // AwaitMasterMap forever (and its assigned rows are never built).
            sendMapTo(slave);
            SlaveSystem.queueDM(slave, "start");
        }
    }

    private void sendMapTo(String slave) {
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(mapFile.toPath());
        } catch (Exception e) {
            warning("Failed to read map file for transmission: " + mapFile.getName()
                + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            HiveLog.log("MAP SEND " + mapFile.getName() + " to " + slave + " FAILED reading file: "
                + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return;
        }
        try {
            long crc = crc32(bytes);
            String b64 = Base64.getEncoder().encodeToString(bytes);
            // Chunked transfer: one giant frame proved unreliable on some slave
            // connections (arrived truncated -> corrupt NBT). Smaller chunks with
            // a CRC let the slave verify and request a re-send.
            int chunkSize = 300000;
            int total = (b64.length() + chunkSize - 1) / chunkSize;
            HiveLog.log("MAP SEND " + mapFile.getName() + " to " + slave + ": " + bytes.length
                + " bytes, crc " + crc + ", " + total + " chunk(s)");
            for (int i = 0; i < total; i++) {
                int from = i * chunkSize;
                int to = Math.min(b64.length(), from + chunkSize);
                SlaveSystem.queueDM(slave, "map:" + mapFile.getName() + ":" + crc + ":" + total + ":" + i + ":" + b64.substring(from, to));
            }
        } catch (Exception e) {
            warning("Failed to read map file for transmission: " + mapFile.getName());
            e.printStackTrace();
        }
    }

    private static long crc32(byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    @Override
    public void applySetup(String json) {
        try {
            ConfigDeserializer.ConfigData data = ConfigDeserializer.readFromString(json);
            if (data == null || !data.type.equals("carpet") || data.mapCorner == null
                || data.resetButton == null || data.cartographyTable == null
                || data.finishedMapChest == null || data.dumpStation == null
                || data.materialDict.isEmpty() || data.perimeterCorners.size() < 4) {
                warning("Received incomplete setup from master.");
                return;
            }
            this.resetButton = data.resetButton;
            this.cartographyTable = data.cartographyTable;
            this.finishedMapChest = data.finishedMapChest;
            this.mapMaterialChests = data.mapMaterialChests;
            this.dumpStation = data.dumpStation;
            this.mapCorner = data.mapCorner;
            MapAreaCache.reset(mapCorner);
            this.materialDict = data.materialDict;
            this.perimeterCorners = (ArrayList<Pair<BlockPos, Vec3d>>) data.perimeterCorners;
            this.afkSpot = data.afkSpot; // slaves receive it for future-proofing; only the master uses it
            info("Setup received from master.");
            if (state == null || state == State.AwaitSetup) state = State.AwaitMasterMap;
        } catch (Exception e) {
            warning("Failed to parse setup from master.");
            e.printStackTrace();
        }
    }

    // Chunked map reception state (slave side)
    private final HashMap<Integer, String> pendingMapChunks = new HashMap<>();
    private String pendingMapName = null;
    private int pendingMapTotal = -1;
    private long pendingMapCrc = -1;
    private long loadedMapCrc = -1;
    private int pendingMapStallTicks = 0;
    private int pendingMapRetries = 0;

    /**
     * Asks the master to re-send the map. Gives up after 5 attempts so a dead
     * transfer cannot loop forever - the outcome is always logged.
     */
    private void requestRemap(String fileName, String reason) {
        if (pendingMapRetries >= 5) {
            error("Gave up receiving map §a" + fileName + "§7 after 5 re-requests. Last failure: " + reason);
            HiveLog.log("MAP RECEIVE " + fileName + " GAVE UP after 5 re-requests - last failure: " + reason);
            pendingMapName = null;
            pendingMapChunks.clear();
            pendingMapTotal = -1;
            pendingMapRetries = 0;
            // Tell the master - otherwise it waits for us forever: with no map
            // our rows can never be built and the whole hive stalls on this map.
            SlaveSystem.queueMasterDM("mapFailed:" + fileName);
            return;
        }
        pendingMapRetries++;
        warning("Map '" + fileName + "' transfer problem (" + reason + ") - requesting re-send (attempt "
            + pendingMapRetries + "/5).");
        HiveLog.log("MAP RECEIVE " + fileName + " re-request " + pendingMapRetries + "/5 - reason: " + reason);
        SlaveSystem.queueMasterDM("remap");
    }

    @Override
    public void applyMapData(String message) {
        if (!SlaveSystem.isSlave()) return;
        try {
            // Format: <fileName>:<crc32>:<totalChunks>:<chunkIndex>:<base64Chunk>
            String[] parts = message.split(":", 5);
            if (parts.length < 5) return;
            String fileName = parts[0];
            long crc;
            int total;
            int idx;
            try {
                crc = Long.parseLong(parts[1]);
                total = Integer.parseInt(parts[2]);
                idx = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                return;
            }
            String b64 = parts[4];

            // Skip a redundant re-send while already building the same map
            if (idx == 0 && mapFile != null && mapFile.getName().equals(fileName) && map != null
                && crc == loadedMapCrc && (state == State.Walking || state == State.Dumping)) return;

            // New transfer (different file/checksum) resets the assembly buffer
            if (pendingMapName == null || !pendingMapName.equals(fileName) || pendingMapCrc != crc) {
                pendingMapName = fileName;
                pendingMapCrc = crc;
                pendingMapTotal = total;
                pendingMapChunks.clear();
                pendingMapStallTicks = 0;
                pendingMapRetries = 0;
            }
            if (pendingMapChunks.put(idx, b64) == null && pendingMapChunks.size() == 1) {
                info("Receiving map §a" + fileName + "§7 from master (" + total + " chunk(s))...");
            }

            // Wait for all chunks before assembling (TCP delivers them in order;
            // a missing chunk means the transfer stalled - the tick watchdog re-requests)
            if (pendingMapChunks.size() < pendingMapTotal) return;

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pendingMapTotal; i++) sb.append(pendingMapChunks.get(i));
            byte[] bytes = Base64.getDecoder().decode(sb.toString());
            pendingMapName = null;
            pendingMapChunks.clear();
            pendingMapTotal = -1;

            if (crc32(bytes) != crc) {
                pendingMapName = null;
                pendingMapChunks.clear();
                pendingMapTotal = -1;
                requestRemap(fileName, "CRC mismatch (received " + bytes.length + " bytes)");
                return;
            }

            File target = new File(mapFolder, fileName);
            try {
                java.nio.file.Files.write(target.toPath(), bytes);
                long written = java.nio.file.Files.size(target.toPath());
                if (written != bytes.length) {
                    requestRemap(fileName, "file write truncated (wrote " + written + "/" + bytes.length + " bytes)");
                    return;
                }
                HiveLog.log("MAP RECEIVE " + fileName + " wrote " + written + " bytes to disk");
            } catch (Exception e) {
                requestRemap(fileName, "file write failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                HiveLog.log("MAP RECEIVE " + fileName + " WRITE FAILED: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
                return;
            }
            mapFile = target;
            startedFiles.add(target);
            if (!loadNBTFile()) {
                requestRemap(fileName, "NBT parse failed");
                return;
            }
            loadedMapCrc = crc;
            pendingMapRetries = 0;
            HiveLog.log("MAP RECEIVE " + fileName + " OK (" + bytes.length + " bytes)");
            if (pendingStart && hasFullSetup()) {
                pendingStart = false;
                startBuilding();
            } else if (state == State.AwaitMasterMap || state == State.AwaitSetup) {
                state = State.AwaitMasterMap;
            }
        } catch (Exception e) {
            requestRemap(pendingMapName != null ? pendingMapName : "<unknown>", "exception: " + e);
            e.printStackTrace();
        }
    }

    @Override
    public void resendMap(String slave) {
        if (SlaveSystem.isSlave() || finalizePhase) return;
        if (mapFile == null) return;
        info("Re-sending map §a" + mapFile.getName() + "§7 to " + slave + ".");
        sendMapTo(slave);
    }

    @Override
    public void goToCorner(int cornerIndex) {
        if (!SlaveSystem.isSlave() || perimeterCorners.size() <= cornerIndex || mapCorner == null) return;
        Pair<BlockPos, Vec3d> corner = perimeterCorners.get(cornerIndex);
        // Park AWAY from the map: a slave standing on the corner approach
        // position physically blocks the master's/delegate's wipe perimeter
        // walk, stalling the whole finalize before it even starts.
        Vec3d parkPos = corner.getRight();
        Vec3d center = mapCorner.add(64, 0, 64).toCenterPos();
        Vec3d away = new Vec3d(parkPos.x - center.x, 0, parkPos.z - center.z);
        if (away.lengthSquared() > 0.01) {
            parkPos = parkPos.add(away.normalize().multiply(2.0));
        }
        checkpoints.clear();
        checkpoints.add(new Pair<>(parkPos, new Pair<>("parkCorner", null)));
        state = State.Walking;
        info("Walking to perimeter corner " + (cornerIndex + 1) + " to wait for the master...");
    }

    @Override
    public void onIntervalsReassigned() {
        if (SlaveSystem.isSlave() || finalizePhase) return;
        // Progress estimates are stale after any interval change
        slaveUnfinished.clear();
        ownUnfinished = -1;
        // Re-activate parked slaves that received new rows from the re-split
        int reactivated = 0;
        for (String slave : SlaveSystem.slaves) {
            if (Boolean.TRUE.equals(SlaveSystem.finishedSlavesDict.get(slave))) {
                SlaveSystem.queueDM(slave, "start");
                reactivated++;
            }
        }
        if (reactivated > 0) HiveLog.log("REACTIVATED " + reactivated + " parked slave(s) after interval reassignment");
    }

    @Override
    public boolean isFinalizePhase() {
        return finalizePhase;
    }

    private boolean hasUnfinishedRowsInInterval() {
        return countUnfinishedRowsInInterval(workingInterval) > 0;
    }

    // Path Change Check

    private void warnPathChanged() {
        if (checkpoints != null && !activationReset.get()) {
            String reString = isActive() ? "re" : "";
            warning("The custom path is only applied if the module is " + reString + "started with Activation Reset enabled!");
        }
    }

    // Config System

    private void saveConfig(File configFile) {
        if (configFile == null) {
            error("No config file name selected.");
            return;
        }
        if (resetButton == null || cartographyTable == null || finishedMapChest == null || dumpStation == null || mapCorner == null || materialDict.isEmpty()) {
            error("Cannot save config: Missing required data.");
            return;
        }
        try {
            ConfigSerializer.writeToJson(
                configFile.toPath(),
                "carpet",
                resetButton,
                cartographyTable,
                finishedMapChest,
                mapMaterialChests,
                dumpStation,
                mapCorner,
                materialDict,
                perimeterCorners,
                afkSpot);
            Text configText = Text.literal(configFile.getName())
                .styled(style -> style
                    .withColor(Formatting.GREEN)
                    .withClickEvent(new ClickEvent.OpenFile(configFile.getAbsolutePath().toString()))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open config")))
                    .withUnderline(true));
            info(Text.literal("Successfully saved config to: ").append(configText));
        } catch (IOException e) {
            error("Failed to create config file.");
        }
    }

    private boolean loadConfig(File configFile) {
        if (configFile == null) {
            error("No config file selected.");
            return false;
        }
        if (!configFile.exists()) {
            error("Could not find config file: " + configFile.getAbsolutePath());
            return false;
        }
        // A null state means the module is inactive (e.g. it auto-disabled because
        // no NBT files are in the map folder yet). Loading a config while inactive
        // is fine - it pre-stages the setup for the next activation.
        if (state != null) {
            List<State> allowedStates = List.of(
                State.SelectingResetButton,
                State.SelectingPerimeterCorner1,
                State.SelectingPerimeterCorner2,
                State.SelectingPerimeterCorner3,
                State.SelectingPerimeterCorner4,
                State.SelectingChests,
                State.SelectingFinishedMapChest,
                State.SelectingAfkSpot,
                State.SelectingDumpStation,
                State.SelectingTable,
                State.SelectingMapArea,
                State.AwaitRegisterResponse
            );
            if (!allowedStates.contains(state)) {
                error("Can only load config during the registration phase.");
                return false;
            }
        }

        try {
            ConfigDeserializer.ConfigData data =
                ConfigDeserializer.readFromJson(configFile.toPath());

            if (!data.type.equals("carpet")) {
                error("Config file is of type " + data.type + " and not 'carpet'.");
                return false;
            }
            if (data.resetButton == null || data.cartographyTable == null || data.finishedMapChest == null || data.dumpStation == null || data.mapCorner == null || data.materialDict.isEmpty() || data.perimeterCorners.size() < 4) {
                error("Config file is missing required data.");
                return false;
            }
            this.resetButton = data.resetButton;
            this.cartographyTable = data.cartographyTable;
            this.finishedMapChest = data.finishedMapChest;
            this.mapMaterialChests = data.mapMaterialChests;
            this.dumpStation = data.dumpStation;
            this.mapCorner = data.mapCorner;
            MapAreaCache.reset(mapCorner);
            this.materialDict = data.materialDict;
            this.perimeterCorners = (ArrayList<Pair<BlockPos, Vec3d>>) data.perimeterCorners;
            this.afkSpot = data.afkSpot;
            Text configText = Text.literal(configFile.getName())
                .styled(style -> style
                    .withColor(Formatting.GREEN)
                    .withClickEvent(new ClickEvent.OpenFile(configFile.getAbsolutePath().toString()))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open config")))
                    .withUnderline(true));
            info(Text.literal("Successfully loaded config: ").append(configText));
            if (afkSpot == null && afkAnchor.get()) {
                warning("Config has no AFK Spot - right-click it now (near the dupers), then re-save the config.");
                state = State.SelectingAfkSpot;
            } else {
                info("Type .startprinter to start printing.");
                state = State.SelectingChests;
            }
            // Slaves may have registered before the config existed - broadcast it now
            if (!SlaveSystem.isSlave() && !SlaveSystem.slaves.isEmpty()) pendingSetupBroadcast = true;
        } catch (IOException e) {
            error("Failed to read config file.");
        }
        return true;
    }

    // NBT file handling

    private boolean prepareNextMapFile() {
        mapFile = Utils.getNextMapFile(mapFolder, startedFiles, moveToFinishedFolder.get());

        if (mapFile == null) {
            if (disableOnFinished.get()) {
                info("All nbt files finished");
                toggle();
            }
            return false;
        }
        if (!loadNBTFile()) {
            warning("Failed to read nbt file.");
            toggle();
            return false;
        }

        return true;
    }

    private boolean loadNBTFile() {
        info("Building: §a" + mapFile.getName());
        long fileSize = -1;
        try {
            fileSize = java.nio.file.Files.size(mapFile.toPath());
        } catch (Exception e) {
            HiveLog.log("NBT LOAD " + mapFile.getName() + " could not stat file: " + e);
            warning("Could not stat map file " + mapFile.getName() + ": " + e);
        }

        // Stage 1: gzip decompress + NBT parse
        NbtCompound nbt;
        try {
            NbtSizeTracker sizeTracker = new NbtSizeTracker(0x20000000L, 100);
            nbt = NbtIo.readCompressed(mapFile.toPath(), sizeTracker);
        } catch (Exception e) {
            warning("NBT parse failed for " + mapFile.getName() + " (" + fileSize + " bytes): "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            HiveLog.log("NBT LOAD " + mapFile.getName() + " STAGE parse FAILED (file " + fileSize
                + " bytes): " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        HiveLog.log("NBT LOAD " + mapFile.getName() + " parse OK (file " + fileSize + " bytes)");

        // Stage 2: palette extraction
        try {
            NbtList paletteList = (NbtList) nbt.get("palette");
            if (paletteList == null) {
                warning("Map " + mapFile.getName() + " has no 'palette' tag.");
                HiveLog.log("NBT LOAD " + mapFile.getName() + " STAGE palette FAILED: missing 'palette' tag. Root keys: " + nbt.getKeys());
                return false;
            }
            blockPaletteDict = Utils.getBlockPalette(paletteList);
        } catch (Exception e) {
            warning("Palette extraction failed for " + mapFile.getName() + ": "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            HiveLog.log("NBT LOAD " + mapFile.getName() + " STAGE palette FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        // Stage 3: drop ignored blocks
        try {
            List<Integer> toBeRemoved = new ArrayList<>();
            for (int key : blockPaletteDict.keySet()) {
                if (ignoredBlocks.get().contains(blockPaletteDict.get(key).getLeft())) toBeRemoved.add(key);
            }
            for (int key : toBeRemoved) blockPaletteDict.remove(key);
        } catch (Exception e) {
            warning("Ignoring blocks failed for " + mapFile.getName() + ": "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            HiveLog.log("NBT LOAD " + mapFile.getName() + " STAGE ignore-filter FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        // Stage 4: build the 128x128 map array
        try {
            NbtList blockList = (NbtList) nbt.get("blocks");
            if (blockList == null) {
                warning("Map " + mapFile.getName() + " has no 'blocks' tag.");
                HiveLog.log("NBT LOAD " + mapFile.getName() + " STAGE map-array FAILED: missing 'blocks' tag. Root keys: " + nbt.getKeys());
                return false;
            }
            map = Utils.generateMapArray(blockList, blockPaletteDict);
            if (map == null) {
                warning("Map array generation returned null for " + mapFile.getName() + ".");
                HiveLog.log("NBT LOAD " + mapFile.getName() + " STAGE map-array FAILED: generateMapArray returned null (blocks: " + blockList.size() + ")");
                return false;
            }
            HiveLog.log("NBT LOAD " + mapFile.getName() + " map array built: " + blockList.size() + " block entries, palette " + blockPaletteDict.size());
        } catch (Exception e) {
            warning("Map array generation failed for " + mapFile.getName() + ": "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            HiveLog.log("NBT LOAD " + mapFile.getName() + " STAGE map-array FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            map = null;
            return false;
        }

        info("Requirements: ");
        for (Pair<Block, Integer> p : blockPaletteDict.values()) {
            info(p.getLeft().getName().getString() + ": " + p.getRight());
        }

        return true;
    }

    // Rendering

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WTable table = new WTable();
        list.add(table);

        File configFolder = new File(mapFolder, "_configs");
        if (!configFolder.exists()) return table;

        table.add(theme.label("Configurations: "));
        // ---- Save config button ----
        WButton saveButton = table.add(theme.button("Save Config")).widget();
        saveButton.action = () -> {
            String path = TinyFileDialogs.tinyfd_saveFileDialog(
                "Save Config",
                new File(configFolder, "carpet-printer-config.json").getAbsolutePath(),
                null,
                null
            );
            if (path != null) saveConfig(new File(path));
        };

        // ---- Load config button ----
        WButton loadButton = table.add(theme.button("Load Config")).widget();
        loadButton.action = () -> {
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                "Load Config",
                new File(configFolder, "carpet-printer-config.json").getAbsolutePath(),
                null,
                null,
                false
            );
            if (path != null) loadConfig(new File(path));
        };
        table.row();

        WTable slaveTable = new WTable();
        list.add(slaveTable);

        SlaveTableController slaveController = new SlaveTableController(slaveTable, theme, false);
        slaveController.rebuild();

        SlaveSystem.tableController = slaveController;
        return list;
    }

    @Override
    public String getInfoString() {
        if (mapFile != null) {
            return mapFile.getName();
        } else {
            return "None";
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mapCorner == null || !render.get()) return;
        event.renderer.box(mapCorner, color.get(), color.get(), ShapeMode.Lines, 0);
        event.renderer.box(mapCorner.getX(), mapCorner.getY(), mapCorner.getZ(), mapCorner.getX() + 128, mapCorner.getY(), mapCorner.getZ() + 128, color.get(), color.get(), ShapeMode.Lines, 0);

        ArrayList<Pair<BlockPos, Vec3d>> renderedPairs = new ArrayList<>();
        for (ArrayList<Pair<BlockPos, Vec3d>> list : materialDict.values()) {
            renderedPairs.addAll(list);
        }
        renderedPairs.addAll(mapMaterialChests);
        for (Pair<BlockPos, Vec3d> pair : renderedPairs) {
            if (renderChestPositions.get())
                event.renderer.box(pair.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
            if (renderOpenPositions.get()) {
                Vec3d openPos = pair.getRight();
                event.renderer.box(openPos.x - indicatorSize.get(), openPos.y - indicatorSize.get(), openPos.z - indicatorSize.get(), openPos.x + indicatorSize.get(), openPos.y + indicatorSize.get(), openPos.z + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
        }

        if (renderCheckpoints.get()) {
            for (Pair<Vec3d, Pair<String, BlockPos>> pair : checkpoints) {
                Vec3d cp = pair.getLeft();
                event.renderer.box(cp.x - indicatorSize.get(), cp.y - indicatorSize.get(), cp.z - indicatorSize.get(), cp.getX() + indicatorSize.get(), cp.getY() + indicatorSize.get(), cp.getZ() + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
        }

        if (renderSpecialInteractions.get()) {
            if (resetButton != null) {
                event.renderer.box(resetButton.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
                event.renderer.box(resetButton.getRight().x - indicatorSize.get(), resetButton.getRight().y - indicatorSize.get(), resetButton.getRight().z - indicatorSize.get(), resetButton.getRight().getX() + indicatorSize.get(), resetButton.getRight().getY() + indicatorSize.get(), resetButton.getRight().getZ() + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
            if (perimeterCorners != null) {
                for (int i = 0; i < perimeterCorners.size(); i++) {
                    Pair<BlockPos, Vec3d> corner = perimeterCorners.get(i);
                    event.renderer.box(corner.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
                    event.renderer.box(corner.getRight().x - indicatorSize.get(), corner.getRight().y - indicatorSize.get(), corner.getRight().z - indicatorSize.get(), corner.getRight().getX() + indicatorSize.get(), corner.getRight().getY() + indicatorSize.get(), corner.getRight().getZ() + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
                }
            }
            if (cartographyTable != null) {
                event.renderer.box(cartographyTable.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
                event.renderer.box(cartographyTable.getRight().x - indicatorSize.get(), cartographyTable.getRight().y - indicatorSize.get(), cartographyTable.getRight().z - indicatorSize.get(), cartographyTable.getRight().getX() + indicatorSize.get(), cartographyTable.getRight().getY() + indicatorSize.get(), cartographyTable.getRight().getZ() + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
            if (dumpStation != null) {
                event.renderer.box(dumpStation.getLeft().x - indicatorSize.get(), dumpStation.getLeft().y - indicatorSize.get(), dumpStation.getLeft().z - indicatorSize.get(), dumpStation.getLeft().getX() + indicatorSize.get(), dumpStation.getLeft().getY() + indicatorSize.get(), dumpStation.getLeft().getZ() + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
            if (finishedMapChest != null) {
                event.renderer.box(finishedMapChest.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
                event.renderer.box(finishedMapChest.getRight().x - indicatorSize.get(), finishedMapChest.getRight().y - indicatorSize.get(), finishedMapChest.getRight().z - indicatorSize.get(), finishedMapChest.getRight().getX() + indicatorSize.get(), finishedMapChest.getRight().getY() + indicatorSize.get(), finishedMapChest.getRight().getZ() + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
        }
    }

    // Enums

    private enum State {
        SelectingResetButton,
        SelectingPerimeterCorner1,
        SelectingPerimeterCorner2,
        SelectingPerimeterCorner3,
        SelectingPerimeterCorner4,
        SelectingChests,
        SelectingFinishedMapChest,
        SelectingDumpStation,
        SelectingTable,
        SelectingAfkSpot,
        SelectingMapArea,
        AwaitRegisterResponse,
        AwaitRestockResponse,
        AwaitButtonPress,
        AwaitMapChestResponse,
        AwaitFinishedMapChestResponse,
        AwaitCartographyResponse,
        AwaitBlockBreak,
        AwaitAreaClear,
        AwaitNBTFile,
        AwaitSetup,
        AwaitMasterMap,
        AwaitMasterAllBuilt,
        AwaitVerify,
        AwaitSlaveContinue,
        AwaitSlaveNextMap,
        Afk,
        Walking,
        Dumping
    }

    private enum SprintMode {
        Off,
        NotPlacing,
        Always
    }

    private enum ErrorAction {
        Ignore,
        ToggleOff,
        Reset,
        Repair
    }
}
