package dev.tntcoating.modules;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.StorageEspIndex;
import dev.tntcoating.core.StorageEspIndex.CellKey;
import dev.tntcoating.render.StorageEspShader;
import dev.tntcoating.render.StorageEspView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ResourcePacksReloadedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.StorageESP;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.MeshBuilderVertexConsumerProvider;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.SimpleBlockRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.*;

/** Shader Storage ESP using Meteor's model renderer and outline pipeline, with retained GPU geometry. */
public final class FastStorageESP extends Module {
    private final StorageEspIndex<StorageEntry> index = new StorageEspIndex<>();
    private final Map<CellKey, Batch> batches = new HashMap<>();
    private final List<Map.Entry<CellKey, Batch>> visibleBatches = new ArrayList<>();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> chunkEntries = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet opened = new LongOpenHashSet();
    private final Color color = new Color();
    private final Matrix4f transform = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private boolean refreshRequested = true;
    private ClientLevel world;
    private StorageEspShader shader;
    private Build build;
    private int ticks;
    private int count;

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup performance = settings.createGroup("Performance");
    private final SettingGroup colors = settings.createGroup("Colors");
    private final SettingGroup openedRendering = settings.createGroup("Opened Rendering");

    private final Setting<List<BlockEntityType<?>>> storageBlocks = general.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks").description("Storage blocks to highlight with the shader.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS).onChanged(value -> refreshRequested = true).build());
    private final Setting<ShapeMode> shapeMode = general.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Shader outlines, fill, or both.").defaultValue(ShapeMode.Both).build());
    private final Setting<Integer> fillOpacity = general.add(new IntSetting.Builder()
        .name("fill-opacity").description("Opacity of the shader fill.").defaultValue(50).range(0, 255)
        .sliderMax(255).visible(() -> shapeMode.get() != ShapeMode.Lines).build());
    private final Setting<Integer> width = general.add(new IntSetting.Builder()
        .name("width").description("Shader outline width in pixels.").defaultValue(1).range(1, 10).sliderRange(1, 5).build());
    private final Setting<Double> glowMultiplier = general.add(new DoubleSetting.Builder()
        .name("glow-multiplier").description("Strength of the shader glow.").defaultValue(3.5).min(0).sliderMax(10).build());
    private final Setting<Double> fadeDistance = general.add(new DoubleSetting.Builder()
        .name("fade-distance").description("Fade nearby storage in the shader; zero disables fading.")
        .defaultValue(6).min(0).sliderMax(12).build());
    private final Setting<Boolean> tracers = general.add(new BoolSetting.Builder()
        .name("tracers").description("Also draw lines to storage blocks. Adds work for each container.")
        .defaultValue(false).build());
    private final Setting<Integer> maxDistance = performance.add(new IntSetting.Builder()
        .name("max-distance").description("Only highlight storage within this many blocks. Zero includes all loaded storage.")
        .defaultValue(0).min(0).sliderMax(512).onChanged(value -> refreshRequested = true).build());
    private final Setting<Double> rebuildBudget = performance.add(new DoubleSetting.Builder()
        .name("rebuild-budget-ms").description("Time per frame spent preparing changed storage models. Lower values spread loading over more frames.")
        .defaultValue(2).range(0.25, 10).sliderRange(0.25, 5).build());

    private final Setting<SettingColor> chest = colorSetting(colors, "chest", new SettingColor(255, 160, 0));
    private final Setting<SettingColor> trappedChest = colorSetting(colors, "trapped-chest", new SettingColor(255, 0, 0));
    private final Setting<SettingColor> barrel = colorSetting(colors, "barrel", new SettingColor(255, 160, 0));
    private final Setting<SettingColor> shulker = colorSetting(colors, "shulker", new SettingColor(255, 160, 0));
    private final Setting<SettingColor> enderChest = colorSetting(colors, "ender-chest", new SettingColor(120, 0, 255));
    private final Setting<SettingColor> other = colorSetting(colors, "other", new SettingColor(140, 140, 140));
    private final Setting<Boolean> hideOpened = openedRendering.add(new BoolSetting.Builder()
        .name("hide-opened").description("Hide containers you have interacted with in this world.")
        .defaultValue(false).onChanged(value -> refreshRequested = true).build());
    private final Setting<SettingColor> openedColor = colorSetting(openedRendering, "opened-color", new SettingColor(203, 90, 203, 0));

    public FastStorageESP() {
        super(TntCoatingAddon.CATEGORY, "fast-storage-esp", "Shader storage outlines with cached models and GPU buffers for large stashes.");
    }

    private Setting<SettingColor> colorSetting(SettingGroup group, String name, SettingColor initial) {
        return group.add(new ColorSetting.Builder().name(name).description("Shader color for " + name + ".")
            .defaultValue(initial).onChanged(value -> refreshRequested = true).build());
    }

    @Override public void onActivate() {
        reset();
        StorageESP original = Modules.get().get(StorageESP.class);
        if (original != null && original.isActive()) {
            original.toggle();
            info("Disabled Meteor's Storage ESP to avoid rendering storage twice.");
        }
    }

    @Override public void onDeactivate() { reset(); }
    @EventHandler private void onGameLeft(GameLeftEvent event) { reset(); }

    @EventHandler private void onReload(ResourcePacksReloadedEvent event) {
        // Reload completion can be posted from an executor. GPU/cache work belongs on the client thread.
        mc.execute(() -> { if (isActive()) reset(); });
    }

    private void reset() {
        if (build != null) build.close();
        build = null;
        batches.values().forEach(Batch::close);
        batches.clear();
        visibleBatches.clear();
        index.clear();
        chunkEntries.clear();
        opened.clear();
        if (shader != null) shader.close();
        shader = null;
        world = mc.level;
        ticks = count = 0;
        refreshRequested = true;
    }


    @EventHandler private void onTick(TickEvent.Post event) {
        if (world != mc.level) reset();
        if (mc.level == null || mc.player == null) return;
        // Five lightweight reconciliations per second, never a world/model scan in every render frame.
        if (++ticks % 4 == 0 || refreshRequested) reconcile();
    }

    private void reconcile() {
        refreshRequested = false;
        Set<BlockEntityType<?>> selected = new HashSet<>(storageBlocks.get());
        LongOpenHashSet liveChunks = new LongOpenHashSet();
        double rangeSquared = (double) maxDistance.get() * maxDistance.get();

        for (var chunk : Utils.chunks()) {
            if (!(chunk instanceof LevelChunk loaded)) continue;
            long chunkKey = loaded.getPos().pack();
            liveChunks.add(chunkKey);
            LongOpenHashSet current = new LongOpenHashSet();

            for (BlockEntity entity : loaded.getBlockEntities().values()) {
                if (entity.isRemoved() || !selected.contains(entity.getType())) continue;
                BlockPos pos = entity.getBlockPos();
                long packed = pos.asLong();
                boolean interacted = opened.contains(packed);
                if (interacted && hideOpened.get()) continue;
                if (rangeSquared > 0 && mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > rangeSquared) continue;
                SettingColor tint = interacted && openedColor.get().a > 0 ? openedColor.get() : colorFor(entity);
                if (tint.a == 0) continue;
                current.add(packed);
                index.put(pos, new StorageEntry(entity, entity.getBlockState(), tint.getPacked(), pose(entity)));
            }

            LongOpenHashSet previous = chunkEntries.put(chunkKey, current);
            if (previous != null) {
                for (long packed : previous) if (!current.contains(packed)) index.remove(BlockPos.of(packed));
            }
        }

        var iterator = chunkEntries.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (liveChunks.contains(entry.getLongKey())) continue;
            for (long packed : entry.getValue()) opened.remove(packed);
            index.removeChunk((int) entry.getLongKey(), (int) (entry.getLongKey() >> 32));
            iterator.remove();
        }
        // Opened/hidden containers are not necessarily present in the render index.
        var openedIterator = opened.iterator();
        while (openedIterator.hasNext()) {
            long pos = openedIterator.nextLong();
            if (!liveChunks.contains(ChunkPos.pack(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4))) openedIterator.remove();
        }
    }

    private SettingColor colorFor(BlockEntity entity) {
        if (entity instanceof TrappedChestBlockEntity) return trappedChest.get();
        if (entity instanceof ChestBlockEntity) return chest.get();
        if (entity instanceof BarrelBlockEntity) return barrel.get();
        if (entity instanceof ShulkerBoxBlockEntity) return shulker.get();
        if (entity instanceof EnderChestBlockEntity) return enderChest.get();
        return other.get();
    }

    private static int pose(BlockEntity entity) {
        if (entity instanceof LidBlockEntity lid) return Math.round(lid.getOpenNess(1) * 10);
        if (entity instanceof ShulkerBoxBlockEntity box) return Math.round(box.getProgress(1) * 10);
        return 0;
    }

    @EventHandler private void onInteract(InteractBlockEvent event) {
        if (mc.level == null || event.isCancelled()) return;
        BlockPos pos = event.result.getBlockPos();
        BlockEntity entity = mc.level.getBlockEntity(pos);
        if (entity == null) return;
        opened.add(pos.asLong());
        BlockState state = entity.getBlockState();
        if (entity instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE)
            && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            opened.add(pos.relative(ChestBlock.getConnectedDirection(state)).asLong());
        }
        refreshRequested = true;
    }

    @Override public WWidget getWidget(GuiTheme theme) {
        var button = theme.button("Clear Opened Containers");
        button.action = () -> { opened.clear(); refreshRequested = true; };
        return button;
    }

    @EventHandler private void onRender(Render3DEvent event) {
        count = 0;
        if (world != mc.level) reset();
        if (mc.level == null || mc.player == null) return;
        if (shader == null) shader = new StorageEspShader();
        rebuild(event);

        var camera = mc.gameRenderer.mainCamera().position();
        // Use the same view/bob/vertical-camera transform as MeshRenderer. Never use terrain occlusion:
        // storage behind walls must still contribute to the shader mask.
        StorageEspView.frustumTransform(transform, RenderUtils.projection, RenderSystem.getModelViewStack(), event.matrices.last().pose(), camera.y);
        frustum.set(transform);
        visibleBatches.clear();
        var iterator = batches.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            CellKey key = entry.getKey();
            Batch batch = entry.getValue();
            var cell = index.cell(key);
            if (cell == null || cell.size() == 0) {
                batch.close();
                iterator.remove();
                continue;
            }

            if (tracers.get()) drawTracers(event, cell.values());
            if (!StorageEspView.visible(frustum, key, camera.x, camera.z)) continue;

            visibleBatches.add(entry);
        }
        if (visibleBatches.isEmpty()) return;
        // Front-to-back batches let the isolated mask depth reject dense stash overdraw early.
        visibleBatches.sort(Comparator.comparingDouble(entry -> distanceSquared(entry.getKey(), camera.x, camera.y, camera.z)));
        shader.prepare();
        for (var entry : visibleBatches) {
            Batch batch = entry.getValue();
            StorageEspView.batchTransform(transform, event.matrices.last().pose(), batch.originX, batch.originZ, camera.x, camera.z);
            MeshRenderer.begin().attachments(shader.framebuffer).pipeline(StorageEspShader.MASK_PIPELINE)
                .mesh(batch.vertices.slice(), batch.indices.slice()).transform(transform)
                .uniform("StorageData", shader.maskUniform(batch.originX, batch.originZ,
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), fadeDistance.get())).end();
            count += batch.count;
        }
        shader.composite(width.get(), fillOpacity.get(), shapeMode.get(), glowMultiplier.get());
    }

    private static double distanceSquared(CellKey key, double x, double y, double z) {
        double dx = key.minX() + 4.0 - x;
        double dy = key.minY() + 4.0 - y;
        double dz = key.minZ() + 4.0 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void rebuild(Render3DEvent event) {
        long deadline = System.nanoTime() + (long) (rebuildBudget.get() * 1_000_000);
        int models = 0;
        while (models < 128 && System.nanoTime() < deadline) {
            if (build == null) {
                CellKey key = index.pollDirty();
                if (key == null) return;
                var cell = index.cell(key);
                if (cell == null || cell.size() == 0) {
                    Batch old = batches.remove(key);
                    if (old != null) old.close();
                    continue;
                }
                build = new Build(cell);
            }
            var cell = index.cell(build.key);
            if (cell != build.cell || cell.revision() != build.revision) {
                build.close();
                build = null;
                continue; // A new dirty entry already queues the replacement revision.
            }
            if (build.next < build.entries.size()) {
                StorageEntry entry = build.entries.get(build.next++);
                if (!entry.entity.isRemoved()) {
                    color.set(Color.toRGBAR(entry.rgba), Color.toRGBAG(entry.rgba), Color.toRGBAB(entry.rgba), Color.toRGBAA(entry.rgba));
                    build.consumer.setColor(color);
                    SimpleBlockRenderer.renderWithBlockEntity(entry.entity, event.tickDelta, build.consumer);
                }
                models++;
            }
            if (build.next == build.entries.size()) {
                build.mesh.end();
                if (build.mesh.getIndicesCount() > 0) {
                    Batch replacement = build.upload();
                    Batch old = batches.put(build.key, replacement);
                    if (old != null) old.close();
                } else {
                    Batch old = batches.remove(build.key);
                    if (old != null) old.close();
                }
                build = null;
            }
        }
    }

    private void drawTracers(Render3DEvent event, Collection<StorageEntry> entries) {
        double fadeSquared = fadeDistance.get() * fadeDistance.get();
        for (StorageEntry entry : entries) {
            BlockPos pos = entry.entity.getBlockPos();
            double alpha = fadeSquared == 0 ? 1 : Math.min(1, mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) / fadeSquared);
            if (alpha < .075) continue;
            color.set(Color.toRGBAR(entry.rgba), Color.toRGBAG(entry.rgba), Color.toRGBAB(entry.rgba), (int) (Color.toRGBAA(entry.rgba) * alpha));
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, color);
        }
    }

    @Override public String getInfoString() { return Integer.toString(count); }

    private record StorageEntry(BlockEntity entity, BlockState state, int rgba, int pose) { }

    private static final class Build implements AutoCloseable {
        final StorageEspIndex.Cell<StorageEntry> cell;
        final CellKey key;
        final long revision;
        final List<StorageEntry> entries;
        final MeshBuilder mesh = new MeshBuilder(StorageEspShader.MASK_PIPELINE);
        final MeshBuilderVertexConsumerProvider consumer = new MeshBuilderVertexConsumerProvider(mesh);
        final double originX;
        final double originZ;
        int next;

        Build(StorageEspIndex.Cell<StorageEntry> cell) {
            this.cell = cell;
            key = cell.key();
            revision = cell.revision();
            entries = new ArrayList<>(cell.values());
            var camera = meteordevelopment.meteorclient.MeteorClient.mc.gameRenderer.mainCamera().position();
            originX = camera.x;
            originZ = camera.z;
            mesh.begin();
        }

        Batch upload() {
            GpuBuffer vertices = mesh.getVertexBuffer();
            try {
                return new Batch(vertices, mesh.getIndexBuffer(), originX, originZ, entries.size());
            } catch (RuntimeException | Error e) {
                vertices.close();
                throw e;
            }
        }

        @Override public void close() { if (mesh.isBuilding()) mesh.end(); }
    }

    private record Batch(GpuBuffer vertices, GpuBuffer indices, double originX, double originZ, int count) implements AutoCloseable {
        @Override public void close() {
            vertices.close();
            indices.close();
        }
    }
}
