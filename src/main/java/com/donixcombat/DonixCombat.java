package com.donixcombat;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DonixCombat extends JavaPlugin implements Listener {
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    private final Map<LocationKey, AttackStamp> crystalAttackers = new ConcurrentHashMap<>();
    private final Map<LocationKey, AttackStamp> anchorAttackers = new ConcurrentHashMap<>();
    private final Set<String> allowedCommands = new HashSet<>();
    private final Set<String> safeRegions = new HashSet<>();
    private RegionQuery regionQuery;
    private BukkitTask ticker;
    private long combatMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            regionQuery = container.createQuery();
        } else {
            getLogger().warning("WorldGuard is not installed; safe-region protection is disabled.");
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 0L, 20L);
        getLogger().info("DonixCombat v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        combatUntil.clear();
        crystalAttackers.clear();
        anchorAttackers.clear();
    }

    private void loadSettings() {
        reloadConfig();
        combatMillis = Math.max(1, getConfig().getInt("combat.duration-seconds", 20)) * 1000L;
        allowedCommands.clear();
        for (String command : getConfig().getStringList("commands.allowed")) {
            allowedCommands.add(command.toLowerCase(Locale.ROOT));
        }
        safeRegions.clear();
        for (String region : getConfig().getStringList("safe-regions")) {
            safeRegions.add(region.toLowerCase(Locale.ROOT));
        }
    }

    public boolean isInCombat(Player player) {
        Long until = combatUntil.get(player.getUniqueId());
        if (until == null) return false;
        if (until <= System.currentTimeMillis()) {
            combatUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void tag(Player player) {
        if (!player.isOnline()) return;
        long now = System.currentTimeMillis();
        boolean alreadyTagged = combatUntil.containsKey(player.getUniqueId())
                && combatUntil.get(player.getUniqueId()) > now;
        combatUntil.put(player.getUniqueId(), now + combatMillis);

        if (getConfig().getBoolean("combat.disable-flight", true)) {
            if (player.isFlying()) player.setFlying(false);
            if (player.getAllowFlight()) player.setAllowFlight(false);
        }
        if (!alreadyTagged) {
            String message = getConfig().getString("combat.start-message", "&cYou are now in combat for &f%time% seconds&c!");
            player.sendMessage(color(message.replace("%time%", String.valueOf(combatMillis / 1000L))));
        }
    }

    private void tagPair(Player first, Player second) {
        if (first == null || second == null || first.equals(second)) return;
        tag(first);
        tag(second);
    }

    private void tickCombat() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long until = combatUntil.get(player.getUniqueId());
            if (until == null) continue;
            long remaining = until - now;
            if (remaining <= 0) {
                combatUntil.remove(player.getUniqueId());
                player.sendMessage(color(getConfig().getString("combat.end-message", "&aYou are no longer in combat.")));
                player.sendActionBar("");
                continue;
            }
            long seconds = (remaining + 999L) / 1000L;
            String bar = getConfig().getString("combat.actionbar", "&cCombat &7» &f%time%s");
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
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker != null) tagPair(attacker, victim);
        if (event.getDamager() instanceof EnderCrystal crystal && crystal.getLocation() != null) {
            Player source = crystalAttackers.get(LocationKey.of(crystal.getLocation()));
            if (source != null) tagExplosionPlayers(source, crystal.getLocation(), 12.0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker != null) crystalAttackers.put(LocationKey.of(crystal.getLocation()), new AttackStamp(attacker.getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        AttackStamp stamp = crystalAttackers.remove(LocationKey.of(crystal.getLocation()));
        Player attacker = stamp == null ? null : Bukkit.getPlayer(stamp.attacker);
        if (attacker != null) tagExplosionPlayers(attacker, crystal.getLocation(), 12.0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnchorInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType().name().equals("RESPAWN_ANCHOR") == false) return;
        Location location = block.getLocation();
        anchorAttackers.put(LocationKey.of(location), new AttackStamp(event.getPlayer().getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnchorExplode(BlockExplodeEvent event) {
        AttackStamp stamp = anchorAttackers.remove(LocationKey.of(event.getBlock().getLocation()));
        Player attacker = stamp == null ? null : Bukkit.getPlayer(stamp.attacker);
        if (attacker != null) tagExplosionPlayers(attacker, event.getBlock().getLocation(), 12.0);
    }

    private void tagExplosionPlayers(Player attacker, Location center, double radius) {
        if (attacker == null || !attacker.isOnline()) return;
        tag(attacker);
        double squared = radius * radius;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof Player victim && victim.getLocation().distanceSquared(center) <= squared) {
                tag(victim);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isInCombat(player)) return;
        String message = event.getMessage().substring(1).trim();
        if (message.isEmpty()) return;
        String root = message.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.lastIndexOf(':') + 1);
        if (allowedCommands.contains(root)) return;
        event.setCancelled(true);
        player.sendMessage(color(getConfig().getString("commands.blocked-message", "&cYou cannot use that command while in combat!")));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !isInCombat(event.getPlayer()) || regionQuery == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == to.getWorld() && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        if (isSafeRegion(to)) {
            event.setTo(from);
            event.getPlayer().sendActionBar(color(getConfig().getString("messages.safezone-entry", "&cYou cannot enter a safezone while in combat!")));
        }
    }

    private boolean isSafeRegion(Location location) {
        if (regionQuery == null || location.getWorld() == null) return false;
        try {
            var applicable = regionQuery.getApplicableRegions(BukkitAdapter.adapt(location));
            for (var region : applicable.getRegions()) {
                if (safeRegions.contains(region.getId().toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Exception exception) {
            getLogger().fine("Could not query WorldGuard region at " + location + ": " + exception.getMessage());
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        combatUntil.remove(event.getPlayer().getUniqueId());
        crystalAttackers.values().removeIf(id -> id.equals(event.getPlayer().getUniqueId()));
        anchorAttackers.values().removeIf(id -> id.equals(event.getPlayer().getUniqueId()));
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
            sender.sendMessage(color(getConfig().getString("messages.reload", "&aDonixCombat configuration reloaded.")));
            return true;
        }
        sender.sendMessage(color("&cUsage: /donixcombat reload"));
        return true;
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private record LocationKey(UUID world, int x, int y, int z) {\n        static LocationKey of(Location location) {\n            return new LocationKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());\n        }\n    }\n\n    private record AttackStamp(UUID attacker, long timestamp) {}
}
