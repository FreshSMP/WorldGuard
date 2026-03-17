/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit.listener;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerMoveListener extends AbstractListener implements Runnable {

    private final Map<UUID, Location> lastPlayerLocations;

    public PlayerMoveListener(WorldGuardPlugin plugin) {
        super(plugin);
        this.lastPlayerLocations = new HashMap<>();
    }

    @Override
    public void registerEvents() {
        if (WorldGuard.getInstance().getPlatform().getGlobalStateManager().usePlayerMove) {
            PluginManager pm = getPlugin().getServer().getPluginManager();
            pm.registerEvents(this, getPlugin());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (getWorldConfig(event.getPlayer().getWorld()).isEventDisabled(event.getEventName())) return;
        LocalPlayer player = getPlugin().wrapPlayer(event.getPlayer());

        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
        session.testMoveTo(player, BukkitAdapter.adapt(event.getRespawnLocation()), MoveType.RESPAWN, true);
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (getWorldConfig(event.getVehicle().getWorld()).isEventDisabled(event.getEventName())) return;
        Entity entity = event.getEntered();
        if (entity instanceof Player) {
            LocalPlayer player = getPlugin().wrapPlayer((Player) entity);
            Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
            if (null != session.testMoveTo(player, BukkitAdapter.adapt(event.getVehicle().getLocation()), MoveType.EMBARK, true)) {
                event.setCancelled(true);
            }
        }
    }

    @Override
    public void run() {
        Bukkit.getOnlinePlayers().forEach(player -> {
            Location from = lastPlayerLocations.getOrDefault(player.getUniqueId(), player.getLocation());
            Location to = player.getLocation().clone();

        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
          LocalPlayer localPlayer = getPlugin().wrapPlayer(player);

          Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);
          MoveType moveType = MoveType.MOVE;
            if (player.isGliding()) {
              moveType = MoveType.GLIDE;
            } else if (player.isSwimming()) {
              moveType = MoveType.SWIM;
            } else if (player.getVehicle() != null && player.getVehicle() instanceof AbstractHorse) {
              moveType = MoveType.RIDE;
            }

        com.sk89q.worldedit.util.Location weLocation = session.testMoveTo(localPlayer, BukkitAdapter.adapt(to), moveType);

        if (weLocation != null) {
            final Location override = BukkitAdapter.adapt(weLocation);
            override.setX(override.getBlockX() + 0.5);
            override.setY(override.getBlockY());
            override.setZ(override.getBlockZ() + 0.5);
            override.setPitch(to.getPitch());
            override.setYaw(to.getYaw());

            if (getPlugin().isFolia()) {
                player.teleportAsync(override.clone());
            } else {
                Bukkit.getScheduler().runTask(getPlugin(), () -> player.teleportAsync(override.clone()));
            }

            if (getPlugin().isFolia()) {
                Entity vehicle = player.getVehicle();
                if (vehicle != null) {
                    vehicle.eject();

                    Entity current = vehicle;
                    while (current != null) {
                        current.eject();
                        vehicle.setVelocity(new Vector(0, 0, 0));

                        if (vehicle instanceof LivingEntity) {
                            vehicle.teleportAsync(override.clone());
                        } else {
                            vehicle.teleportAsync(override.clone().add(0, 1, 0));
                        }
                        current = current.getVehicle();
                    }

                    player.teleportAsync(override.clone().add(0, 1, 0));
                }
            } else {
                Bukkit.getScheduler().runTask(getPlugin(), () -> {
                    Entity vehicle = player.getVehicle();
                    if (vehicle != null) {
                        vehicle.eject();

                        Entity current = vehicle;
                        while (current != null) {
                            current.eject();
                            vehicle.setVelocity(new Vector(0, 0, 0));

                            if (vehicle instanceof LivingEntity) {
                                vehicle.teleportAsync(override.clone());
                            } else {
                                vehicle.teleportAsync(override.clone().add(0, 1, 0));
                            }
                            current = current.getVehicle();
                        }

                        player.teleportAsync(override.clone().add(0, 1, 0));
                    }
                });
            }

                Location delayedDismountLocation = override.clone().add(0, 1, 0);
                if (getPlugin().isFolia()) {
                    player.getScheduler().runDelayed(getPlugin(), scheduledTask -> player.teleportAsync(delayedDismountLocation),
                        null, 1);
                } else {
                    Bukkit.getScheduler().runTaskLater(getPlugin(), () -> player.teleportAsync(delayedDismountLocation), 1);
                }
            }
        }

            lastPlayerLocations.put(player.getUniqueId(), to);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (getWorldConfig(event.getPlayer().getWorld()).isEventDisabled(event.getEventName())) return;
        final Player player = event.getPlayer();
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);

        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);
        com.sk89q.worldedit.util.Location loc = session.testMoveTo(localPlayer,
            BukkitAdapter.adapt(event.getPlayer().getLocation()), MoveType.OTHER_CANCELLABLE); // white lie
        if (loc != null) {
            player.teleportAsync(BukkitAdapter.adapt(loc));
        }

        session.uninitialize(localPlayer);

        lastPlayerLocations.remove(player.getUniqueId());
    }

    @EventHandler
    public void onEntityMount(EntityMountEvent event) {
        if (getWorldConfig(event.getEntity().getWorld()).isEventDisabled(event.getEventName())) return;
        Entity entity = event.getEntity();
        if (entity instanceof Player) {
            LocalPlayer player = getPlugin().wrapPlayer((Player) entity);
            Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
            if (null != session.testMoveTo(player, BukkitAdapter.adapt(event.getMount().getLocation()), MoveType.EMBARK, true)) {
                event.setCancelled(true);
            }
        }
    }
}
