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
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.util.Entities;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.util.Locations;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.List;

public class WorldGuardVehicleListener extends AbstractListener {

    public WorldGuardVehicleListener(WorldGuardPlugin plugin) {
        super(plugin);
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();
        if (vehicle.getPassengers().isEmpty()) return;

        org.bukkit.Location from = event.getFrom();
        org.bukkit.Location to = event.getTo();

        Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
            List<Player> playerPassengers = vehicle.getPassengers().stream()
                    .filter(ent -> ent instanceof Player).map(ent -> (Player) ent).toList();

            if (playerPassengers.isEmpty()) {
                return;
            }

            World world = vehicle.getWorld();
            WorldConfiguration wcfg = getWorldConfig(world);
            if (wcfg.isEventDisabled(event.getEventName())) return;

            if (wcfg.useRegions) {
                if (Locations.isDifferentBlock(BukkitAdapter.adapt(from), BukkitAdapter.adapt(to))) {
                    for (Player player : playerPassengers) {
                        if (Entities.isNPC(player)) continue;
                        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);

                        Location lastValid = WorldGuard.getInstance().getPlatform().getSessionManager()
                                .get(localPlayer).testMoveTo(localPlayer, BukkitAdapter.adapt(to), MoveType.RIDE);

                        if (lastValid != null) {
                            if (getPlugin().isFolia()) {
                                vehicle.setVelocity(new Vector(0, 0, 0));
                                vehicle.teleportAsync(from);

                                if (Locations.isDifferentBlock(lastValid, BukkitAdapter.adapt(from))) {
                                    Vector dir = player.getLocation().getDirection();
                                    player.teleportAsync(BukkitAdapter.adapt(lastValid).setDirection(dir));
                                }
                            } else {
                                Bukkit.getScheduler().runTask(getPlugin(), () -> {
                                    vehicle.setVelocity(new Vector(0, 0, 0));
                                    vehicle.teleportAsync(from);

                                    if (Locations.isDifferentBlock(lastValid, BukkitAdapter.adapt(from))) {
                                        Vector dir = player.getLocation().getDirection();
                                        player.teleportAsync(BukkitAdapter.adapt(lastValid).setDirection(dir));
                                    }
                                });
                            }
                            return;
                        }
                    }
                }
            }
        });
    }
}
