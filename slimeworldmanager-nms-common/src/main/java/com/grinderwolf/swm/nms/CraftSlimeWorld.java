package com.grinderwolf.swm.nms;

import com.flowpowered.nbt.*;
import com.flowpowered.nbt.stream.NBTInputStream;
import com.flowpowered.nbt.stream.NBTOutputStream;
import com.github.luben.zstd.Zstd;
import com.grinderwolf.swm.api.exceptions.WorldAlreadyExistsException;
import com.grinderwolf.swm.api.loaders.SlimeLoader;
import com.grinderwolf.swm.api.utils.SlimeFormat;
import com.grinderwolf.swm.api.world.*;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Difficulty;

import java.io.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
@AllArgsConstructor
public class CraftSlimeWorld implements SlimeWorld {

    private static final int CHUNK_DATA_SEGMENTED_MARKER = -1;

    private static final int CHUNK_SEGMENT_TARGET_RAW_BYTES = 64 * 1024 * 1024;

    private SlimeLoader loader;
    private final String name;
    private final Map<Long, SlimeChunk> chunks;
    private final CompoundTag extraData;
    private final List<CompoundTag> worldMaps;
    private byte version;
    private final SlimePropertyMap propertyMap;
    private final boolean readOnly;
    private final boolean locked;

    @Override
    public SlimeChunk getChunk(int x, int z) {
        synchronized (chunks) {
            long index = ((long) z) * Integer.MAX_VALUE + (long) x;
            return chunks.get(index);
        }
    }

    public void updateChunk(SlimeChunk chunk) {
        if (!chunk.getWorldName().equals(getName())) {
            throw new IllegalArgumentException("Chunk (" + chunk.getX() + ", " + chunk.getZ() + ") belongs to world '"
                    + chunk.getWorldName() + "', not to '" + getName() + "'!");
        }
        synchronized (chunks) {
            chunks.put(((long) chunk.getZ()) * Integer.MAX_VALUE + (long) chunk.getX(), chunk);
        }
    }

    @Override
    public SlimeWorld clone(String worldName) {
        try {
            return clone(worldName, null);
        } catch (WorldAlreadyExistsException | IOException ignored) {
            return null;
        }
    }

    @Override
    public SlimeWorld clone(String worldName, SlimeLoader loader) throws WorldAlreadyExistsException, IOException {
        return clone(worldName, loader, true);
    }

    @Override
    public SlimeWorld clone(String worldName, SlimeLoader loader, boolean lock) throws WorldAlreadyExistsException, IOException {
        if (name.equals(worldName)) throw new IllegalArgumentException("The clone world cannot have the same name as the original world!");
        if (worldName == null) throw new IllegalArgumentException("The world name cannot be null!");
        if (loader != null && loader.worldExists(worldName)) throw new WorldAlreadyExistsException(worldName);

        CraftSlimeWorld world;
        synchronized (chunks) {
            world = new CraftSlimeWorld(
                    loader == null ? this.loader : loader,
                    worldName,
                    new HashMap<>(chunks),
                    extraData.clone(),
                    new ArrayList<>(worldMaps),
                    version,
                    propertyMap,
                    loader == null,
                    lock
            );
        }

        if (loader != null) {
            loader.saveWorld(worldName, world.serialize(), lock);
        }

        return world;
    }

