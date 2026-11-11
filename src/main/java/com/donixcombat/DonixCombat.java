package com.donixcombat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DonixCombat extends JavaPlugin implements Listener {
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    private final Map<LocationKey, AttackStamp> crystalAttackers = new ConcurrentHashMap<>();
    private final Map<LocationKey, AttackStamp> anchorAttackers = new ConcurrentHashMap<>();
    private final Set<String> allowedCommands = new HashSet<>();
    private final Set<String> safeRegions = new HashSet<>();
    private Object regionHook;
    private java.lang.reflect.Method regionCheck;
    private BukkitTask ticker;
    private YamlConfiguration mainSettings, combatSettings, safezoneSettings, commandsSettings, messagesSettings;
    private boolean featureEnabled;
    private long combatMillis;

    @Override
    public void onEnable() {
        loadSettings();
        initializeWorldGuardHook();
        Bukkit.getPluginManager().registerEvents(this, this);
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 0L, 20L);
        getLogger().info("DonixCombat v" + getDescription().getVersion() + (featureEnabled ? " enabled." : " loaded but disabled in config.yml."));
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        combatUntil.clear();
        crystalAttackers.clear();
        anchorAttackers.clear();
    }

    private void loadSettings() {
        mainSettings = loadYaml("config.yml");
        combatSettings = loadYaml("combat/config.yml");
        safezoneSettings = loadYaml("safezone/config.yml");
        commandsSettings = loadYaml("commands/config.yml");
        messagesSettings = loadYaml("messages/config.yml");
        featureEnabled = mainSettings.getBoolean("enabled", true);
        combatMillis = Math.max(1, combatSettings.getInt("duration-seconds", 20)) * 1000L;
        allowedCommands.clear();
        for (String command : commandsSettings.getStringList("allowed")) {
            allowedCommands.add(command.toLowerCase(Locale.ROOT));
        }
        safeRegions.clear();
        for (String region : safezoneSettings.getStringList("regions")) {
            safeRegions.add(region.toLowerCase(Locale.ROOT));
        }
        if (!featureEnabled) {
            combatUntil.clear();
            for (Player player : Bukkit.getOnlinePlayers()) player.sendActionBar("");
        }
    }

    private YamlConfiguration loadYaml(String relativePath) {
        File file = new File(getDataFolder(), relativePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            getLogger().warning("Could not create config folder: " + parent.getPath());
        }
        if (!file.exists()) {
            try {
                saveResource(relativePath, false);
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Missing default config resource: " + relativePath);
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    public boolean isInCombat(Player player) {
        if (!featureEnabled) return false;
        Long until = combatUntil.get(player.getUniqueId());
        if (until == null) return false;
        if (until <= System.currentTimeMillis()) {
            combatUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void tag(Player player) {
        if (!featureEnabled || !player.isOnline()) return;
        long now = System.currentTimeMillis();
        boolean alreadyTagged = combatUntil.containsKey(player.getUniqueId())
                && combatUntil.get(player.getUniqueId()) > now;
        combatUntil.put(player.getUniqueId(), now + combatMillis);

        if (combatSettings.getBoolean("disable-flight", true)) {
            if (player.isFlying()) player.setFlying(false);
            if (player.getAllowFlight()) player.setAllowFlight(false);
        }
        if (!alreadyTagged) {
            String message = messagesSettings.getString("combat-start", "&cYou are now in combat for &f%time% seconds&c!");
            player.sendMessage(color(message.replace("%time%", String.valueOf(combatMillis / 1000L))));
        }
    }

    private void tagPair(Player first, Player second) {
        if (first == null || second == null || first.equals(second)) return;
        tag(first);
        tag(second);
    }

    private void tickCombat() {
        if (!featureEnabled) return;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long until = combatUntil.get(player.getUniqueId());
            if (until == null) continue;
            long remaining = until - now;
            if (remaining <= 0) {
                combatUntil.remove(player.getUniqueId());
                player.sendMessage(color(messagesSettings.getString("combat-end", "&aYou are no longer in combat.")));
                player.sendActionBar("");
                continue;
            }
            long seconds = (remaining + 999L) / 1000L;
            String bar = combatSettings.getString("actionbar", "&cCombat &7» &f%time%s");
            player.sendActionBar(color(bar.replace("%time%", String.valueOf(seconds))));
        }
        long expiry = now - 5000L;
        crystalAttackers.entrySet().removeIf(entry -> entry.getValue().timestamp < expiry);
        anchorAttackers.entrySet().removeIf(entry -> entry.getValue().timestamp < expiry);
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!featureEnabled) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker != null) tagPair(attacker, victim);
        if (event.getDamager() instanceof EnderCrystal crystal && crystal.getLocation() != null) {
            AttackStamp source = crystalAttackers.get(LocationKey.of(crystal.getLocation()));
            Player crystalAttacker = source == null ? null : Bukkit.getPlayer(source.attacker);
            // Only tag the crystal owner when the explosion actually damages another player.
            // Self-damage from your own crystal must never create a combat tag.
            if (crystalAttacker != null && !crystalAttacker.equals(victim)) {
                tagPair(crystalAttacker, victim);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (!featureEnabled) return;
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker != null) crystalAttackers.put(LocationKey.of(crystal.getLocation()), new AttackStamp(attacker.getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalExplode(EntityExplodeEvent event) {
        if (!featureEnabled) return;
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        LocationKey key = LocationKey.of(crystal.getLocation());
        // Entity damage events for the explosion are processed around this event. Keep
        // the owner stamp briefly so those events can pair the owner with actual victims.
        Bukkit.getScheduler().runTaskLater(this, () -> crystalAttackers.remove(key), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnchorInteract(PlayerInteractEvent event) {
        if (!featureEnabled) return;
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType().name().equals("RESPAWN_ANCHOR") == false) return;
        Location location = block.getLocation();
        anchorAttackers.put(LocationKey.of(location), new AttackStamp(event.getPlayer().getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnchorExplode(BlockExplodeEvent event) {
        if (!featureEnabled) return;
        AttackStamp stamp = anchorAttackers.remove(LocationKey.of(event.getBlock().getLocation()));
        Player attacker = stamp == null ? null : Bukkit.getPlayer(stamp.attacker);
        if (attacker != null) tagExplosionPlayers(attacker, event.getBlock().getLocation(), 12.0);
    }

    private void tagExplosionPlayers(Player attacker, Location center, double radius) {
        if (attacker == null || !attacker.isOnline() || center.getWorld() == null) return;
        double squared = radius * radius;
        boolean hitAnotherPlayer = false;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof Player victim && !victim.equals(attacker)
                    && victim.getLocation().distanceSquared(center) <= squared) {
                tag(victim);
                hitAnotherPlayer = true;
            }
        }
        // A respawn anchor only starts combat when it affects someone other than its owner.
        if (hitAnotherPlayer) tag(attacker);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!featureEnabled) return;
        Player player = event.getPlayer();
        if (!isInCombat(player)) return;
        String message = event.getMessage().substring(1).trim();
        if (message.isEmpty()) return;
        String root = message.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.lastIndexOf(':') + 1);
        if (allowedCommands.contains(root)) return;
        event.setCancelled(true);
        player.sendMessage(color(messagesSettings.getString("command-blocked", "&cYou cannot use that command while in combat!")));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!featureEnabled) return;
        if (event.getTo() == null || !isInCombat(event.getPlayer()) || regionHook == null || regionCheck == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == to.getWorld() && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        if (isSafeRegion(to)) {
            Player player = event.getPlayer();
            event.setTo(from);
            knockPlayerAway(player, from, to);
            if (safezoneSettings.getBoolean("barrier.enabled", true)) {
                showRedGlassBarrier(player, to);
            }
            player.sendActionBar(color(messagesSettings.getString("safezone-entry", "&cYou cannot enter a safezone while in combat!")));
        }
    }

    private void knockPlayerAway(Player player, Location from, Location attemptedTo) {
        Vector away = from.toVector().subtract(attemptedTo.toVector());
        away.setY(0);
        if (away.lengthSquared() < 0.0001) {
            away = player.getLocation().getDirection().multiply(-1);
            away.setY(0);
        }
        if (away.lengthSquared() > 0.0001) away.normalize();
        double strength = Math.max(0.0, safezoneSettings.getDouble("barrier.knockback-strength", 0.85));
        double vertical = safezoneSettings.getDouble("barrier.knockback-y", 0.25);
        away.multiply(strength);
        away.setY(vertical);
        player.setVelocity(away);
    }

    // The red glass is sent only to the combat-tagged player; no real world blocks are changed.
    private void showRedGlassBarrier(Player player, Location attemptedTo) {
        if (attemptedTo.getWorld() == null) return;
        int height = Math.max(1, Math.min(8, safezoneSettings.getInt("barrier.height", 8)));
        int durationTicks = Math.max(1, safezoneSettings.getInt("barrier.duration-ticks", 20));
        int x = attemptedTo.getBlockX();
        int z = attemptedTo.getBlockZ();
        int baseY = attemptedTo.getBlockY();
        Material glass = Material.RED_STAINED_GLASS;
        for (int offset = 0; offset < height; offset++) {
            Location blockLocation = new Location(attemptedTo.getWorld(), x, baseY + offset, z);
            player.sendBlockChange(blockLocation, glass.createBlockData());
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || player.getWorld() != attemptedTo.getWorld()) return;
            for (int offset = 0; offset < height; offset++) {
                Location blockLocation = new Location(attemptedTo.getWorld(), x, baseY + offset, z);
                player.sendBlockChange(blockLocation, blockLocation.getBlock().getBlockData());
            }
        }, durationTicks);
    }

    private void initializeWorldGuardHook() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null
                || !Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            getLogger().info("WorldGuard not found; safe-region protection is inactive.");
            return;
        }
        try {
            Class<?> hookClass = Class.forName("com.donixcombat.WorldGuardHook", true, getClassLoader());
            regionHook = hookClass.getConstructor().newInstance();
            regionCheck = hookClass.getMethod("isSafeRegion", Location.class, Set.class);
            getLogger().info("WorldGuard integration enabled.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            regionHook = null;
            regionCheck = null;
            getLogger().warning("WorldGuard integration could not start; safe-region protection is disabled: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private boolean isSafeRegion(Location location) {
        if (regionHook == null || regionCheck == null || location.getWorld() == null) return false;
        try {
            return Boolean.TRUE.equals(regionCheck.invoke(regionHook, location, safeRegions));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            getLogger().fine("WorldGuard region check failed safely: " + exception.getMessage());
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        if (!featureEnabled) return;
        if (!event.isFlying() || !isInCombat(event.getPlayer())) return;
        event.setCancelled(true);
        if (event.getPlayer().isFlying()) event.getPlayer().setFlying(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // Death ends combat immediately; do not carry the tag through respawn.
        combatUntil.remove(player.getUniqueId());
        player.sendActionBar("");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Check the timer before clearing it: combat logouts are treated as deaths.
        if (featureEnabled && combatSettings.getBoolean("kill-on-logout", true)
                && isInCombat(player) && player.getHealth() > 0.0) {
            player.setHealth(0.0);
        }
        combatUntil.remove(player.getUniqueId());
        crystalAttackers.values().removeIf(stamp -> stamp.attacker.equals(player.getUniqueId()));
        anchorAttackers.values().removeIf(stamp -> stamp.attacker.equals(player.getUniqueId()));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("donixcombat")) return false;
        if (!(sender instanceof Player) && args.length == 0) {
            sender.sendMessage("Usage: /donixcombat reload");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage(color(messagesSettings.getString("reload", "&aDonixCombat configuration reloaded.")));
            return true;
        }
        sender.sendMessage(color("&cUsage: /donixcombat reload"));
        return true;
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private record LocationKey(UUID world, int x, int y, int z) {
        static LocationKey of(Location location) {
            return new LocationKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    private record AttackStamp(UUID attacker, long timestamp) {}
}
