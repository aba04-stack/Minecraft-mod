package com.abs.livingvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * A village guard role layered over a vanilla villager. Guards can equip weapons and armor
 * from community storage, walk toward nearby hostile mobs, attack only at close range and
 * retreat toward the village when badly hurt. The villager keeps its vanilla sleep and social AI.
 */
public final class VillageDefense {
    private static final String P = "livingvillages_defense_";
    private static final String NEXT_EQUIP = P + "next_equip";
    private static final String NEXT_ATTACK = P + "next_attack";
    private static final String NEXT_PATROL = P + "next_patrol";
    private static final String LAST_ROUTE_ORDER = P + "last_route_order";
    private static final String HAS_ROUTE = P + "has_route";
    private static final String ROUTE_X = P + "route_x", ROUTE_Y = P + "route_y", ROUTE_Z = P + "route_z";
    private static final String ROUTE_KIND = P + "route_kind";
    private static final String ROUTE_THREAT_UUID = P + "threat_uuid";
    private static final int TICK_STEP = 5;
    private static final int EQUIP_INTERVAL_TICKS = 200;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private static final int PATROL_INTERVAL_TICKS = 160;
    private static final float WALK_SPEED = 0.82F;
    private VillageDefense() {
    }

    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager guard) || guard.level().isClientSide()
            || !guard.isAlive() || guard.isBaby() || guard.tickCount % TICK_STEP != 0) {
            return;
        }
        CompoundTag data = guard.getPersistentData();
        if (!VillagerRoles.isGuard(guard)) {
            if (data.getBoolean(HAS_ROUTE)) clearOwnedRoute(guard, data);
            return;
        }
        if (!LivingVillagesConfig.ENABLE_AUTONOMOUS_DEVELOPMENT.get()
            || !LivingVillagesConfig.ENABLE_VILLAGE_GUARDS.get()) {
            if (data.getBoolean(HAS_ROUTE)) clearOwnedRoute(guard, data);
            return;
        }
        if (!(guard.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();

        if (now >= data.getLong(NEXT_EQUIP)) {
            equipFromNearbyStorage(guard, level);
            data.putLong(NEXT_EQUIP, now + EQUIP_INTERVAL_TICKS);
        }

        int radius = LivingVillagesConfig.VILLAGE_DEFENSE_RADIUS_BLOCKS.get();
        BlockPos home = getOrCreateHome(guard, level, radius);
        Mob threat = findThreat(guard, level, home, radius);
        if (threat != null) {
            data.putLong(NEXT_PATROL, now + PATROL_INTERVAL_TICKS);
            defendAgainst(guard, threat, level, data, now);
            return;
        }

        // No current threat. Don't take over another vanilla route; patrol only when idle.
        if (data.getBoolean(HAS_ROUTE) && !data.getString(ROUTE_KIND).equals("patrol")) {
            clearOwnedRoute(guard, data);
        }
        if (now >= data.getLong(NEXT_PATROL)
            && !guard.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            BlockPos patrolTarget = choosePatrolPoint(guard, level, radius);
            if (patrolTarget != null) {
                setOwnedRoute(guard, data, patrolTarget, "patrol", null, now);
            }
        }
    }

    private static Mob findThreat(Villager guard, ServerLevel level, BlockPos home, int radius) {
        AABB area = new AABB(home).inflate(radius, 10.0D, radius);
        return level.getEntitiesOfClass(Mob.class, area, mob -> mob.isAlive() && mob instanceof Enemy
                && !mob.isRemoved())
            .stream()
            .filter(mob -> isInsideHorizontalRadius(home, mob.blockPosition(), radius))
            .filter(mob -> guard.distanceToSqr(mob) <= (double) radius * radius * 2.0D)
            .min(Comparator.comparingDouble(guard::distanceToSqr))
            .orElse(null);
    }

    private static boolean isInsideHorizontalRadius(BlockPos center, BlockPos pos, int radius) {
        int dx = center.getX() - pos.getX();
        int dz = center.getZ() - pos.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private static void defendAgainst(Villager guard, Mob threat, ServerLevel level, CompoundTag data, long now) {
        // If critically wounded, fall back toward the nearby group instead of charging to death.
        if (guard.getHealth() <= guard.getMaxHealth() * 0.35F) {
            BlockPos retreat = getOrCreateHome(guard, level, LivingVillagesConfig.VILLAGE_DEFENSE_RADIUS_BLOCKS.get());
            boolean alreadyRetreating = data.getBoolean(HAS_ROUTE) && data.getString(ROUTE_KIND).equals("retreat");
            boolean routeMissing = !guard.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET);
            if (retreat != null && guard.blockPosition().distSqr(retreat) > 9.0D
                && (!alreadyRetreating || routeMissing || now - data.getLong(LAST_ROUTE_ORDER) >= 40L)) {
                setOwnedRoute(guard, data, retreat, "retreat", threat, now);
            } else if (retreat != null && guard.blockPosition().distSqr(retreat) <= 9.0D
                && data.getBoolean(HAS_ROUTE) && !alreadyRetreating) {
                clearOwnedRoute(guard, data);
            }
            return;
        }

        double distanceSqr = guard.distanceToSqr(threat);
        if (distanceSqr <= 3.4D * 3.4D && guard.getSensing().hasLineOfSight(threat)) {
            if (now >= data.getLong(NEXT_ATTACK)) {
                clearOwnedRoute(guard, data);
                guard.getLookControl().setLookAt(threat, 30.0F, 30.0F);
                guard.swing(InteractionHand.MAIN_HAND);
                float damage = attackDamage(guard.getItemBySlot(EquipmentSlot.MAINHAND));
                if (threat.hurt(level.damageSources().mobAttack(guard), damage)) {
                    ItemStack weapon = guard.getItemBySlot(EquipmentSlot.MAINHAND);
                    if (!weapon.isEmpty() && isSword(weapon)) {
                        weapon.hurtAndBreak(1, guard, EquipmentSlot.MAINHAND);
                    }
                    threat.knockback(0.25D, guard.getX() - threat.getX(), guard.getZ() - threat.getZ());
                    level.sendParticles(ParticleTypes.CRIT, threat.getX(), threat.getY() + 0.6D,
                        threat.getZ(), 3, 0.15D, 0.2D, 0.15D, 0.02D);
                }
                data.putLong(NEXT_ATTACK, now + ATTACK_COOLDOWN_TICKS);
            }
            return;
        }

        BlockPos targetPos = threat.blockPosition();
        boolean sameTarget = data.getString(ROUTE_KIND).equals("threat")
            && threat.getUUID().toString().equals(data.getString(ROUTE_THREAT_UUID));
        boolean routePresent = guard.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET);
        if (!sameTarget || !routePresent || now - data.getLong(LAST_ROUTE_ORDER) >= 40L) {
            // Re-issue occasionally because hostile mobs move; this does not teleport the guard.
            setOwnedRoute(guard, data, targetPos, "threat", threat, now);
        }
    }

    private static void equipFromNearbyStorage(Villager guard, ServerLevel level) {
        // Gear comes from real items placed in local chests/barrels; nothing is conjured.
        equipSlot(guard, level, EquipmentSlot.MAINHAND, VillageDefense::isSword);
        equipSlot(guard, level, EquipmentSlot.HEAD, VillageDefense::isHelmet);
        equipSlot(guard, level, EquipmentSlot.CHEST, VillageDefense::isChestplate);
        equipSlot(guard, level, EquipmentSlot.LEGS, VillageDefense::isLeggings);
        equipSlot(guard, level, EquipmentSlot.FEET, VillageDefense::isBoots);
        equipSlot(guard, level, EquipmentSlot.OFFHAND, stack -> stack.is(Items.SHIELD));
    }

    private static void equipSlot(Villager guard, ServerLevel level, EquipmentSlot slot, Predicate<ItemStack> predicate) {
        ItemStack equipped = guard.getItemBySlot(slot);
        if (predicate.test(equipped)) return;
        if (!equipped.isEmpty()) {
            ItemStack remainder = guard.getInventory().addItem(equipped.copy());
            if (!remainder.isEmpty()) return; // Do not overwrite an item if it cannot be stowed.
            guard.setItemSlot(slot, ItemStack.EMPTY);
        }

        var inventory = guard.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack candidate = inventory.getItem(i);
            if (!predicate.test(candidate)) continue;
            ItemStack take = candidate.split(1);
            if (candidate.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
            inventory.setChanged();
            guard.setItemSlot(slot, take);
            return;
        }

        ItemStack fromStorage = takeMatchingFromStorage(level, guard.blockPosition(), predicate);
        if (!fromStorage.isEmpty()) guard.setItemSlot(slot, fromStorage);
    }

    private static ItemStack takeMatchingFromStorage(ServerLevel level, BlockPos origin, Predicate<ItemStack> predicate) {
        int radius = Math.min(10, LivingVillagesConfig.RESOURCE_CHEST_RADIUS_BLOCKS.get());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isStorage(level.getBlockState(pos))) continue;
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (!predicate.test(stack)) continue;
                        ItemStack take = stack.split(1);
                        if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                        container.setChanged();
                        return take;
                    }
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private static boolean isSword(ItemStack stack) {
        return stack.is(Items.WOODEN_SWORD) || stack.is(Items.STONE_SWORD) || stack.is(Items.IRON_SWORD)
            || stack.is(Items.GOLDEN_SWORD) || stack.is(Items.DIAMOND_SWORD) || stack.is(Items.NETHERITE_SWORD);
    }

    private static boolean isHelmet(ItemStack s) {
        return s.is(Items.LEATHER_HELMET) || s.is(Items.CHAINMAIL_HELMET) || s.is(Items.IRON_HELMET)
            || s.is(Items.GOLDEN_HELMET) || s.is(Items.DIAMOND_HELMET) || s.is(Items.NETHERITE_HELMET) || s.is(Items.TURTLE_HELMET);
    }

    private static boolean isChestplate(ItemStack s) {
        return s.is(Items.LEATHER_CHESTPLATE) || s.is(Items.CHAINMAIL_CHESTPLATE) || s.is(Items.IRON_CHESTPLATE)
            || s.is(Items.GOLDEN_CHESTPLATE) || s.is(Items.DIAMOND_CHESTPLATE) || s.is(Items.NETHERITE_CHESTPLATE);
    }

    private static boolean isLeggings(ItemStack s) {
        return s.is(Items.LEATHER_LEGGINGS) || s.is(Items.CHAINMAIL_LEGGINGS) || s.is(Items.IRON_LEGGINGS)
            || s.is(Items.GOLDEN_LEGGINGS) || s.is(Items.DIAMOND_LEGGINGS) || s.is(Items.NETHERITE_LEGGINGS);
    }

    private static boolean isBoots(ItemStack s) {
        return s.is(Items.LEATHER_BOOTS) || s.is(Items.CHAINMAIL_BOOTS) || s.is(Items.IRON_BOOTS)
            || s.is(Items.GOLDEN_BOOTS) || s.is(Items.DIAMOND_BOOTS) || s.is(Items.NETHERITE_BOOTS);
    }

    private static float attackDamage(ItemStack weapon) {
        if (weapon.is(Items.NETHERITE_SWORD)) return 8.0F;
        if (weapon.is(Items.DIAMOND_SWORD)) return 7.0F;
        if (weapon.is(Items.IRON_SWORD)) return 6.0F;
        if (weapon.is(Items.STONE_SWORD)) return 5.0F;
        if (isSword(weapon)) return 4.0F;
        return 2.0F; // A guard can still defend itself unarmed, though a sword is much better.
    }

    private static BlockPos choosePatrolPoint(Villager guard, ServerLevel level, int radius) {
        BlockPos center = getOrCreateHome(guard, level, radius);
        if (center == null) return null;
        int phase = (int) Math.floorMod(level.getGameTime() / PATROL_INTERVAL_TICKS + guard.getUUID().hashCode(), 8L);
        int[][] offsets = {{4, 0}, {3, 3}, {0, 4}, {-3, 3}, {-4, 0}, {-3, -3}, {0, -4}, {3, -3}};
        int x = center.getX() + offsets[phase][0];
        int z = center.getZ() + offsets[phase][1];
        // Patrols must not query heightmaps in an unloaded chunk.
        if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) {
            return isSafeStand(level, center) ? center : null;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos target = new BlockPos(x, y, z);
        if (isSafeStand(level, target)) return target;
        return isSafeStand(level, center) ? center : null;
    }

    private static BlockPos getOrCreateHome(Villager guard, ServerLevel level, int radius) {
        BlockPos home = VillagerRoles.getHome(guard);
        if (home != null) return home;

        AABB area = new AABB(guard.blockPosition()).inflate(radius, 8.0D, radius);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, area, v -> v.isAlive() && !v.isBaby());
        double x = 0.0D, y = 0.0D, z = 0.0D;
        for (Villager villager : villagers) {
            x += villager.getX(); y += villager.getY(); z += villager.getZ();
        }
        BlockPos calculated = villagers.isEmpty() ? guard.blockPosition()
            : BlockPos.containing(x / villagers.size(), y / villagers.size(), z / villagers.size());
        var data = guard.getPersistentData();
        data.putInt(VillagerRoles.HOME_X, calculated.getX());
        data.putInt(VillagerRoles.HOME_Y, calculated.getY());
        data.putInt(VillagerRoles.HOME_Z, calculated.getZ());
        return calculated;
    }

    private static boolean isSafeStand(ServerLevel level, BlockPos feet) {
        return level.hasChunkAt(feet) && level.hasChunkAt(feet.above()) && level.hasChunkAt(feet.below())
            && level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
            && level.getBlockState(feet.below()).blocksMotion();
    }

    private static void setOwnedRoute(Villager guard, CompoundTag data, BlockPos target,
                                      String kind, Mob threat, long now) {
        guard.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, WALK_SPEED, 1));
        data.putBoolean(HAS_ROUTE, true);
        data.putInt(ROUTE_X, target.getX()); data.putInt(ROUTE_Y, target.getY()); data.putInt(ROUTE_Z, target.getZ());
        data.putString(ROUTE_KIND, kind);
        data.putString(ROUTE_THREAT_UUID, threat == null ? "" : threat.getUUID().toString());
        data.putLong(LAST_ROUTE_ORDER, now);
        data.putLong(NEXT_PATROL, now + PATROL_INTERVAL_TICKS);
    }

    private static void clearOwnedRoute(Villager guard, CompoundTag data) {
        if (data.getBoolean(HAS_ROUTE)) {
            BlockPos route = new BlockPos(data.getInt(ROUTE_X), data.getInt(ROUTE_Y), data.getInt(ROUTE_Z));
            guard.getBrain().getMemory(MemoryModuleType.WALK_TARGET).ifPresent(walk -> {
                if (walk.getTarget().currentBlockPosition().equals(route)) {
                    guard.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            });
        }
        data.putBoolean(HAS_ROUTE, false);
        data.remove(ROUTE_X); data.remove(ROUTE_Y); data.remove(ROUTE_Z);
        data.remove(ROUTE_KIND); data.remove(ROUTE_THREAT_UUID); data.remove(LAST_ROUTE_ORDER);
    }
}