    @Override
    public SlimeWorld.SlimeProperties getProperties() {
        return SlimeWorld.SlimeProperties.builder()
                .spawnX(propertyMap.getInt(com.grinderwolf.swm.api.world.properties.SlimeProperties.SPAWN_X))
                .spawnY(propertyMap.getInt(com.grinderwolf.swm.api.world.properties.SlimeProperties.SPAWN_Y))
                .spawnZ(propertyMap.getInt(com.grinderwolf.swm.api.world.properties.SlimeProperties.SPAWN_Z))
                .environment(propertyMap.getString(com.grinderwolf.swm.api.world.properties.SlimeProperties.ENVIRONMENT))
                .pvp(propertyMap.getBoolean(com.grinderwolf.swm.api.world.properties.SlimeProperties.PVP))
                .allowMonsters(propertyMap.getBoolean(com.grinderwolf.swm.api.world.properties.SlimeProperties.ALLOW_MONSTERS))
                .allowAnimals(propertyMap.getBoolean(com.grinderwolf.swm.api.world.properties.SlimeProperties.ALLOW_ANIMALS))
                .difficulty(Difficulty.valueOf(
                        propertyMap.getString(com.grinderwolf.swm.api.world.properties.SlimeProperties.DIFFICULTY).toUpperCase()
                ).getValue())
                .readOnly(readOnly)
                .build();
    }

