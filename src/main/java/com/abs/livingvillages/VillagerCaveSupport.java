package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight support for miners exploring natural caves.
 *
 * <p>The villager stores a short, bounded trail in its own persistent data, so the route survives
 * chunk saves and server restarts. It places torches only from real carried/stored items and only
 * on valid, supported positions. At night or when badly hurt it follows the remembered trail
 * backwards and then heads to its HOME anchor. It never loads chunks to inspect or route through.</p>
 */
public final class VillagerCaveSupport {
    private static final String P = "livingvillages_cave_";
    private static final String TRAIL = P + "trail";
    private static final String LAST_WAYPOINT = P + "last_waypoint";
    private static final String LAST_TORCH = P + "last_torch";
    private static final String LAST_TORCH_TICK = P + "last_torch_tick";
    private static final String NEXT_TORCH_RESTOCK = P + "next_torch_restock";
    private static final String RETURNING = P + "returning";
    private static final String RETURN_INDEX = P + "return_index";
    private static final String RETURN_TARGET = P + "return_target";
    private static final String RETURN_LAST_POS = P + "return_last_pos";
    private static final String RETURN_LAST_PROGRESS = P + "return_last_progress";
    private static final String RETURN_LAST_ORDER = P + "return_last_order";

    private static final int TICK_STEP = 10;
    private static final int MAX_WAYPOINTS = 48;
    private static final int WAYPOINT_SPACING_SQUARED = 36;
    private static final int TORCH_SPACING_SQUARED = 49;
    private static final long TORCH_COOLDOWN_TICKS = 80L;
    private static final long ROUTE_REISSUE_TICKS = 40L;
    private static final long WAYPOINT_STALL_TICKS = 400L;
    private static final double ARRIVAL_DISTANCE_SQUARED = 6.25D;
    private static final float WALK_SPEED = 0.62F;

    private VillagerCaveSupport() {
    }

    public static boolean isReturning(Villager villager) {
        return villager != null && villager.getPersistentData().getBoolean(RETURNING);
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()
            || !villager.isAlive() || villager.isBaby() || villager.tickCount % TICK_STEP != 0
            || !(villager.level() instanceof ServerLevel level)) return;

        CompoundTag data = villager.getPersistentData();
        long now = level.getGameTime();

        // A return trip already started is safety-critical and continues even if the role/config changes.
        if (data.getBoolean(RETURNING)) {
            continueReturnHome(villager, level, data, now);
            return;
        }
        if (!VillagerRoles.isMiner(villager)
            || !LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_MINER_ROLE.get()
            || !LivingVillagesConfig.ENABLE_MINER_CAVE_EXPLORATION.get()) return;

