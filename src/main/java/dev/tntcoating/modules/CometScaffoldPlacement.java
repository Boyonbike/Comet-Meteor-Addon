package dev.tntcoating.modules;

import dev.tntcoating.core.PlacementGeometry;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Geometry and inventory selection only. Does not switch inventory, predict blocks, or send packets. */
public final class CometScaffoldPlacement {
    public record Material(int slot, InteractionHand hand, Item item, Block block) {}
    public record Hit(BlockPos target, BlockHitResult hit, Material material, float yaw, float pitch,
                      boolean container, boolean air) {}

    /** One shared, strict ray budget, including deferred verification. */
    public static final class Rays {
        private final int max;
        private int used;
        public Rays(int max) {
            if (max < 0) throw new IllegalArgumentException("Negative ray budget");
            this.max = max;
        }
        public int used() { return used; }
        public int remaining() { return max - used; }
        public boolean take() {
            if (used >= max) return false;
            used++;
            return true;
        }
    }

    // Positions are immutable keys; geometry cache eviction never changes placement trust/quarantine.
    private final Map<BlockPos, Integer> cursors = new LinkedHashMap<>(256, .75f, true);

    public Material material(List<Block> allowed, List<Block> denied, boolean fullOnly,
                             boolean autoSwitch, int preferredSlot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        ItemStack[] hotbar = new ItemStack[9];
        for (int slot = 0; slot < hotbar.length; slot++) hotbar[slot] = mc.player.getInventory().getItem(slot);
        return chooseMaterial(hotbar, mc.player.getOffhandItem(), mc.player.getInventory().getSelectedSlot(),
            allowed, denied, fullOnly, autoSwitch, preferredSlot);
    }

    static Material chooseMaterial(ItemStack[] hotbar, ItemStack offhand, int selected, List<Block> allowed,
                                   List<Block> denied, boolean fullOnly, boolean autoSwitch, int preferred) {
        if (selected >= 0 && selected < hotbar.length && acceptable(hotbar[selected], allowed, denied, fullOnly))
            return asMaterial(selected, InteractionHand.MAIN_HAND, hotbar[selected]);
        if (acceptable(offhand, allowed, denied, fullOnly)) return asMaterial(-1, InteractionHand.OFF_HAND, offhand);
        if (!autoSwitch) return null;
        if (preferred >= 0 && preferred < hotbar.length && acceptable(hotbar[preferred], allowed, denied, fullOnly))
            return asMaterial(preferred, InteractionHand.MAIN_HAND, hotbar[preferred]);
        int best = -1;
        for (int slot = 0; slot < hotbar.length; slot++) {
            if (acceptable(hotbar[slot], allowed, denied, fullOnly)
                && (best < 0 || hotbar[slot].getCount() > hotbar[best].getCount())) best = slot;
        }
        return best < 0 ? null : asMaterial(best, InteractionHand.MAIN_HAND, hotbar[best]);
    }

    private static Material asMaterial(int slot, InteractionHand hand, ItemStack stack) {
        return new Material(slot, hand, stack.getItem(), ((BlockItem) stack.getItem()).getBlock());
    }

