package com.desertcore.realdesertcore;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class Realdesertcore extends JavaPlugin implements Listener {

    public record BlockKey(String worldName, int x, int y, int z) {
        public static BlockKey from(Location loc) {
            return new BlockKey(Objects.requireNonNull(loc.getWorld()).getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
        public boolean isInChunk(int chunkX, int chunkZ) {
            return (this.x >> 4) == chunkX && (this.z >> 4) == chunkZ;
        }
        public Location toLocation() {
            World w = Bukkit.getWorld(worldName);
            return w != null ? new Location(w, x + 0.5, y, z + 0.5) : null;
        }
    }

    public static class BarrierData {
        private final BlockDisplay display;
        private int hp;
        private boolean isStone;
        public BarrierData(BlockDisplay display, int hp, boolean isStone) {
            this.display = display;
            this.hp = hp;
            this.isStone = isStone;
        }
        public BlockDisplay getDisplay() { return display; }
        public int getHp() { return hp; }
        public void setHp(int hp) { this.hp = hp; }
        public boolean isStone() { return isStone; }
        public void setStone(boolean stone) { this.isStone = stone; }
        public void destroy() { if (display != null && display.isValid()) display.remove(); }
    }

    public static class ExcavatorData {
        private final List<BlockDisplay> displays;
        private boolean isStoneUpgraded;
        private int productionTimer;

        public ExcavatorData(List<BlockDisplay> displays) {
            this.displays = displays;
            this.isStoneUpgraded = false;
            this.productionTimer = 0;
        }
        public List<BlockDisplay> getDisplays() { return displays; }
        public boolean isStoneUpgraded() { return isStoneUpgraded; }
        public void setStoneUpgraded(boolean stoneUpgraded) { this.isStoneUpgraded = stoneUpgraded; }
        public int getProductionTimer() { return productionTimer; }
        public void setProductionTimer(int productionTimer) { this.productionTimer = productionTimer; }
        public void destroy() {
            for (BlockDisplay bd : displays) { if (bd != null && bd.isValid()) bd.remove(); }
        }
    }

    private final Map<BlockKey, BarrierData> barrierRegistry = new ConcurrentHashMap<>();
    private final Map<BlockKey, ExcavatorData> excavatorRegistry = new ConcurrentHashMap<>();
    private final Map<UUID, List<BlockDisplay>> playerPreviewRegistry = new ConcurrentHashMap<>();
    private final Set<BlockKey> smithingWorkbenches = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> reloadingPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Integer> playerAmmoMap = new ConcurrentHashMap<>();

    private final List<Villager> coreHitboxes = new ArrayList<>();
    private final List<BlockDisplay> coreVisualDisplays = new ArrayList<>();
    private BlockDisplay centralNucleusDisplay = null;
    private BossBar bossBar;

    private double ringAngle = 0;
    private double coreHealth = 100.0;
    private boolean isDead = false;
    private boolean isGameStarted = false;
    private int currentWave = 0;
    private final List<Zombie> spawnedZombies = new ArrayList<>();
    private long globalTickCounter = 0L;
    private long waveTimeoutTargetTick = -1L;
    private Location coreCenterLocation;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        coreCenterLocation = new Location(getServer().getWorlds().get(0), 0.5, -41.5, 0.5);

        recreateBossBar();

        Bukkit.getScheduler().runTaskLater(this, () -> {
            World world = coreCenterLocation.getWorld();
            if (world != null) {
                clearOldFloatingEntities(world);
                spawnCoreEffect();
                getLogger().info("[Realdesertcore] 모든 컴파일 오류 수정 및 최적화 본 가동 완료.");
            }
        }, 20L);

        startUnifiedHeartbeatLoop();
    }

    @Override
    public void onDisable() {
        for (UUID uuid : new ArrayList<>(playerPreviewRegistry.keySet())) { clearPlayerPreview(uuid); }
        clearCoreEntitiesDeep(); // [오류 수정] 명칭 통일하여 빌드 폭파 방지
        if (bossBar != null) bossBar.removeAll();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (bossBar != null) {
            bossBar.addPlayer(event.getPlayer());
        }
    }

    public void clearSpawnedZombies() {
        for (Zombie z : spawnedZombies) { if (z.isValid()) z.remove(); }
        spawnedZombies.clear();
    }

    private void recreateBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
        }
        bossBar = Bukkit.createBossBar("§b[ 데저트 코어 에너지 ]", BarColor.PINK, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
        }
        bossBar.setVisible(true);
    }

    private void startUnifiedHeartbeatLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                globalTickCounter++;
                runCoreSaturnRingPulse();
                if (globalTickCounter % 2 == 0) runPlayerPreviewPulse();
                runZombieAILockPulse();
                if (globalTickCounter % 20 == 0) runProductionAndEnvironmentPulse();
                runWaveStateMonitoringPulse();
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void runCoreSaturnRingPulse() {
        if (isDead) return;

        ringAngle += 0.05;
        World world = coreCenterLocation.getWorld();
        if (world == null) return;

        if (centralNucleusDisplay != null && centralNucleusDisplay.isValid()) {
            float yawRotation = (float) (globalTickCounter * 0.03);
            float pitchRotation = (float) (globalTickCounter * 0.015);

            Quaternionf rot = new Quaternionf().rotationXYZ(pitchRotation, yawRotation, 0);

            centralNucleusDisplay.setTransformationMatrix(
                    new Matrix4f()
                            .translate(0f, 0.2f, 0f)
                            .rotate(rot)
                            .translate(-0.2f, -0.2f, -0.2f)
                            .scale(0.4f)
            );
        }

        Location ringCenter = coreCenterLocation.clone().add(0, 0.15, 0);
        Particle.DustOptions cyanDust = new Particle.DustOptions(Color.fromRGB(0, 210, 200), 1.1f);

        for (int i = 0; i < 6; i++) {
            double angle = ringAngle + (i * (Math.PI / 3));
            double xOffset = Math.sin(angle) * 1.2;
            double zOffset = Math.cos(angle) * 1.2;
            world.spawnParticle(Particle.DUST, ringCenter.clone().add(xOffset, 0, zOffset), 1, cyanDust);
        }
    }

    private void runPlayerPreviewPulse() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack mainHand = p.getInventory().getItemInMainHand();
            if (isCustomItem(mainHand, "§b[설치형] 굴착기")) {
                renderExcavatorPreview(p);
            } else {
                clearPlayerPreview(p.getUniqueId());
            }
        }
    }

    private void runZombieAILockPulse() {
        World w = coreCenterLocation.getWorld();
        if (w == null) return;

        for (Entity e : w.getEntities()) {
            if (e instanceof Zombie z && z.isValid() && !z.isDead()) {
                if (z.isBaby()) z.setBaby(false);

                if (isDead) {
                    Player targetPlayer = null;
                    double closestDistance = Double.MAX_VALUE;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                            double dist = z.getLocation().distanceSquared(p.getLocation());
                            if (dist < closestDistance) {
                                closestDistance = dist;
                                targetPlayer = p;
                            }
                        }
                    }
                    if (targetPlayer != null && (z.getTarget() == null || !z.getTarget().equals(targetPlayer))) {
                        z.setTarget(targetPlayer);
                    }
                    continue;
                }

                if (!coreHitboxes.isEmpty()) {
                    Villager target = coreHitboxes.get(0);
                    if (target != null && target.isValid()) {
                        if (z.getTarget() == null || !z.getTarget().equals(target)) {
                            z.setTarget(target);
                        }
                    }
                }

                Location zLoc = z.getLocation();
                BlockKey zk = BlockKey.from(zLoc);

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockKey bk = new BlockKey(zk.worldName(), zk.x() + dx, zk.y() + dy, zk.z() + dz);
                            if (barrierRegistry.containsKey(bk)) {
                                Location barrierLoc = bk.toLocation();
                                if (barrierLoc != null && zLoc.distance(barrierLoc) <= 1.25) {
                                    Vector pushBack = zLoc.toVector().subtract(barrierLoc.toVector()).normalize().setY(0.02).multiply(0.12);
                                    z.setVelocity(pushBack);

                                    if (globalTickCounter % 15 == 0) {
                                        executeZombieAttackBarrier(z, bk, barrierRegistry.get(bk));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void executeZombieAttackBarrier(Zombie zombie, BlockKey key, BarrierData barrier) {
        barrier.setHp(barrier.getHp() - 1);
        Location loc = key.toLocation();
        if (loc != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.6f, 1.0f);
            loc.getWorld().spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.5, 0), 6, Material.OAK_PLANKS.createBlockData());
        }
        if (barrier.getHp() <= 0) {
            barrier.destroy();
            barrierRegistry.remove(key);
            if (loc != null) {
                loc.getBlock().setType(Material.AIR);
                loc.getWorld().playSound(loc, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.8f);
                loc.getWorld().spawnParticle(Particle.BLOCK, loc.add(0, 0.5, 0), 20, Material.OAK_PLANKS.createBlockData());
            }
        }
    }

    private void runProductionAndEnvironmentPulse() {
        if (!isDead && !coreHitboxes.isEmpty()) {
            Location center = coreCenterLocation;
            for (Entity e : Objects.requireNonNull(center.getWorld()).getNearbyEntities(center, 2.5, 2.5, 2.5)) {
                if (e instanceof Zombie z && !z.isDead()) {
                    z.setNoDamageTicks(0);
                    z.damage(1.0);
                }
            }
        }

        // --- [기믹 변경] Y+3 위치의 물리적 상자(Chest)를 찾아 인벤토리에 직접 광물 적재 ---
        excavatorRegistry.forEach((key, data) -> {
            Location baseLoc = key.toLocation();
            if (baseLoc == null) return;

            Location chestLoc = baseLoc.clone().add(0, 3, 0);
            Block chestBlock = chestLoc.getBlock();

            if (chestBlock.getType() != Material.CHEST) return;

            Chest chest = (Chest) chestBlock.getState();
            Inventory inv = chest.getInventory();
            if (inv.firstEmpty() == -1) return;

            if (data.isStoneUpgraded()) {
                data.setProductionTimer(data.getProductionTimer() + 1);
                if (data.getProductionTimer() >= 20) {
                    data.setProductionTimer(0);
                    inv.addItem(createCopperIngotItem());
                    baseLoc.getWorld().playSound(chestLoc, Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.8f, 0.8f); // [오류 수정] 올바른 사운드 매핑
                    baseLoc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, chestLoc.clone().add(0, 0.5, 0), 5);
                }
            } else {
                data.setProductionTimer(data.getProductionTimer() + 1);
                if (data.getProductionTimer() >= 10) {
                    data.setProductionTimer(0);
                    inv.addItem(createPebbleItem());
                }
            }
        });
    }

    private void runWaveStateMonitoringPulse() {
        if (!isGameStarted) return;
        if (waveTimeoutTargetTick != -1L && globalTickCounter >= waveTimeoutTargetTick) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendActionBar("§c타임 아웃! 다음 웨이브를 구동합니다.");
            }
            clearSpawnedZombies();
            currentWave++;
            spawnWave(currentWave);
            return;
        }
        spawnedZombies.removeIf(z -> !z.isValid() || z.isDead());
        if (spawnedZombies.isEmpty() && currentWave > 0) {
            waveTimeoutTargetTick = -1L;
            currentWave++;
            spawnWave(currentWave);
        }
    }

    private void executePistolShoot(Player p) {
        UUID uuid = p.getUniqueId();
        if (reloadingPlayers.contains(uuid)) {
            p.sendActionBar("§c재장전 중에는 사격이 불가능합니다.");
            return;
        }
        int ammo = playerAmmoMap.getOrDefault(uuid, 5);
        if (ammo <= 0) {
            p.sendActionBar("§7탄약 부족! (좌클릭 재장전)");
            p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1.0f, 1.7f);
            return;
        }

        ammo--;
        playerAmmoMap.put(uuid, ammo);
        p.sendActionBar("§e⚡ 탄창: " + ammo + " / 5");
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.5f);

        Location eyeLoc = p.getEyeLocation();
        float currentPitch = eyeLoc.getPitch();
        float newPitch = Math.max(-90f, currentPitch - 4.5f);

        Location targetRot = p.getLocation();
        targetRot.setPitch(newPitch);
        p.teleport(targetRot);

        Vector dir = eyeLoc.getDirection();
        List<Zombie> targets = new ArrayList<>(this.spawnedZombies);
        RayTraceResult ray = p.getWorld().rayTrace(eyeLoc, dir, 30.0, FluidCollisionMode.NEVER, true, 0.4, entity -> (entity instanceof Zombie && targets.contains(entity)));

        Location hitPoint = (ray != null) ? ray.getHitPosition().toLocation(p.getWorld()) : eyeLoc.clone().add(dir.multiply(30.0));
        renderBulletTrail(eyeLoc.add(0, -0.2, 0), hitPoint);

        if (ray != null && ray.getHitEntity() instanceof Zombie zombie) {
            double damage = 8.0;
            double relativeY = ray.getHitPosition().getY() - zombie.getLocation().getY();

            if (relativeY >= 1.45 && relativeY <= 1.90) {
                if (zombie.getEquipment() != null && zombie.getEquipment().getHelmet() != null && zombie.getEquipment().getHelmet().getType() == Material.COBBLED_DEEPSLATE) {
                    zombie.getEquipment().setHelmet(null);
                    zombie.getWorld().playSound(zombie.getLocation(), Sound.BLOCK_STONE_BREAK, 1.2f, 0.8f);
                    p.sendActionBar("§7돌연변이의 투구를 파괴했습니다.");
                    damage = 0.0;
                } else {
                    damage = 16.0;
                    p.sendActionBar("§c💥 HEADSHOT");
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.5f);
                }
            }

            if (damage > 0) {
                zombie.damage(damage, p);
                zombie.getWorld().spawnParticle(Particle.BLOCK, ray.getHitPosition().toLocation(p.getWorld()), 12, Material.NETHER_WART_BLOCK.createBlockData());
            }
        }
    }

    private void triggerPistolReload(Player p) {
        UUID uuid = p.getUniqueId();
        if (playerAmmoMap.getOrDefault(uuid, 5) == 5) {
            p.sendActionBar("§c탄창이 가득 차 있습니다.");
            p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1.0f, 1.0f);
            return;
        }

        if (reloadingPlayers.contains(uuid)) return;

        if (!hasInventoryCustomItem(p, "§e[탄약] §f권총 총알")) {
            p.sendActionBar("§c권총 총알(철 조각)이 없습니다.");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            return;
        }

        deductInventoryCustomItem(p, "§e[탄약] §f권총 총알", 1);
        reloadingPlayers.add(uuid);
        p.sendActionBar("§7🔄 실린더 재장전 중...");
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.2f);

        ItemStack pistol = p.getInventory().getItemInMainHand();
        if (pistol.hasItemMeta()) {
            ItemMeta m = pistol.getItemMeta();
            m.setCustomModelData(2);
            pistol.setItemMeta(m);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                playerAmmoMap.put(uuid, 5);
                reloadingPlayers.remove(uuid);
                if (p.getInventory().getItemInMainHand().hasItemMeta()) {
                    ItemMeta m = p.getInventory().getItemInMainHand().getItemMeta();
                    m.setCustomModelData(1);
                    p.getInventory().getItemInMainHand().setItemMeta(m);
                }
                p.sendActionBar("§a⚡ 재장전 완료");
                p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0f, 1.5f);
            }
        }.runTaskLater(this, 40L);
    }

    private void renderExcavatorPreview(Player p) {
        RayTraceResult ray = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 5.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() != BlockFace.UP) {
            clearPlayerPreview(p.getUniqueId());
            return;
        }
        Location baseLoc = ray.getHitBlock().getRelative(BlockFace.UP).getLocation();
        List<BlockDisplay> currentPreviews = playerPreviewRegistry.get(p.getUniqueId());

        if (currentPreviews != null && !currentPreviews.isEmpty() && currentPreviews.get(0).getLocation().getBlockX() == baseLoc.getBlockX() && currentPreviews.get(0).getLocation().getBlockY() == baseLoc.getBlockY() && currentPreviews.get(0).getLocation().getBlockZ() == baseLoc.getBlockZ()) {
            return;
        }

        clearPlayerPreview(p.getUniqueId());
        List<BlockDisplay> newPreviews = new ArrayList<>();

        // 프리뷰 모델링도 가설 Y+3 상자 구조를 직관적으로 예측하도록 동기화 수정
        BlockDisplay fenceDisp1 = spawnStructureDisplay(baseLoc.clone().add(0.1, 0.0, 0.1), Material.OAK_FENCE, 0.8f);
        BlockDisplay fenceDisp2 = spawnStructureDisplay(baseLoc.clone().add(0.1, 1.0, 0.1), Material.OAK_FENCE, 0.8f);
        BlockDisplay fenceDisp3 = spawnStructureDisplay(baseLoc.clone().add(0.1, 2.0, 0.1), Material.OAK_FENCE, 0.8f);
        BlockDisplay chest = spawnStructureDisplay(baseLoc.clone().add(0.0, 3.0, 0.0), Material.CHEST, 1.0f);

        BlockDisplay[] bds = {fenceDisp1, fenceDisp2, fenceDisp3, chest};
        for (BlockDisplay bd : bds) {
            if (bd != null) {
                bd.setGlowing(true);
                bd.setGlowColorOverride(Color.AQUA);
                bd.addScoreboardTag("excavator_preview");
                newPreviews.add(bd);
            }
        }
        playerPreviewRegistry.put(p.getUniqueId(), newPreviews);
    }

    private void clearPlayerPreview(UUID uuid) {
        List<BlockDisplay> previews = playerPreviewRegistry.remove(uuid);
        if (previews != null) {
            for (BlockDisplay bd : previews) { if (bd != null && bd.isValid()) bd.remove(); }
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking()) return;

        Location loc = player.getLocation();
        BlockKey bk = BlockKey.from(loc);

        boolean nearBarrier = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockKey search = new BlockKey(bk.worldName(), bk.x() + dx, bk.y(), bk.z() + dz);
                if (barrierRegistry.containsKey(search)) {
                    nearBarrier = true;
                    break;
                }
            }
        }

        if (nearBarrier && player.getLocation().getY() % 1.0 == 0) {
            Vector dir = player.getLocation().getDirection().setY(0).normalize().multiply(0.42);
            player.setVelocity(new Vector(dir.getX(), 0.52, dir.getZ()));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.2f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractHold(PlayerInteractEvent event) {
        // --- [버그 수정] 왼손(Off Hand) 호출은 즉시 캔슬하여 메시지가 2개씩 뜨는 것을 완벽 방지 ---
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item != null && isCustomItem(item, "§7권총")) {
            Action action = event.getAction();
            if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true); executePistolShoot(player); return;
            } else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true); triggerPistolReload(player); return;
            }
        }

        Block clickedBlock = event.getClickedBlock();
        Location clickedLoc = (clickedBlock != null) ? clickedBlock.getLocation() : null;
        BlockKey clickedKey = (clickedLoc != null) ? BlockKey.from(clickedLoc) : null;

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && clickedBlock != null && clickedBlock.getType() == Material.DEAD_BUSH) {
            event.setCancelled(true);
            clickedBlock.setType(Material.AIR);
            clickedBlock.getWorld().dropItemNaturally(clickedBlock.getLocation().add(0.5, 0.1, 0.5), createTwigItem(2));
            clickedBlock.getWorld().playSound(clickedBlock.getLocation(), Sound.BLOCK_GRASS_BREAK, 1.0f, 0.6f);

            Location growLoc = clickedBlock.getLocation().clone().add(0.5, 0.0, 0.5);
            new BukkitRunnable() {
                int runtime = 0;
                BlockDisplay display = null;
                @Override
                public void run() {
                    if (isDead) { if (display != null) display.remove(); cancel(); return; }
                    if (runtime == 0) {
                        display = spawnStructureDisplay(growLoc, Material.DEAD_BUSH, 0.2f);
                    } else if (runtime < 4) {
                        if (display != null && display.isValid()) {
                            float scale = 0.2f + (runtime * 0.2f);
                            display.setTransformationMatrix(new Matrix4f().scale(scale));
                        }
                    } else {
                        if (display != null) display.remove();
                        clickedBlock.setType(Material.DEAD_BUSH);
                        clickedBlock.getWorld().playSound(clickedBlock.getLocation(), Sound.BLOCK_BAMBOO_PLACE, 1.0f, 0.5f);
                        cancel();
                        return;
                    }
                    runtime++;
                }
            }.runTaskTimer(this, 0L, 1200L);
            return;
        }

        // --- [기믹 변경 구현반] 굴착기 클릭 처리 ---
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && clickedKey != null && excavatorRegistry.containsKey(clickedKey)) {
            ExcavatorData data = excavatorRegistry.get(clickedKey);

            if (player.isSneaking()) {
                event.setCancelled(true);
                data.destroy();
                excavatorRegistry.remove(clickedKey);

                // 설치 기반 배리어 제거
                clickedBlock.setType(Material.AIR);

                // Y+1, Y+2 펜스 제거 및 Y+3 물리 상자 파괴 (아이템 드롭 포함)
                Location base = clickedLoc.clone();
                base.clone().add(0, 1, 0).getBlock().setType(Material.AIR);
                base.clone().add(0, 2, 0).getBlock().setType(Material.AIR);

                Block topChest = base.clone().add(0, 3, 0).getBlock();
                if (topChest.getType() == Material.CHEST) {
                    topChest.breakNaturally(); // 모험모드에서도 철거 시 상자 내부 아이템 필드에 보존 드롭
                }

                player.getInventory().addItem(createExcavatorItem());
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 1.0f, 1.1f);
                player.sendActionBar("§c굴착기 기지와 수확용 상자가 완전히 철거되었습니다.");
                return;
            }

            // 시프트 우클릭이 아닌 단순 우클릭 시에는 공정 업그레이드 제어반 제공 (상자는 물리 블록이므로 알아서 열림!)
            event.setCancelled(true);
            openExcavatorUpgradeTextMenu(player, clickedKey, data);
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || clickedBlock == null || clickedKey == null) return;

        if (smithingWorkbenches.contains(clickedKey)) {
            event.setCancelled(true);
            openSmithingCraftingMenu(player);
            return;
        }

        if (item == null) return;

        if (isCustomItem(item, "§6참나무 장벽")) {
            event.setCancelled(true);
            Block targetBlock = clickedBlock.getRelative(event.getBlockFace());
            BlockKey targetKey = BlockKey.from(targetBlock.getLocation());
            if (targetBlock.getType().isSolid() || barrierRegistry.containsKey(targetKey)) return;

            item.setAmount(item.getAmount() - 1);
            targetBlock.setType(Material.OAK_FENCE);

            BlockDisplay bd = spawnStructureDisplay(targetBlock.getLocation(), Material.OAK_FENCE, 1.0f);
            if (bd != null) {
                bd.setInvisible(true);
                bd.addScoreboardTag("desert_barrier");
                barrierRegistry.put(targetKey, new BarrierData(bd, 3, false));
            }
            targetBlock.getWorld().playSound(targetBlock.getLocation(), Sound.BLOCK_WOOD_PLACE, 1.0f, 0.8f);
            return;
        }

        if (isCustomItem(item, "§7조약돌") && barrierRegistry.containsKey(clickedKey)) {
            event.setCancelled(true);
            BarrierData bData = barrierRegistry.get(clickedKey);
            if (bData.isStone()) {
                player.sendActionBar("§c해당 장벽은 이미 석재 강화 상태입니다.");
                return;
            }
            item.setAmount(item.getAmount() - 1);
            if (bData.getDisplay() != null && bData.getDisplay().isValid()) bData.getDisplay().remove();

            clickedBlock.setType(Material.STONE_BRICK_WALL);
            BlockDisplay nBd = spawnStructureDisplay(clickedKey.toLocation().add(-0.5, 0, -0.5), Material.STONE_BRICK_WALL, 1.0f);
            if (nBd != null) {
                nBd.setInvisible(true);
                nBd.addScoreboardTag("desert_barrier");
            }

            bData.setStone(true);
            bData.setHp(10);
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_PLACE, 1.0f, 1.0f);
            player.sendActionBar("§a석재 격벽 강화 완료 (HP 10)");
            return;
        }

        if (isCustomItem(item, "§b[설치형] 굴착기")) {
            event.setCancelled(true);
            if (event.getBlockFace() != BlockFace.UP) return;
            Block placementLoc = clickedBlock.getRelative(BlockFace.UP);
            BlockKey exKey = BlockKey.from(placementLoc.getLocation());
            if (placementLoc.getType().isSolid() || excavatorRegistry.containsKey(exKey)) return;

            item.setAmount(item.getAmount() - 1);
            clearPlayerPreview(player.getUniqueId());

            placementLoc.setType(Material.BARRIER);
            Location bLoc = placementLoc.getLocation();

            bLoc.clone().add(0, 1, 0).getBlock().setType(Material.OAK_FENCE);
            bLoc.clone().add(0, 2, 0).getBlock().setType(Material.OAK_FENCE);

            Block topBlock = bLoc.clone().add(0, 3, 0).getBlock();
            topBlock.setType(Material.CHEST);

            // 프리뷰 외형 변경: 중간 기둥(Y+1, Y+2) 울타리/널빤지로 프리뷰에 고정
            BlockDisplay fenceDisp1 = spawnStructureDisplay(bLoc.clone().add(0.1, 0.0, 0.1), Material.OAK_FENCE, 0.8f);
            BlockDisplay fenceDisp2 = spawnStructureDisplay(bLoc.clone().add(0.1, 1.0, 0.1), Material.OAK_FENCE, 0.8f);
            BlockDisplay fenceDisp3 = spawnStructureDisplay(bLoc.clone().add(0.1, 2.0, 0.1), Material.OAK_FENCE, 0.8f);

            // [오류 수정/요청 반영] 굴착기 몸체 시각적 프리뷰(Y+1.6 -> Y+3 체스트로 물리 블록 이동 완)

            List<BlockDisplay> dList = new ArrayList<>(List.of(fenceDisp1, fenceDisp2, fenceDisp3));

            excavatorRegistry.put(exKey, new ExcavatorData(dList));
            bLoc.getWorld().playSound(bLoc, Sound.BLOCK_WOOD_PLACE, 1.0f, 1.0f);
        }
    }

    private void openExcavatorUpgradeTextMenu(Player p, BlockKey key, ExcavatorData data) {
        p.sendMessage("§f ");
        p.sendMessage("§b⚙️ [ 굴착기 기지 공정 제어반 ]");

        if (data.isStoneUpgraded()) {
            p.sendMessage("  §d[✓] 석재 및 구리 추출 공정 활성화 완료");
            p.sendMessage("  §7(현재 3칸 위 상자에 구리 주괴를 안정적으로 가공 투입 중입니다.)");
        } else {
            String cmdStr = String.format("/_internal_exup %d %d %d", key.x(), key.y(), key.z());
            TextComponent upgradeBtn = new TextComponent("  §e[1] 구리 굴착기 공정 개량 §7(재료: 조약돌 10개)");
            upgradeBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§f조약돌 10개를 바쳐 플린트 공정을 구리 생산 공정으로 진화시킵니다.")));
            upgradeBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdStr));
            p.spigot().sendMessage(upgradeBtn);
        }

        TextComponent nextBtn = new TextComponent("  §c[2] 다음 테크 업그레이드 공정 §7(미개발)");
        nextBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§c아직 설계도가 제공되지 않은 공정입니다.")));
        p.spigot().sendMessage(nextBtn);
        p.sendMessage("§f ");
    }

    private void openSmithingCraftingMenu(Player p) {
        p.sendMessage("§f ");
        p.sendMessage("§6⚙️ [ 단조 작업대 조합 원격 UI ]"); // [오류 수정] 스크린샷과 UI명 일치화

        TextComponent tc1 = new TextComponent("  §e참나무 판자 2개 제작 §7(재료: 나뭇가지 4개)");
        tc1.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§f나뭇가지 4개를 소모하여 널빤지 2개를 만듭니다.")));
        tc1.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/_internal_craft_plank"));

        TextComponent tc2 = new TextComponent("  §e참나무 장벽 10개 제작 §7(재료: 판자 5개 + 나뭇가지 4개)");
        tc2.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§6참나무 장벽 10개§f를 제작합니다.")));
        tc2.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/_internal_craft_barrier"));

        TextComponent tc3 = new TextComponent("  §b[설치형] 굴착기 제작 §7(재료: 나뭇가지 10개 + 판자 10개)"); // [오류 수정] 막대기 -> 나뭇가지 텍스트 연동 고정
        tc3.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§b[설치형] 굴착기 가설 툴킷§f을 조립합니다.")));
        tc3.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/_internal_craft_excavator"));

        p.spigot().sendMessage(tc1);
        p.spigot().sendMessage(tc2);
        p.spigot().sendMessage(tc3);
        p.sendMessage("§f ");
    }

    private void handleInternalCraft(Player player, String[] args) {
        String type = args[0].replace("_internal_craft_", "");
        if (type.equalsIgnoreCase("plank")) {
            if (getCustomItemCount(player, "§e나뭇가지") < 4) {
                player.sendActionBar("§c재료 부족! (나뭇가지 4개 필요)");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
                return;
            }
            deductInventoryCustomItem(player, "§e나뭇가지", 4);
            player.getInventory().addItem(createPlankItem(2));

            // [오류 수정/요청 반영] 널빤지 제작 시 타이틀로 출력
            player.playSound(player.getLocation(), Sound.BLOCK_WOOD_BREAK, 1.0f, 1.2f); // [오류 수정 완]
            player.sendTitle("", "§a[✓] 널빤지 2개 가공 완료", 5, 40, 5);

        } else if (type.equalsIgnoreCase("barrier")) {
            int planks = getCustomItemCount(player, "§f널빤지");
            int twigs = getCustomItemCount(player, "§e나뭇가지");
            if (planks < 5 || twigs < 4) {
                player.sendMessage("§c[!] 재료 부족! (참나무 판자 5개와 나뭇가지 4개 필요)"); // [오류 수정] 스크린샷 오류 출력 고정
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
                return;
            }
            deductInventoryCustomItem(player, "§f널빤지", 5);
            deductInventoryCustomItem(player, "§e나뭇가지", 4);
            player.getInventory().addItem(createBarrierItem(10));
            player.playSound(player.getLocation(), Sound.BLOCK_WOOD_PLACE, 1.0f, 1.0f);
            player.sendActionBar("§a참나무 장벽 10개 제작 완료");
        } else if (type.equalsIgnoreCase("excavator")) {
            int twigs = getCustomItemCount(player, "§e나뭇가지");
            int planks = getCustomItemCount(player, "§f널빤지");
            if (twigs < 10 || planks < 10) {
                player.sendActionBar("§c재료 부족! (나뭇가지 10개 / 널빤지 10개 필요)");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
                return;
            }
            deductInventoryCustomItem(player, "§e나뭇가지", 10);
            deductInventoryCustomItem(player, "§f널빤지", 10);
            player.getInventory().addItem(createExcavatorItem());
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
            player.sendActionBar("§b[설치형] 굴착기 조립 완료");
        }
    }

    @EventHandler
    public void onZombieDeathLoot(EntityDeathEvent event) {
        if (event.getEntity() instanceof Zombie zombie && zombie.getScoreboardTags().contains("wave_zombie")) {
            if (ThreadLocalRandom.current().nextDouble() <= 0.30) {
                event.getDrops().add(createAmmoItem(1));
            }
        }
    }

    @EventHandler
    public void onCoreHitStructure(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Villager villager && villager.getScoreboardTags().contains("core_hitbox")) {
            event.setCancelled(true);
            if (isDead) return;

            if (event.getDamager() instanceof Zombie) {
                coreHealth = Math.max(0, coreHealth - 2.0);
                updateBossBarDisplay();
                coreCenterLocation.getWorld().playSound(coreCenterLocation, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 0.5f);

                if (coreHealth <= 0) {
                    executeSupernovaSequence();
                }
            }
        }
    }

    private void executeSupernovaSequence() {
        isDead = true;
        clearCoreVisualsOnly();

        World world = coreCenterLocation.getWorld();
        if (world == null) return;

        world.playSound(coreCenterLocation, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 3.0f, 0.5f);
        world.playSound(coreCenterLocation, Sound.BLOCK_BEACON_DEACTIVATE, 3.0f, 0.5f);
        world.playSound(coreCenterLocation, Sound.ENTITY_WITHER_DEATH, 2.0f, 0.5f);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§c§l⚠️ 코어 붕괴 ⚠️", "§4좀비들이 폭주하여 플레이어를 사냥합니다!", 10, 80, 20);
        }

        PotionEffect strengthEffect = new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 254, false, true);
        PotionEffect speedEffect = new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, true);

        for (Zombie z : new ArrayList<>(spawnedZombies)) {
            if (z != null && z.isValid()) {
                z.addPotionEffect(strengthEffect);
                z.addPotionEffect(speedEffect);
                z.setGlowing(true);
            }
        }

        new BukkitRunnable() {
            int ticks = 0;
            final List<Vector> particles = new ArrayList<>();
            final Particle.DustOptions pColor = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.4f);

            @Override
            public void run() {
                if (ticks == 0) {
                    for (int i = 0; i < 750; i++) {
                        double theta = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
                        double phi = Math.acos(ThreadLocalRandom.current().nextDouble() * 2 - 1);
                        double speed = ThreadLocalRandom.current().nextDouble(0.8, 1.4);
                        particles.add(new Vector(Math.sin(phi) * Math.cos(theta), Math.sin(phi) * Math.sin(theta), Math.cos(phi)).multiply(speed));
                    }
                }

                for (int i = 0; i < particles.size(); i++) {
                    Vector v = particles.get(i);
                    v.multiply(0.86);
                    Location pLoc = coreCenterLocation.clone().add(v.clone().multiply(ticks));
                    world.spawnParticle(Particle.DUST, pLoc, 1, pColor);
                }

                if (ticks++ >= 25) { cancel(); }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAdminCommandIntercept(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage().trim();
        if (!msg.startsWith("/")) return;

        if (msg.startsWith("/_internal_craft_")) {
            event.setCancelled(true);
            String[] args = msg.substring(1).split(" ");
            handleInternalCraft(player, args);
            return;
        }

        if (msg.startsWith("/_internal_exup")) {
            event.setCancelled(true);
            String[] parts = msg.split(" ");
            if (parts.length < 4) return;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            BlockKey key = new BlockKey(player.getWorld().getName(), x, y, z);
            ExcavatorData data = excavatorRegistry.get(key);
            if (data == null || data.isStoneUpgraded()) return;

            if (getCustomItemCount(player, "§7조약돌") < 10) {
                player.sendActionBar("§c업그레이드 실패: 조약돌 10개가 필요합니다.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
                return;
            }

            deductInventoryCustomItem(player, "§7조약돌", 10);
            data.setStoneUpgraded(true);
            data.setProductionTimer(0);

            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 0.8f);
            player.sendActionBar("§d⚙️ 굴착기가 석재 구리 추출 공정으로 진화했습니다!");
            return;
        }

        String[] parts = msg.substring(1).split(" ");
        String cmd = parts[0];

        String[] adminCmds = {"게임시작", "권총지급", "탄약지급", "코어재생", "보스바청소"};
        boolean check = false;
        for (String ac : adminCmds) { if (cmd.equalsIgnoreCase(ac)) { check = true; break; } }

        if (!check) return;

        event.setCancelled(true);
        if (!player.getName().equals("nogchateabag") && !player.isOp()) {
            player.sendActionBar("§c권한이 부족합니다.");
            return;
        }

        if (cmd.equalsIgnoreCase("게임시작")) {
            if (isGameStarted) { player.sendActionBar("§c이미 디펜스 세션이 진행 중입니다."); return; }
            startGameCountdown();
        } else if (cmd.equalsIgnoreCase("권총지급")) {
            player.getInventory().addItem(createPistolItem());
            player.sendActionBar("§e[Realdesertcore] §f3D 권총이 지급되었습니다.");
        } else if (cmd.equalsIgnoreCase("탄약지급")) {
            player.getInventory().addItem(createAmmoItem(64));
            player.sendActionBar("§e[Realdesertcore] §f특수 권총 탄약이 지급되었습니다.");
        } else if (cmd.equalsIgnoreCase("코어재생")) {
            clearCoreVisualsOnly();
            isGameStarted = false; currentWave = 0; coreHealth = 100.0; isDead = false;
            recreateBossBar(); spawnCoreEffect();
            player.sendActionBar("§a코어 엔티티 청소 및 지면 배치 완료");
        } else if (cmd.equalsIgnoreCase("보스바청소")) {
            recreateBossBar();
            player.sendActionBar("§a보스바가 정상 재연결되었습니다.");
        }
    }

    private void startGameCountdown() {
        new BukkitRunnable() {
            int count = 6;
            @Override
            public void run() {
                count--;
                if (count > 0) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendTitle("§e§l" + count, "§f좀비가 멀리서 다가오는 중...", 0, 22, 0);
                    }
                } else {
                    isGameStarted = true; currentWave = 1; coreHealth = 100.0; isDead = false;
                    updateBossBarDisplay(); spawnWave(currentWave); cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void spawnWave(int wave) {
        if (wave > 5) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§6§lVICTORY", "§e데저트 코어 방어전 최종 승리!", 10, 70, 20);
            }
            isGameStarted = false;
            return;
        }

        currentWave = wave;
        waveTimeoutTargetTick = globalTickCounter + 2400L;

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§d§lWAVE " + wave, "§7방어선을 견고히 유지하십시오.", 10, 40, 10);
        }

        World world = coreCenterLocation.getWorld(); if (world == null) return;
        int normal = 0, sword = 0, armor = 0;

        switch (wave) {
            case 1 -> normal = 3;
            case 2 -> normal = 10;
            case 3 -> sword = 5;
            case 4 -> armor = 15;
            case 5 -> { sword = 10; armor = 10; }
        }

        for (int i = 0; i < normal; i++) spawnMutantAtRandom(world, false, false);
        for (int i = 0; i < sword; i++) spawnMutantAtRandom(world, true, false);
        for (int i = 0; i < armor; i++) spawnMutantAtRandom(world, false, true);
    }

    private void spawnMutantAtRandom(World world, boolean weapon, boolean armor) {
        double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        double radius = ThreadLocalRandom.current().nextDouble(18.0, 24.0);
        Location spawnLoc = coreCenterLocation.clone().add(Math.sin(angle) * radius, 0, Math.cos(angle) * radius);
        spawnLoc.setY(world.getHighestBlockYAt(spawnLoc) + 1.0);
        createWaveZombie(world, spawnLoc, weapon, armor);
    }

    private void createWaveZombie(World world, Location loc, boolean weapon, boolean armor) {
        Zombie zombie = world.spawn(loc, Zombie.class);
        zombie.setBaby(false);
        Objects.requireNonNull(zombie.getEquipment()).clear();

        if (weapon) {
            zombie.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
            zombie.setCustomName("§c[전투형 돌검 변이체]");
        } else if (armor) {
            zombie.getEquipment().setHelmet(new ItemStack(Material.COBBLED_DEEPSLATE));
            zombie.setCustomName("§e[탱커형 심층암 변이체]");
        } else {
            zombie.setCustomName("§7[기본 뮤턴트 좀비]");
        }
        zombie.setCustomNameVisible(true);
        zombie.addScoreboardTag("wave_zombie");

        if (isDead) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 254, false, true));
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, true));
            zombie.setGlowing(true);
        } else if (!coreHitboxes.isEmpty()) {
            zombie.setTarget(coreHitboxes.get(0));
        }

        spawnedZombies.add(zombie);
    }

    @EventHandler
    public void onChickenJockeySpawnRestrict(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Chicken) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreakRestriction(BlockBreakEvent event) {
        Player p = event.getPlayer(); if (p.isOp() || p.getName().equals("nogchateabag") && !p.isSneaking()) return;
        if (barrierRegistry.containsKey(BlockKey.from(event.getBlock().getLocation())) || excavatorRegistry.containsKey(BlockKey.from(event.getBlock().getLocation()))) {
            event.setCancelled(true);
            p.sendActionBar("§c수호 자산 인프라는 서바이벌 모드식 파괴가 제한됩니다.");
        }
    }

    @EventHandler
    public void onChunkUnloadClear(ChunkUnloadEvent event) {
        int cx = event.getChunk().getX(); int cz = event.getChunk().getZ();

        barrierRegistry.entrySet().removeIf(e -> {
            if (e.getKey().isInChunk(cx, cz)) {
                e.getValue().destroy();
                return true;
            }
            return false;
        });
        excavatorRegistry.entrySet().removeIf(e -> {
            if (e.getKey().isInChunk(cx, cz)) {
                e.getValue().destroy();
                return true;
            }
            return false;
        });
    }

    private void spawnCoreEffect() {
        World world = coreCenterLocation.getWorld(); if (world == null) return;

        Location nucleusLoc = coreCenterLocation.clone();
        centralNucleusDisplay = spawnStructureDisplay(nucleusLoc, Material.DIAMOND_BLOCK, 0.4f);
        if (centralNucleusDisplay != null) {
            coreVisualDisplays.add(centralNucleusDisplay);
        }

        for (int i = 0; i < 5; i++) {
            Villager v = world.spawn(coreCenterLocation.clone().add(0, -0.5, 0), Villager.class);
            v.setAI(false); v.setSilent(true); v.setInvulnerable(false);
            v.setInvisible(true);
            v.addScoreboardTag("core_hitbox");
            coreHitboxes.add(v);
        }

        smithingWorkbenches.clear();
        int[][] offsets = {{-3, -3}, {-3, 3}, {3, -3}, {3, 3}};
        for (int[] os : offsets) {
            Location smithingLoc = new Location(world, os[0], -44.0, os[1]);
            smithingLoc.getBlock().setType(Material.SMITHING_TABLE);
            smithingWorkbenches.add(BlockKey.from(smithingLoc));
        }

        updateBossBarDisplay();
    }

    private void updateBossBarDisplay() {
        if (bossBar != null) {
            double progress = Math.max(0.0, Math.min(1.0, coreHealth / 100.0));
            bossBar.setProgress(progress);
            bossBar.setTitle("§b[ 데저트 코어 에너지 ] §f- §e" + (int)coreHealth + "%");
        }
    }

    private void clearCoreVisualsOnly() {
        for (Villager v : coreHitboxes) { if (v != null && v.isValid()) v.remove(); }
        coreHitboxes.clear();
        for (BlockDisplay bd : coreVisualDisplays) { if (bd != null && bd.isValid()) bd.remove(); }
        coreVisualDisplays.clear();
        if (centralNucleusDisplay != null && centralNucleusDisplay.isValid()) centralNucleusDisplay.remove();
        centralNucleusDisplay = null;
    }

    private void clearCoreEntitiesDeep() { // [명칭 수정 완]
        clearCoreVisualsOnly();
        barrierRegistry.values().forEach(BarrierData::destroy); barrierRegistry.clear();
        excavatorRegistry.values().forEach(ExcavatorData::destroy); excavatorRegistry.clear();
        clearSpawnedZombies();
    }

    private void clearOldFloatingEntities(World world) {
        for (Entity e : world.getEntities()) {
            if (e instanceof BlockDisplay bd && (bd.getScoreboardTags().contains("desert_barrier") || bd.getScoreboardTags().contains("excavator_preview"))) bd.remove();
            if (e instanceof Villager v && v.getScoreboardTags().contains("core_hitbox")) v.remove();
        }
    }

    private ItemStack createPistolItem() {
        ItemStack item = new ItemStack(Material.IRON_HOE);
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.setDisplayName("§7권총");
            m.setCustomModelData(1);
            m.setLore(List.of("§7우클릭: 화기 정밀 격발 (Shoot)", "§7좌클릭: 탄창 실린더 재장전 (Reload)"));
        }
        item.setItemMeta(m);
        return item;
    }

    private ItemStack createAmmoItem(int amt) {
        ItemStack item = new ItemStack(Material.IRON_NUGGET, amt);
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.setDisplayName("§e[탄약] §f권총 총알");
            m.setLore(List.of("§7권총을 재장전할 때 1개가 소모됩니다."));
        }
        item.setItemMeta(m);
        return item;
    }

    private ItemStack createPebbleItem() {
        ItemStack item = new ItemStack(Material.FLINT);
        ItemMeta m = item.getItemMeta();
        if (m != null) m.setDisplayName("§7조약돌");
        item.setItemMeta(m);
        return item;
    }

    private ItemStack createCopperIngotItem() {
        ItemStack item = new ItemStack(Material.COPPER_INGOT);
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.setDisplayName("§d[특수 자원] 구리 주괴");
            m.setLore(List.of("§7석재 업그레이드가 가동된 굴착기 기지에서", "§720초에 한 번씩 추출해내는 고유의 정련 금속재."));
        }
        item.setItemMeta(m);
        return item;
    }

    private ItemStack createBarrierItem(int amt) {
        ItemStack item = new ItemStack(Material.OAK_FENCE, amt);
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.setDisplayName("§6참나무 장벽");
            m.setLore(List.of("§7블록에 우클릭하여 지면에 배치 가능", "§7조약돌 클릭 시 석재 격벽 돌담장 강화 (HP 10)", "§7기본 내구도: HP 3"));
        }
        item.setItemMeta(m);
        return item;
    }

    private ItemStack createExcavatorItem() {
        ItemStack item = new ItemStack(Material.SCAFFOLDING);
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.setDisplayName("§b[설치형] 굴착기");
            m.setLore(List.of("§7지면에 우클릭하여 거치 설치 (물리 블록화)", "§7Y+3 물리 상자 우클릭: 직접 수확 및 루팅 가능 (모험모드 최적화)", "§7하단 몸체 우클릭: 기지 공정 업그레이드 텍스트 제어", "§7쉬프트+우클릭: 완전히 철거 및 잔여 상자 해체"));
        }
        item.setItemMeta(m);
        return item;
    }

    private ItemStack createTwigItem(int amt) {
        ItemStack item = new ItemStack(Material.STICK, amt);
        ItemMeta m = item.getItemMeta();
        if (m != null) m.setDisplayName("§e나뭇가지");
        item.setItemMeta(m);
        return item;
    }

    private ItemStack createPlankItem(int amt) {
        ItemStack item = new ItemStack(Material.OAK_PLANKS, amt);
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.setDisplayName("§f널빤지");
            m.setLore(List.of("§7나뭇가지를 단조 작업대에서 가공하여 만든 고급 수호 자재."));
        }
        item.setItemMeta(m);
        return item;
    }

    private BlockDisplay spawnStructureDisplay(Location loc, Material mat, float scale) {
        World w = loc.getWorld(); if (w == null) return null;
        return w.spawn(loc, BlockDisplay.class, d -> {
            d.setBlock(mat.createBlockData());
            d.setTransformationMatrix(new Matrix4f().scale(scale));
        });
    }

    private void renderBulletTrail(Location start, Location end) {
        Vector direction = end.toVector().subtract(start.toVector());
        double distance = direction.length();
        direction.normalize();
        for (double d = 0; d < distance; d += 0.4) {
            start.getWorld().spawnParticle(Particle.SMOKE, start.clone().add(direction.clone().multiply(d)), 1, 0, 0, 0, 0);
        }
    }

    private boolean isCustomItem(ItemStack is, String name) {
        return is != null && is.hasItemMeta() && is.getItemMeta().getDisplayName().equals(name);
    }

    private boolean hasInventoryCustomItem(Player p, String name) {
        for (ItemStack is : p.getInventory().getContents()) { if (isCustomItem(is, name)) return true; }
        return false;
    }

    private int getCustomItemCount(Player p, String name) {
        int count = 0;
        for (ItemStack is : p.getInventory().getContents()) { if (isCustomItem(is, name)) count += is.getAmount(); }
        return count;
    }

    private void deductInventoryCustomItem(Player p, String name, int amt) {
        for (ItemStack is : p.getInventory().getContents()) {
            if (isCustomItem(is, name)) {
                int next = is.getAmount() - amt;
                if (next <= 0) is.setAmount(0); else is.setAmount(next);
                break;
            }
        }
    }
}