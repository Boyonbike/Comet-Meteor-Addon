package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.KeyedHash;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.function.Consumer;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.mixin.MinecraftServerAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Makes it as hard as possible to work out your coordinates from what you show.
 * Texture rotations and model offsets follow NoTextureRotations by rfresh2 (Apache-2.0), see THIRD_PARTY_NOTICES.md.
 */
public final class AntiCoords extends Module {
    public enum Mode { Fixed, Keyed }

    private static final long FIXED_SEED = 42L;
    // The block hooks run for every block meshed, on worker threads, so they read this instead of looking the module up.
    private static AntiCoords instance;
    // Only meaningful on the client thread; meshing threads must never take real offsets from it.
    private static boolean entityTicking;
    private static volatile LevelKey levelKey;

    private record LevelKey(WeakReference<ClientLevel> level, KeyedHash key) {}

    private final SettingGroup sgTextures = settings.createGroup("Textures");
    private final Setting<Mode> mode = sgTextures.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Fixed gives every block the same rotation and offset. Keyed varies them like vanilla, but from a secret key instead of where the block is.")
        .defaultValue(Mode.Fixed).onChanged(value -> onTextureSettingChanged()).build());
    private final Setting<Boolean> hideRotations = sgTextures.add(new BoolSetting.Builder()
        .name("hide-rotations").description("Stops grass, stone, sand and other blocks with texture variants from showing the variant their position picks.")
        .defaultValue(true).onChanged(value -> onTextureSettingChanged()).build());
    private final Setting<Boolean> hideOffsets = sgTextures.add(new BoolSetting.Builder()
        .name("hide-offsets").description("Stops flowers, grass, bamboo and dripstone from showing the offset their position picks. Your collisions still use the real offsets.")
        .defaultValue(true).onChanged(value -> onTextureSettingChanged()).build());

    private final SettingGroup sgScreenshots = settings.createGroup("Screenshots");
    private final Setting<Boolean> cleanScreenshots = sgScreenshots.add(new BoolSetting.Builder()
        .name("clean-screenshots").description("Screenshots show only the world: no HUD, F3, chat, hand, open menu, Meteor HUD, waypoint labels or other mods' overlays.")
        .defaultValue(true).build());

    private Consumer<Component> pendingScreenshot;
    private boolean capturing;
    private boolean hudWasHidden;

    public AntiCoords() {
        super(TntCoatingAddon.CATEGORY, "anti-coords", "Makes it as hard as possible to leak your coordinates through textures and screenshots.");
        instance = this;
    }

    // onActivate and onDeactivate also run on every join and leave, so only a real toggle rebuilds.
    @Override public void toggle() {
        super.toggle();
        rebuildChunks();
    }

    private void onTextureSettingChanged() {
        if (isActive()) rebuildChunks();
    }

    private void rebuildChunks() {
        if (mc.level != null) mc.levelExtractor.allChanged();
    }

    private static Mode seedMode() {
        AntiCoords module = instance;
        return module != null && module.isActive() && module.hideRotations.get() ? module.mode.get() : null;
    }

    /** In Keyed mode, texture seeds come from the block's place within a 256-block square, so even a stolen key reveals no more. */
    public static BlockPos seedPos(BlockPos pos) {
        return seedMode() == Mode.Keyed ? new BlockPos(pos.getX() & 255, pos.getY(), pos.getZ() & 255) : pos;
    }

    public static long seed(long seed) {
        Mode mode = seedMode();
        if (mode == null) return seed;
        return mode == Mode.Fixed ? FIXED_SEED : levelKey().hash(seed);
    }

    /** Null keeps the real offset: the client's entity tick and the integrated server collide with it, as the server does. */
    public static Mode offsetMode() {
        AntiCoords module = instance;
        if (module == null || !module.isActive() || !module.hideOffsets.get()) return null;
        Minecraft mc = module.mc;
        if (entityTicking && mc.isSameThread()) return null;
        // Through Object, as MinecraftServer carries Fabric API interfaces that are not on Comet's classpath.
        Object server = mc.getSingleplayerServer();
        if (server != null && ((BlockableEventLoop<?>) server).isSameThread()) return null;
        return module.mode.get();
    }

    /** Vanilla offsets ignore height, so both halves of a tall plant still line up. */
    public static BlockPos offsetPos(BlockPos pos) {
        return BlockPos.of(levelKey().hash(BlockPos.asLong(pos.getX() & 255, 0, pos.getZ() & 255)));
    }

    public static void entityTick(boolean ticking) {
        entityTicking = ticking;
    }

    private static KeyedHash levelKey() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LevelKey cached = levelKey;
        if (cached != null && cached.level().get() == level) return cached.key();
        KeyedHash key = level == null ? MasterKey.KEY : MasterKey.KEY.derive(context(mc, level));
        levelKey = new LevelKey(new WeakReference<>(level), key);
        return key;
    }

    private static String context(Minecraft mc, ClientLevel level) {
        String dimension = level.dimension().identifier().toString();
        Object server = mc.getSingleplayerServer();
        if (server != null) return "sp\0" + ((MinecraftServerAccessor) server).meteor$getStorageSource()
            .getLevelPath(LevelResource.ROOT).normalize().getFileName() + "\0" + dimension;
        ServerData data = mc.getCurrentServer();
        return "mp\0" + (data == null ? "" : data.ip.trim().toLowerCase(Locale.ROOT)) + "\0" + dimension;
    }

    // Loaded by whichever thread first meshes a block in Keyed mode.
    private static final class MasterKey {
        static final KeyedHash KEY = KeyedHash.loadOrCreate(MeteorClient.FOLDER.toPath().resolve("comet/anti-coords.key"));
    }

    /**
     * The screenshot key's frame already has the HUD in it, so the capture waits for the next one.
     * Returns false to let vanilla take the screenshot as usual.
     */
    public static boolean deferScreenshot(Consumer<Component> callback) {
        AntiCoords module = instance;
        if (module == null || !module.isActive() || !module.cleanScreenshots.get() || module.mc.level == null) return false;
        module.pendingScreenshot = callback;
        return true;
    }

    /** Before the frame is extracted: hides the hand, block outline and name tags, as F1 does. */
    public static void beforeFrame() {
        AntiCoords module = instance;
        if (module == null || module.pendingScreenshot == null || module.capturing) return;
        module.capturing = true;
        module.hudWasHidden = module.mc.gui.hud.isHidden();
        if (!module.hudWasHidden) module.mc.gui.hud.toggle();
    }

    /** Once the world is drawn and before every 2D layer, so no HUD, overlay or menu from any mod can be in the capture. */
    public static void captureWorld() {
        AntiCoords module = instance;
        if (module == null || !module.capturing) return;
        Consumer<Component> callback = module.pendingScreenshot;
        module.pendingScreenshot = null;
        module.capturing = false;
        Minecraft mc = module.mc;
        if (mc.gui.hud.isHidden() != module.hudWasHidden) mc.gui.hud.toggle();
        Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(), callback);
    }
}
