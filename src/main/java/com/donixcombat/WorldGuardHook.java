package com.donixcombat;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import org.bukkit.Location;

import java.util.Locale;
import java.util.Set;

/**
 * Loaded reflectively so DonixCombat itself has no hard class-loading dependency on WorldGuard.
 */
public final class WorldGuardHook {
    public WorldGuardHook() {
    }

    public boolean isSafeRegion(Location location, Set<String> safeRegions) {
        if (location == null || location.getWorld() == null || safeRegions == null || safeRegions.isEmpty()) {
            return false;
        }
        try {
            var container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            var query = container.createQuery();
            var applicable = query.getApplicableRegions(BukkitAdapter.adapt(location));
            for (var region : applicable.getRegions()) {
                if (safeRegions.contains(region.getId().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            return false;
        }
        return false;
    }
}
