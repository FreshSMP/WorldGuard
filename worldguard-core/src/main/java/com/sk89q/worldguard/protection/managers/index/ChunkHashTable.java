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

package com.sk89q.worldguard.protection.managers.index;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionDifference;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.util.RegionCollectionConsumer;
import com.sk89q.worldguard.util.concurrent.EvenMoreExecutors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

/**
 * Maintains a hash table for each chunk containing a list of regions that
 * are contained within that chunk, allowing for fast spatial lookup.
 */
public class ChunkHashTable implements ConcurrentRegionIndex {

    private final String name;
    private final AtomicReference<ListeningExecutorService> executorRef;
    private final ConcurrentHashMap<Long, ChunkState> states = new ConcurrentHashMap<>();
    private final RegionIndex index;
    private final AtomicReference<ChunkState> lastState = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong(0);

    /**
     * Create a new instance.
     *
     * @param index the index
     * @param name
     */
    public ChunkHashTable(RegionIndex index, String name) {
        checkNotNull(index);
        this.index = index;
        this.name = name;
        this.executorRef = new AtomicReference<>(createExecutor());
    }

    /**
     * Create an executor.
     *
     * @return an executor service
     */
    private ListeningExecutorService createExecutor() {
        return MoreExecutors.listeningDecorator(EvenMoreExecutors.newBoundedCachedThreadPool(0, 4, Integer.MAX_VALUE,
                "WorldGuard Region Chunk Table - " + name));
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static long chunkKey(BlockVector2 position) {
        return chunkKey(position.x(), position.z());
    }

    /**
     * Get a state object at the given position.
     *
     * @param position the position
     * @param create true to create an entry if one does not exist
     * @return a chunk state object, or {@code null} (only if {@code create} is false)
     */
    @Nullable
    private ChunkState get(BlockVector2 position, boolean create) {
        long key = chunkKey(position);
        ChunkState state = states.get(key);

        if (state != null || !create) {
            return state;
        }

        long currentGen = generation.get();
        ChunkState newState = new ChunkState(position, currentGen);
        state = states.putIfAbsent(key, newState);

        if (state == null) {
            state = newState;
            executorRef.get().submit(new EnumerateRegions(position, currentGen));
        }

        return state;
    }

    /**
     * Get a state at the given position or create a new entry if one does
     * not exist.
     *
     * @param position the position
     * @return a state
     */
    private ChunkState getOrCreate(BlockVector2 position) {
        return get(position, true);
    }

    /**
     * Clear the current hash table and rebuild it in the background.
     */
    private void rebuild() {
        long newGen = generation.incrementAndGet();

        ListeningExecutorService previousExecutor = executorRef.getAndSet(createExecutor());
        previousExecutor.shutdownNow();

        lastState.set(null);

        List<BlockVector2> positions = new ArrayList<>();
        for (ChunkState state : states.values()) {
            positions.add(state.getPosition());
        }

        states.clear();

        for (BlockVector2 position : positions) {
            states.put(chunkKey(position), new ChunkState(position, newGen));
        }

        if (!positions.isEmpty()) {
            executorRef.get().submit(new EnumerateRegions(positions, newGen));
        }
    }

    /**
     * Waits until all currently executing background tasks complete.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     *         {@code false} if the timeout elapsed before termination
     * @throws InterruptedException on interruption
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        ListeningExecutorService previousExecutor = executorRef.getAndSet(createExecutor());
        previousExecutor.shutdown();
        return previousExecutor.awaitTermination(timeout, unit);
    }

    @Override
    public void bias(BlockVector2 chunkPosition) {
        checkNotNull(chunkPosition);
        getOrCreate(chunkPosition);
    }

    @Override
    public void biasAll(Collection<BlockVector2> chunkPositions) {
        for (BlockVector2 position : chunkPositions) {
            bias(position);
        }
    }

    @Override
    public void forget(BlockVector2 chunkPosition) {
        checkNotNull(chunkPosition);
        states.remove(chunkKey(chunkPosition));

        ChunkState state = lastState.get();
        if (state != null && state.getPosition().x() == chunkPosition.x() && state.getPosition().z() == chunkPosition.z()) {
            lastState.compareAndSet(state, null);
        }
    }

    @Override
    public void forgetAll() {
        ListeningExecutorService previousExecutor = executorRef.getAndSet(createExecutor());
        previousExecutor.shutdownNow();
        states.clear();
        lastState.set(null);
    }

    @Override
    public void add(ProtectedRegion region) {
        index.add(region);
        rebuild();
    }

    @Override
    public void addAll(Collection<ProtectedRegion> regions) {
        index.addAll(regions);
        rebuild();
    }

    @Override
    public Set<ProtectedRegion> remove(String id, RemovalStrategy strategy) {
        Set<ProtectedRegion> removed = index.remove(id, strategy);
        rebuild();
        return removed;
    }

    @Override
    public boolean contains(String id) {
        return index.contains(id);
    }

    @Nullable
    @Override
    public ProtectedRegion get(String id) {
        return index.get(id);
    }

    @Override
    public void apply(Predicate<ProtectedRegion> consumer) {
        index.apply(consumer);
    }

    @Override
    public void applyContaining(BlockVector3 position, Predicate<ProtectedRegion> consumer) {
        checkNotNull(position);
        checkNotNull(consumer);

        int chunkX = position.x() >> 4;
        int chunkZ = position.z() >> 4;

        ChunkState state = lastState.get();
        if (state == null || state.getPosition().x() != chunkX || state.getPosition().z() != chunkZ) {
            state = get(BlockVector2.at(chunkX, chunkZ), false);
            if (state != null) {
                lastState.set(state);
            }
        }

        if (state != null && state.isLoaded()) {
            List<ProtectedRegion> regions = state.getRegions();
            for (ProtectedRegion region : regions) {
                if (region.contains(position)) {
                    consumer.test(region);
                }
            }
        } else {
            index.applyContaining(position, consumer);
        }
    }

    @Override
    public void applyIntersecting(ProtectedRegion region, Predicate<ProtectedRegion> consumer) {
        index.applyIntersecting(region, consumer);
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public RegionDifference getAndClearDifference() {
        return index.getAndClearDifference();
    }

    @Override
    public void setDirty(RegionDifference difference) {
        index.setDirty(difference);
    }

    @Override
    public Collection<ProtectedRegion> values() {
        return index.values();
    }

    @Override
    public boolean isDirty() {
        return index.isDirty();
    }

    @Override
    public void setDirty(boolean dirty) {
        index.setDirty(dirty);
    }

    /**
     * A task to enumerate the regions for a list of provided chunks.
     */
    private class EnumerateRegions implements Runnable {
        private final List<BlockVector2> positions;
        private final long expectedGeneration;

        private EnumerateRegions(BlockVector2 position, long generation) {
            this(Arrays.asList(checkNotNull(position)), generation);
        }

        private EnumerateRegions(List<BlockVector2> positions, long generation) {
            checkNotNull(positions);
            checkArgument(!positions.isEmpty(), "List of positions can't be empty");
            this.positions = positions;
            this.expectedGeneration = generation;
        }

        @Override
        public void run() {
            for (BlockVector2 position : positions) {
                if (generation.get() != expectedGeneration) {
                    return;
                }

                ChunkState state = states.get(chunkKey(position));
                if (state != null && state.getGeneration() == expectedGeneration) {
                    List<ProtectedRegion> regions = new ArrayList<>();
                    ProtectedRegion chunkRegion = new ProtectedCuboidRegion(
                            "_",
                            position.multiply(16).toBlockVector3(Integer.MIN_VALUE),
                            position.add(1, 1).multiply(16).toBlockVector3(Integer.MAX_VALUE));
                    index.applyIntersecting(chunkRegion, new RegionCollectionConsumer(regions, false));
                    Collections.sort(regions);

                    state.setRegions(Collections.unmodifiableList(regions));

                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Stores a cache of region data for a chunk.
     */
    private static class ChunkState {
        private final BlockVector2 position;
        private final long generation;
        private boolean loaded = false;
        private List<ProtectedRegion> regions = Collections.emptyList();

        private ChunkState(BlockVector2 position, long generation) {
            this.position = position;
            this.generation = generation;
        }

        public BlockVector2 getPosition() {
            return position;
        }

        public long getGeneration() {
            return generation;
        }

        public List<ProtectedRegion> getRegions() {
            return regions;
        }

        public void setRegions(List<ProtectedRegion> regions) {
            this.regions = regions;
            this.loaded = true;
        }

        public boolean isLoaded() {
            return loaded;
        }
    }

    /**
     * A factory for instances of {@code ChunkHashCache}.
     */
    public static class Factory implements Function<String, ChunkHashTable> {
        private final Function<String, ? extends ConcurrentRegionIndex> supplier;

        public Factory(Function<String, ? extends ConcurrentRegionIndex> supplier) {
            checkNotNull(supplier);
            this.supplier = supplier;
        }

        @Override
        public ChunkHashTable apply(String name) {
            return new ChunkHashTable(supplier.apply(name), name);
        }
    }

}