    static boolean acceptable(ItemStack stack, List<Block> allowed, List<Block> denied, boolean fullOnly) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return false;
        Block block = item.getBlock();
        if (block instanceof FallingBlock || denied.contains(block) || (!allowed.isEmpty() && !allowed.contains(block))) return false;
        // Special BlockItems can override protected placement-state derivation (beds, double blocks,
        // scaffolding, etc.). Their multi-cell/custom rules cannot be safely replicated here.
        if (item.getClass() != BlockItem.class) return false;
        BlockState state = block.defaultBlockState();
        return !fullOnly || (!state.canBeReplaced() && state.getFluidState().isEmpty()
            && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    public static ItemStack stack(Material material) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || material == null) return ItemStack.EMPTY;
        if (material.hand() == InteractionHand.OFF_HAND && material.slot() == -1) return mc.player.getOffhandItem();
        return material.hand() == InteractionHand.MAIN_HAND && material.slot() >= 0 && material.slot() < 9
            ? mc.player.getInventory().getItem(material.slot()) : ItemStack.EMPTY;
    }

    private static boolean liveMaterial(Material material) {
        ItemStack stack = stack(material);
        return material != null && !stack.isEmpty() && stack.getItem() == material.item()
            && stack.getItem() instanceof BlockItem item && item.getBlock() == material.block()
            && item.getClass() == BlockItem.class;
    }

    public Hit resolve(BlockPos target, Material material, Rays rays, boolean airPlace,
                       boolean containerSupports, double reachMargin, Predicate<BlockPos> unconfirmed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !validPosition(target) || !liveMaterial(material) || unconfirmed.test(target)) return null;
        List<List<BlockHitResult>> byFace = new ArrayList<>(6);
        int maxSamples = 0;
        for (Direction side : Direction.values()) {
            BlockPos support = target.relative(side);
            List<BlockHitResult> samples = List.of();
            if (validPosition(support) && !unconfirmed.test(support)) {
                BlockState state = mc.level.getBlockState(support);
                if (usableSupport(support, state) && (containerSupports || !BlockUtils.isClickable(state.getBlock())))
                    samples = faceSamples(support, state.getShape(mc.level, support, CollisionContext.of(mc.player)), side.getOpposite());
            }
            byFace.add(samples);
            maxSamples = Math.max(maxSamples, samples.size());
        }
        // Interleave supports, then preserve the next sample across ticks; neither an occluded nearest
        // face nor a large outline can permanently starve an alternate side.
        List<BlockHitResult> samples = new ArrayList<>();
        for (int i = 0; i < maxSamples; i++) for (List<BlockHitResult> face : byFace)
            if (i < face.size()) samples.add(face.get(i));
        int consumed = rays.used();
        for (int visited = 0; visited < samples.size() && rays.used() - consumed < 16 && rays.remaining() > 0; visited++) {
            BlockHitResult point = samples.get(nextSample(target, samples.size()));
            float[] aim = angles(mc.player.getEyePosition(), point.getLocation());
            boolean container = BlockUtils.isClickable(mc.level.getBlockState(point.getBlockPos()).getBlock());
            Hit hit = new Hit(target.immutable(), point, material, aim[0], aim[1], container, false);
            if (validHit(hit, rays, containerSupports, reachMargin, unconfirmed)) return hit;
        }
        if (!airPlace) return null;
        // Explicit server-policy option: synthetic hit in the replaceable target. This claims no
        // support face or clear ray. Vanilla context, reach, survival and collision still apply.
        Vec3 eye = mc.player.getEyePosition();
        Vec3 point = new Vec3(Math.clamp(eye.x, target.getX() + .001, target.getX() + .999),
            Math.clamp(eye.y, target.getY() + .001, target.getY() + .999),
            Math.clamp(eye.z, target.getZ() + .001, target.getZ() + .999));
        float[] aim = angles(eye, point);
        Hit hit = new Hit(target.immutable(), new BlockHitResult(point, Direction.UP, target.immutable(), false),
            material, aim[0], aim[1], false, true);
        return validHit(hit, rays, containerSupports, reachMargin, unconfirmed) ? hit : null;
    }

    int nextSample(BlockPos target, int size) {
        if (size <= 0) throw new IllegalArgumentException("No samples");
        int index = Math.floorMod(cursors.getOrDefault(target, 0), size);
        cursors.put(target.immutable(), (index + 1) % size);
        if (cursors.size() > 256) cursors.remove(cursors.keySet().iterator().next());
        return index;
    }

    public boolean verify(Hit hit, Rays rays, boolean containerSupports, double reachMargin,
                          Predicate<BlockPos> unconfirmed) {
        return validHit(hit, rays, containerSupports, reachMargin, unconfirmed);
    }

    private boolean validHit(Hit hit, Rays rays, boolean containerSupports, double reachMargin,
                             Predicate<BlockPos> unconfirmed) {
        Minecraft mc = Minecraft.getInstance();
        if (hit == null || mc.player == null || !validPosition(hit.target()) || !liveMaterial(hit.material())
            || unconfirmed.test(hit.target())) return false;
        double reach = mc.player.blockInteractionRange() - Math.max(0, reachMargin);
        Vec3 eye = mc.player.getEyePosition();
        if (!(reach > 0) || !Double.isFinite(reach) || eye.distanceToSqr(hit.hit().getLocation()) > reach * reach) return false;
        if (!hit.air()) {
            BlockPos support = hit.hit().getBlockPos();
            if (!validPosition(support) || unconfirmed.test(support)
                || !support.relative(hit.hit().getDirection()).equals(hit.target())) return false;
            BlockState state = mc.level.getBlockState(support);
            boolean clickable = BlockUtils.isClickable(state.getBlock());
            if (!usableSupport(support, state) || (clickable && !containerSupports) || clickable != hit.container()) return false;
            if (!rays.take()) return false;
            Direction face = hit.hit().getDirection();
            Vec3 inside = hit.hit().getLocation().add(-face.getStepX() * .001, -face.getStepY() * .001, -face.getStepZ() * .001);
            BlockHitResult ray = mc.level.clip(new ClipContext(eye, inside, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(support)
                || ray.getDirection() != face || ray.getLocation().distanceToSqr(hit.hit().getLocation()) > .0004) return false;
        } else if (!hit.hit().getBlockPos().equals(hit.target()) || hit.container()) return false;
        return placementContext(hit);
    }

    private boolean placementContext(Hit hit) {
        Minecraft mc = Minecraft.getInstance();
        BlockItem item = (BlockItem) hit.material().item();
        if (!item.isEnabled(mc.level.enabledFeatures())) return false;
        // The bridge verifies the sent orientation against this Hit before dispatch. Its visual
        // restoration must not change directional state, contextual destination or collision checks.
        BlockPlaceContext base = new AimedContext(hit, stack(hit.material()));
        BlockPlaceContext context = item.updatePlacementContext(base);
        if (context == null || !context.getClickedPos().equals(hit.target()) || !context.canPlace()) return false;
        BlockState placed = hit.material().block().getStateForPlacement(context);
        if (placed == null || !placed.canSurvive(mc.level, hit.target())) return false;
        // Vanilla applies BLOCK_STATE components just after placement. Validate that final shape too.
        placed = withStackState(placed, stack(hit.material()));
        if (!placed.canSurvive(mc.level, hit.target())) return false;
        CollisionContext collision = CollisionContext.placementContext(mc.player);
        VoxelShape shape = placed.getCollisionShape(mc.level, hit.target(), collision);
        for (AABB box : shape.toAabbs())
            if (box.move(hit.target().getX(), hit.target().getY(), hit.target().getZ()).intersects(mc.player.getBoundingBox())) return false;
        return mc.level.isUnobstructed(placed, hit.target(), collision);
    }

    static BlockState withStackState(BlockState placed, ItemStack stack) {
        var properties = stack.get(DataComponents.BLOCK_STATE);
        return properties == null ? placed : properties.apply(placed);
    }

    static Direction placementHorizontal(Hit hit) {
        return Direction.fromYRot(hit.yaw());
    }

    /** Search and dispatch derive placement from hit aim, independently of the restored visual pose. */
    private static final class AimedContext extends BlockPlaceContext {
        private final Hit intended;
        private final Direction[] directions;
        AimedContext(Hit hit, ItemStack stack) {
            super(Minecraft.getInstance().player, hit.material().hand(), stack, hit.hit());
            intended = hit;
            Vec3 look = Vec3.directionFromRotation(hit.pitch(), hit.yaw());
            directions = Direction.values().clone();
            java.util.Arrays.sort(directions, Comparator.comparingDouble(direction ->
                -(direction.getStepX() * look.x + direction.getStepY() * look.y + direction.getStepZ() * look.z)));
        }
        @Override public boolean isSecondaryUseActive() { return intended.container() || super.isSecondaryUseActive(); }
        @Override public float getRotation() { return intended.yaw(); }
        @Override public Direction getHorizontalDirection() { return placementHorizontal(intended); }
        @Override public Direction getNearestLookingDirection() { return directions[0]; }
        @Override public Direction getNearestLookingVerticalDirection() {
            for (Direction direction : directions) if (direction.getAxis() == Direction.Axis.Y) return direction;
            return Direction.DOWN;
        }
        @Override public Direction[] getNearestLookingDirections() {
            Direction[] result = directions.clone();
            // Match BlockPlaceContext: when placing next to the support, prefer the direction
            // into the clicked support for directional blocks such as stairs.
            if (!replacingClickedOnBlock()) {
                Direction first = getClickedFace().getOpposite();
                int i = 0; while (result[i] != first) i++;
                System.arraycopy(result, 0, result, 1, i);
                result[0] = first;
            }
            return result;
        }
    }

    public static boolean usableSupport(BlockPos position, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || state == null || !validPosition(position) || state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        VoxelShape collision = state.getCollisionShape(mc.level, position, CollisionContext.of(mc.player));
        for (AABB box : collision.toAabbs()) if (box.maxX - box.minX > 1e-6 && box.maxZ - box.minZ > 1e-6
            && box.maxY - box.minY > 1e-6) return true;
        return false;
    }

    private static boolean validPosition(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        return pos != null && mc.level != null && !mc.level.isOutsideBuildHeight(pos)
            && mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && mc.level.getWorldBorder().isWithinBounds(pos);
    }

    public static boolean supportBelow(AABB playerBox, Predicate<BlockPos> unconfirmed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        double step = Math.min(.6, Math.max(0, mc.player.maxUpStep()));
        List<AABB> collision = new ArrayList<>();
        List<AABB> supports = new ArrayList<>();
        // Include body/head room and protruding shapes from adjacent cells (fences/walls).
        for (int x = Mth.floor(playerBox.minX) - 1; x <= Mth.floor(playerBox.maxX) + 1; x++)
            for (int z = Mth.floor(playerBox.minZ) - 1; z <= Mth.floor(playerBox.maxZ) + 1; z++)
                for (int y = Mth.floor(playerBox.minY) - 2; y <= Mth.floor(playerBox.maxY + step); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!validPosition(pos)) return false;
                    BlockState state = mc.level.getBlockState(pos);
                    boolean credible = !unconfirmed.test(pos) && !state.canBeReplaced() && state.getFluidState().isEmpty();
                    for (AABB box : state.getCollisionShape(mc.level, pos, CollisionContext.of(mc.player)).toAabbs()) {
                        AABB worldBox = box.move(x, y, z);
                        collision.add(worldBox);
                        if (credible) supports.add(worldBox);
                    }
                }
        return !Double.isNaN(supportHeight(playerBox, supports, collision, step));
    }

    static double supportHeight(AABB body, List<AABB> collision, double maxStep) {
        return supportHeight(body, collision, collision, maxStep);
    }

    static double supportHeight(AABB body, List<AABB> supports, List<AABB> collision, double maxStep) {
        double best = Double.NaN;
        for (AABB box : supports) {
            double top = box.maxY;
            if (box.maxX <= body.minX + 1e-7 || box.minX >= body.maxX - 1e-7
                || box.maxZ <= body.minZ + 1e-7 || box.minZ >= body.maxZ - 1e-7
                || top < body.minY - .08 || top > body.minY + maxStep + 1e-7) continue;
            AABB raised = body.move(0, Math.max(0, top - body.minY), 0).deflate(1e-7);
            boolean blocked = false;
            for (AABB obstacle : collision) if (raised.intersects(obstacle)) { blocked = true; break; }
            if (!blocked && (Double.isNaN(best) || top < best)) best = top;
        }
        return best;
    }

    static List<BlockHitResult> faceSamples(BlockPos pos, VoxelShape outline, Direction face) {
        List<BlockHitResult> samples = new ArrayList<>();
        int axis = face.getAxis() == Direction.Axis.X ? 0 : face.getAxis() == Direction.Axis.Y ? 1 : 2;
        for (AABB local : outline.toAabbs()) {
            AABB box = local.move(pos.getX(), pos.getY(), pos.getZ());
            var bounds = new PlacementGeometry.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            // Fixed points preserve sample-index fairness as the eye moves between ticks.
            var centre = new PlacementGeometry.Point((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2);
            for (var sample : PlacementGeometry.faceSamples(bounds, axis, face.getAxisDirection().getStep(), centre, true))
                samples.add(new BlockHitResult(new Vec3(sample.x(), sample.y(), sample.z()), face, pos.immutable(), false));
        }
        return samples;
    }

    static float[] angles(Vec3 eye, Vec3 hit) {
        Vec3 delta = hit.subtract(eye);
        return new float[] {(float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90),
            (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)))};
    }

    /** Clears geometry fairness state only; lifecycle/quarantine is owned by the module runtime. */
    public void reset() { cursors.clear(); }
}