    public byte[] serialize() {
        final List<SlimeChunk> sortedChunks;
        synchronized (chunks) {
            sortedChunks = new ArrayList<>(chunks.values());
        }

        sortedChunks.sort(Comparator.comparingLong(c -> (long) c.getZ() * Integer.MAX_VALUE + (long) c.getX()));

        for (SlimeChunk c : sortedChunks) {
            if (c != null) compactEmptySectionsInPlace(c, version);
        }
        sortedChunks.removeIf(chunk -> chunk == null || Arrays.stream(chunk.getSections()).allMatch(Objects::isNull));

        extraData.getValue().put("properties", propertyMap.toCompound());

        ByteArrayOutputStream outByteStream = new ByteArrayOutputStream();
        DataOutputStream outStream = new DataOutputStream(outByteStream);

        try {
            // Header + Slime version
            outStream.write(SlimeFormat.SLIME_HEADER);
            outStream.write(SlimeFormat.SLIME_VERSION);

            // World version
            outStream.writeByte(version);

            // Lowest chunk coordinates
            int minX = sortedChunks.stream().mapToInt(SlimeChunk::getX).min().orElse(0);
            int minZ = sortedChunks.stream().mapToInt(SlimeChunk::getZ).min().orElse(0);
            int maxX = sortedChunks.stream().mapToInt(SlimeChunk::getX).max().orElse(0);
            int maxZ = sortedChunks.stream().mapToInt(SlimeChunk::getZ).max().orElse(0);

            outStream.writeShort(minX);
            outStream.writeShort(minZ);

            int width = maxX - minX + 1;
            int depth = maxZ - minZ + 1;

            outStream.writeShort(width);
            outStream.writeShort(depth);

            // Chunk bitmask
            BitSet chunkBitset = new BitSet(width * depth);
            for (SlimeChunk chunk : sortedChunks) {
                int bitsetIndex = (chunk.getZ() - minZ) * width + (chunk.getX() - minX);
                chunkBitset.set(bitsetIndex, true);
            }

            int chunkMaskSize = (int) Math.ceil((width * depth) / 8.0D);
            writeBitSetAsBytes(outStream, chunkBitset, chunkMaskSize);

            // Segmented chunk data
            writeChunkDataSegmented(outStream, sortedChunks, version);

            // Tile Entities
            List<CompoundTag> tileEntitiesList = sortedChunks.stream()
                    .flatMap(chunk -> chunk.getTileEntities().stream())
                    .collect(Collectors.toList());
            ListTag<CompoundTag> tileEntitiesNbtList = new ListTag<>("tiles", TagType.TAG_COMPOUND, tileEntitiesList);
            CompoundTag tileEntitiesCompound = new CompoundTag("", new CompoundMap(Collections.singletonList(tileEntitiesNbtList)));
            byte[] tileEntitiesData = serializeCompoundTag(tileEntitiesCompound);
            byte[] compressedTileEntitiesData = Zstd.compress(tileEntitiesData);

            outStream.writeInt(compressedTileEntitiesData.length);
            outStream.writeInt(tileEntitiesData.length);
            outStream.write(compressedTileEntitiesData);

            // Entities (single blob)
            List<CompoundTag> entitiesList = sortedChunks.stream()
                    .flatMap(chunk -> chunk.getEntities().stream())
                    .collect(Collectors.toList());

            outStream.writeBoolean(!entitiesList.isEmpty());

            if (!entitiesList.isEmpty()) {
                ListTag<CompoundTag> entitiesNbtList = new ListTag<>("entities", TagType.TAG_COMPOUND, entitiesList);
                CompoundTag entitiesCompound = new CompoundTag("", new CompoundMap(Collections.singletonList(entitiesNbtList)));
                byte[] entitiesData = serializeCompoundTag(entitiesCompound);
                byte[] compressedEntitiesData = Zstd.compress(entitiesData);

                outStream.writeInt(compressedEntitiesData.length);
                outStream.writeInt(entitiesData.length);
                outStream.write(compressedEntitiesData);
            }

            // Extra Tag
            byte[] extra = serializeCompoundTag(extraData);
            byte[] compressedExtra = Zstd.compress(extra);

            outStream.writeInt(compressedExtra.length);
            outStream.writeInt(extra.length);
            outStream.write(compressedExtra);

            // World Maps
            CompoundMap map = new CompoundMap();
            map.put("maps", new ListTag<>("maps", TagType.TAG_COMPOUND, worldMaps));
            CompoundTag mapsCompound = new CompoundTag("", map);

            byte[] mapArray = serializeCompoundTag(mapsCompound);
            byte[] compressedMapArray = Zstd.compress(mapArray);

            outStream.writeInt(compressedMapArray.length);
            outStream.writeInt(mapArray.length);
            outStream.write(compressedMapArray);

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return outByteStream.toByteArray();
    }

    private static void writeBitSetAsBytes(DataOutputStream outStream, BitSet set, int fixedSize) throws IOException {
        byte[] array = set.toByteArray();
        outStream.write(array);

        int padding = fixedSize - array.length;
        for (int i = 0; i < padding; i++) {
            outStream.write(0);
        }
    }

    private static void compactEmptySectionsInPlace(SlimeChunk chunk, byte worldVersion) {
        if (worldVersion >= 0x04) return;

        SlimeChunkSection[] sections = chunk.getSections();
        if (sections == null) return;

        for (int i = 0; i < sections.length; i++) {
            SlimeChunkSection s = sections[i];
            if (s == null) continue;

            boolean blocksEmpty = isAllZero(s.getBlocks());
            boolean dataEmpty = (s.getData() == null) || isAllZero(s.getData().getBacking());
            boolean blEmpty = (s.getBlockLight() == null) || isAllZero(s.getBlockLight().getBacking());
            boolean slEmpty = (s.getSkyLight() == null) || isAllZero(s.getSkyLight().getBacking());

            if (blocksEmpty && dataEmpty && blEmpty && slEmpty) {
                sections[i] = null;
            }
        }
    }

    private static boolean isAllZero(byte[] arr) {
        if (arr == null) return true;
        for (byte b : arr) if (b != 0) return false;
        return true;
    }

    private static final class Segment {
        final byte[] compressed;
        final int rawLen;

        Segment(byte[] compressed, int rawLen) {
            this.compressed = compressed;
            this.rawLen = rawLen;
        }
    }

    private static void writeChunkDataSegmented(DataOutputStream outStream, List<SlimeChunk> chunks, byte worldVersion) throws IOException {
        List<Segment> segments = new ArrayList<>();
        long totalRaw = 0L;

        ByteArrayOutputStream segBaos = new ByteArrayOutputStream(Math.min(CHUNK_SEGMENT_TARGET_RAW_BYTES, 8 * 1024 * 1024));
        CountingOutputStream counter = new CountingOutputStream(segBaos);
        DataOutputStream segOut = new DataOutputStream(counter);

        int chunksInSeg = 0;

        for (SlimeChunk chunk : chunks) {
            writeSingleChunk(segOut, chunk, worldVersion);
            chunksInSeg++;

            if (counter.getCount() >= CHUNK_SEGMENT_TARGET_RAW_BYTES && chunksInSeg > 0) {
                Segment s = finalizeSegment(segBaos, (int) counter.getCount());
                segments.add(s);
                totalRaw += s.rawLen;

                segBaos.reset();
                counter.reset();
                chunksInSeg = 0;
            }
        }

        if (counter.getCount() > 0) {
            Segment s = finalizeSegment(segBaos, (int) counter.getCount());
            segments.add(s);
            totalRaw += s.rawLen;
        }

        outStream.writeInt(CHUNK_DATA_SEGMENTED_MARKER);
        outStream.writeInt(segments.size());
        outStream.writeLong(totalRaw);

        for (Segment s : segments) {
            outStream.writeInt(s.compressed.length);
            outStream.writeInt(s.rawLen);
            outStream.write(s.compressed);
        }
    }

    private static Segment finalizeSegment(ByteArrayOutputStream segBaos, int rawLen) {
        byte[] raw = segBaos.toByteArray();
        byte[] compressed = Zstd.compress(raw);
        return new Segment(compressed, rawLen);
    }

    private static void writeSingleChunk(DataOutputStream outStream, SlimeChunk chunk, byte worldVersion) throws IOException {
        // Height Maps
        if (worldVersion >= 0x04) {
            byte[] heightMaps = serializeCompoundTag(chunk.getHeightMaps());
            outStream.writeInt(heightMaps.length);
            outStream.write(heightMaps);
        } else {
            int[] heightMap = chunk.getHeightMaps().getIntArrayValue("heightMap").get();
            for (int i = 0; i < 256; i++) outStream.writeInt(heightMap[i]);
        }

        // Biomes
        int[] biomes = chunk.getBiomes();
        if (worldVersion >= 0x04) outStream.writeInt(biomes.length);
        for (int biome : biomes) outStream.writeInt(biome);

        // Sections bitmask
        SlimeChunkSection[] sections = chunk.getSections();
        BitSet sectionBitmask = new BitSet(16);
        for (int i = 0; i < sections.length; i++) sectionBitmask.set(i, sections[i] != null);
        writeBitSetAsBytes(outStream, sectionBitmask, 2);

        // Sections data
        for (SlimeChunkSection section : sections) {
            if (section == null) continue;

            boolean hasBlockLight = section.getBlockLight() != null;
            outStream.writeBoolean(hasBlockLight);
            if (hasBlockLight) outStream.write(section.getBlockLight().getBacking());

            if (worldVersion >= 0x04) {
                List<CompoundTag> palette = section.getPalette().getValue();
                outStream.writeInt(palette.size());
                for (CompoundTag value : palette) {
                    byte[] serializedValue = serializeCompoundTag(value);
                    outStream.writeInt(serializedValue.length);
                    outStream.write(serializedValue);
                }

                long[] blockStates = section.getBlockStates();
                outStream.writeInt(blockStates.length);
                for (long v : blockStates) outStream.writeLong(v);
            } else {
                outStream.write(section.getBlocks());
                outStream.write(section.getData().getBacking());
            }

            boolean hasSkyLight = section.getSkyLight() != null;
            outStream.writeBoolean(hasSkyLight);
            if (hasSkyLight) outStream.write(section.getSkyLight().getBacking());
        }
    }

    private static byte[] serializeCompoundTag(CompoundTag tag) throws IOException {
        if (tag == null || tag.getValue().isEmpty()) return new byte[0];

        ByteArrayOutputStream outByteStream = new ByteArrayOutputStream();
        NBTOutputStream outStream = new NBTOutputStream(outByteStream, NBTInputStream.NO_COMPRESSION, ByteOrder.BIG_ENDIAN);
        outStream.writeTag(tag);
        outStream.close();

        return outByteStream.toByteArray();
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        long getCount() {
            return count;
        }

        void reset() {
            count = 0L;
        }

        @Override public void write(int b) throws IOException { delegate.write(b); count++; }
        @Override public void write(byte[] b) throws IOException { delegate.write(b); count += b.length; }
        @Override public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); count += len; }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