        recordCaveWaypoint(villager, level, data);
        restockTorches(villager, level, data, now);
        placeTorchAtDarkSpot(villager, level, data, now);

        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        boolean lowHealth = villager.getHealth() <= villager.getMaxHealth() * 0.35F;
        if ((dayTime >= 11400L || lowHealth) && data.getLongArray(TRAIL).length > 0) {
            beginReturnHome(villager, data, now);
            continueReturnHome(villager, level, data, now);
        }
    }

    private static void recordCaveWaypoint(Villager villager, ServerLevel level, CompoundTag data) {
        BlockPos feet = villager.blockPosition();
        if (!level.hasChunkAt(feet)) return;
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        // Only remember underground movement. Surface work and ordinary village paths do not fill the trail.
        if (feet.getY() > surfaceY - 5) return;

        long[] old = data.getLongArray(TRAIL);
        if (old.length > 0 && BlockPos.of(old[old.length - 1]).distSqr(feet) < WAYPOINT_SPACING_SQUARED) return;

        int keep = Math.min(old.length, MAX_WAYPOINTS - 1);
        long[] next = new long[keep + 1];
        if (keep > 0) System.arraycopy(old, old.length - keep, next, 0, keep);
        next[keep] = feet.asLong();
        data.putLongArray(TRAIL, next);
        data.putLong(LAST_WAYPOINT, level.getGameTime());
    }

    private static void restockTorches(Villager villager, ServerLevel level, CompoundTag data, long now) {
        if (now < data.getLong(NEXT_TORCH_RESTOCK)) return;
        // The storage scan is deliberately much slower than the miner's work tick.
        data.putLong(NEXT_TORCH_RESTOCK, now + 400L);
        SimpleContainer inventory = villager.getInventory();
        if (countTorches(villager) >= 8) return;

        BlockPos center = VillagerRoles.getHome(villager);
        if (center == null) center = villager.blockPosition();
        int radius = Math.min(12, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos storagePos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(storagePos) || !isStorage(level.getBlockState(storagePos))
                        || !VillageRegistry.isStorageForVillage(level, center, storagePos)
                        || !VillageReservations.isStorageAvailable(level, storagePos, villager)
                        || !(level.getBlockEntity(storagePos) instanceof Container storage)) continue;

                    for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                        ItemStack stored = storage.getItem(slot);
                        if (!stored.is(Items.TORCH) || stored.getCount() <= 8) continue;
                        int requested = Math.min(16 - countTorches(villager), stored.getCount() - 8);
                        if (requested <= 0) return;
                        ItemStack offered = new ItemStack(Items.TORCH, requested);
                        ItemStack remainder = inventory.addItem(offered);
                        int moved = requested - remainder.getCount();
                        if (moved > 0) {
                            stored.shrink(moved);
                            if (stored.isEmpty()) storage.setItem(slot, ItemStack.EMPTY);
                            storage.setChanged();
                            inventory.setChanged();
                        }
                        if (countTorches(villager) >= 8) return;
                    }
                }
            }
        }
    }

    private static int countTorches(Villager villager) {
        int count = 0;
        Container inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.TORCH)) count += stack.getCount();
        }
        ItemStack offhand = villager.getOffhandItem();
        if (offhand.is(Items.TORCH)) count += offhand.getCount();
        return count;
    }

    private static void placeTorchAtDarkSpot(Villager villager, ServerLevel level, CompoundTag data, long now) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            || now - data.getLong(LAST_TORCH_TICK) < TORCH_COOLDOWN_TICKS) return;
        if (countTorches(villager) <= 0) return;

        BlockPos origin = villager.blockPosition();
        List<BlockPos> candidates = new ArrayList<>(9);
        candidates.add(origin);
        candidates.add(origin.north());
        candidates.add(origin.east());
        candidates.add(origin.south());
        candidates.add(origin.west());
        candidates.add(origin.north().east());
        candidates.add(origin.south().east());
        candidates.add(origin.south().west());
        candidates.add(origin.north().west());

        BlockPos previousTorch = data.contains(LAST_TORCH)
            ? BlockPos.of(data.getLong(LAST_TORCH)) : null;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            if (!level.hasChunkAt(candidate) || !level.hasChunkAt(candidate.below())) continue;
            if (level.getMaxLocalRawBrightness(candidate) >= 8) continue;
            if (previousTorch != null && previousTorch.distSqr(candidate) < TORCH_SPACING_SQUARED) continue;
            BlockState existing = level.getBlockState(candidate);
            if (!existing.isAir() && !VillageBuilder.isAirOrReplaceableVegetation(existing)) continue;
            if (!level.getFluidState(candidate).isEmpty() || !level.getBlockState(candidate.below()).blocksMotion()) continue;
            if (!Blocks.TORCH.defaultBlockState().canSurvive(level, candidate)) continue;
            double distance = villager.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.immutable();
            }
        }
        if (best == null || !consumeTorch(villager)) return;
        if (!level.setBlock(best, Blocks.TORCH.defaultBlockState(), 3)) {
            villager.getInventory().addItem(new ItemStack(Items.TORCH));
            return;
        }
        data.putLong(LAST_TORCH, best.asLong());
        data.putLong(LAST_TORCH_TICK, now);
        VillageTickBudget.recordActivitySignal(level, best);
    }

    private static boolean consumeTorch(Villager villager) {
        ItemStack offhand = villager.getOffhandItem();
        if (offhand.is(Items.TORCH)) {
            offhand.shrink(1);
            if (offhand.isEmpty()) villager.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            return true;
        }
        Container inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(Items.TORCH)) continue;
            stack.shrink(1);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            inventory.setChanged();
            return true;
        }
        return false;
    }

    static int nearestTrailIndex(BlockPos current, long[] trail) {
        if (current == null || trail == null || trail.length == 0) return -1;
        int nearestIndex = -1;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < trail.length; i++) {
            double distance = current.distSqr(BlockPos.of(trail[i]));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private static void beginReturnHome(Villager villager, CompoundTag data, long now) {
        long[] trail = data.getLongArray(TRAIL);
        if (trail.length == 0) return;
        VillagerMining.suspendForCaveReturn(villager);
        data.putBoolean(RETURNING, true);
        data.putInt(RETURN_INDEX, nearestTrailIndex(villager.blockPosition(), trail));
        data.putLong(RETURN_LAST_PROGRESS, now);
        data.putLong(RETURN_LAST_ORDER, 0L);
        data.putLong(RETURN_LAST_POS, villager.blockPosition().asLong());
        data.remove(RETURN_TARGET);
    }

    private static void continueReturnHome(Villager villager, ServerLevel level, CompoundTag data, long now) {
        long[] trail = data.getLongArray(TRAIL);
        int index = Math.min(data.getInt(RETURN_INDEX), trail.length - 1);
        BlockPos target = null;
        while (index >= 0) {
            BlockPos candidate = BlockPos.of(trail[index]);
            if (level.hasChunkAt(candidate) && villager.blockPosition().distSqr(candidate) > ARRIVAL_DISTANCE_SQUARED) {
                target = candidate.immutable();
                break;
            }
            index--;
        }
        data.putInt(RETURN_INDEX, index);

        boolean headingHome = index < 0;
        if (headingHome) {
            BlockPos home = VillagerRoles.getHome(villager);
            if (home == null || !level.hasChunkAt(home)) {
                finishReturn(villager, data);
                return;
            }
            if (villager.blockPosition().distSqr(home) <= ARRIVAL_DISTANCE_SQUARED) {
                finishReturn(villager, data);
                return;
            }
            target = home.immutable();
        }

        long lastPosition = data.getLong(RETURN_LAST_POS);
        if (villager.blockPosition().distSqr(BlockPos.of(lastPosition)) >= 1.0D) {
            data.putLong(RETURN_LAST_POS, villager.blockPosition().asLong());
            data.putLong(RETURN_LAST_PROGRESS, now);
        } else if (now - data.getLong(RETURN_LAST_PROGRESS) >= WAYPOINT_STALL_TICKS) {
            // Skip one stale/blocked waypoint rather than retrying forever. The final HOME leg
            // is bounded too, so a missing or inaccessible bed cannot trap a worker indefinitely.
            data.putInt(RETURN_INDEX, index - 1);
            data.putLong(RETURN_LAST_PROGRESS, now);
            data.putLong(RETURN_LAST_ORDER, 0L);
            data.remove(RETURN_TARGET);
            if (headingHome) {
                finishReturn(villager, data);
                return;
            }
            return;
        }

        Optional<WalkTarget> current = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        boolean sameTarget = current.map(walk -> walk.getTarget().currentBlockPosition().equals(target)).orElse(false);
        boolean retry = now - data.getLong(RETURN_LAST_ORDER) >= ROUTE_REISSUE_TICKS;
        if (!sameTarget && (current.isEmpty() || retry) || sameTarget && retry) {
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
            data.putLong(RETURN_TARGET, target.asLong());
            data.putLong(RETURN_LAST_ORDER, now);
        }
    }

    private static void finishReturn(Villager villager, CompoundTag data) {
        if (data.contains(RETURN_TARGET)) {
            BlockPos owned = BlockPos.of(data.getLong(RETURN_TARGET));
            villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(owned)) {
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        data.putBoolean(RETURNING, false);
        data.remove(TRAIL);
        data.remove(LAST_WAYPOINT);
        data.remove(RETURN_INDEX);
        data.remove(RETURN_TARGET);
        data.remove(RETURN_LAST_POS);
        data.remove(RETURN_LAST_PROGRESS);
        data.remove(RETURN_LAST_ORDER);
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }
}
