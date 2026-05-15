package com.grinderwolf.swm.plugin.loaders;

import com.flowpowered.nbt.*;
import com.flowpowered.nbt.stream.NBTInputStream;
import com.github.luben.zstd.Zstd;
import com.grinderwolf.swm.api.exceptions.CorruptedWorldException;
import com.grinderwolf.swm.api.exceptions.NewerFormatException;
import com.grinderwolf.swm.api.loaders.SlimeLoader;
import com.grinderwolf.swm.api.utils.NibbleArray;
import com.grinderwolf.swm.api.utils.SlimeFormat;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeChunkSection;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import com.grinderwolf.swm.nms.CraftSlimeChunk;
import com.grinderwolf.swm.nms.CraftSlimeChunkSection;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import com.grinderwolf.swm.plugin.config.ConfigManager;
import com.grinderwolf.swm.plugin.config.DatasourcesConfig;
import com.grinderwolf.swm.plugin.loaders.file.FileLoader;
import com.grinderwolf.swm.plugin.loaders.mongo.MongoLoader;
import com.grinderwolf.swm.plugin.loaders.mysql.MysqlLoader;
import com.grinderwolf.swm.plugin.log.Logging;
import com.mongodb.MongoException;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.*;

public class LoaderUtils {

    public static final long MAX_LOCK_TIME = 300000L;
    public static final long LOCK_INTERVAL = 60000L;

    private static final int CHUNK_DATA_SEGMENTED_MARKER = -1;

    private static final Map<String, SlimeLoader> loaderMap = new HashMap<>();

    public static void registerLoaders() {
        DatasourcesConfig config = ConfigManager.getDatasourcesConfig();

        DatasourcesConfig.FileConfig fileConfig = config.getFileConfig();
        registerLoader("file", new FileLoader(new File(fileConfig.getPath())));

        DatasourcesConfig.MysqlConfig mysqlConfig = config.getMysqlConfig();
        if (mysqlConfig.isEnabled()) {
            try {
                registerLoader("mysql", new MysqlLoader(mysqlConfig));
            } catch (SQLException ex) {
                Logging.error("Failed to establish connection to the MySQL server:");
                ex.printStackTrace();
            }
        }

        DatasourcesConfig.MongoDBConfig mongoConfig = config.getMongoDbConfig();
        if (mongoConfig.isEnabled()) {
            try {
                registerLoader("mongodb", new MongoLoader(mongoConfig));
            } catch (MongoException ex) {
                Logging.error("Failed to establish connection to the MongoDB server:");
                ex.printStackTrace();
            }
        }
    }

    public static List<String> getAvailableLoadersNames() {
        return new LinkedList<>(loaderMap.keySet());
    }

    public static SlimeLoader getLoader(String dataSource) {
        return loaderMap.get(dataSource);
    }

    public static void registerLoader(String dataSource, SlimeLoader loader) {
        if (loaderMap.containsKey(dataSource)) {
            throw new IllegalArgumentException("Data source " + dataSource + " already has a declared loader!");
        }

        if (loader instanceof UpdatableLoader) {
            try {
                ((UpdatableLoader) loader).update();
            } catch (UpdatableLoader.NewerDatabaseException e) {
                Logging.error("Data source " + dataSource + " version is " + e.getDatabaseVersion() + ", while" +
                        " this SWM version only supports up to version " + e.getCurrentVersion() + ".");
                return;
            } catch (IOException ex) {
                Logging.error("Failed to check if data source " + dataSource + " is updated:");
                ex.printStackTrace();
                return;
            }
        }

        loaderMap.put(dataSource, loader);
    }

    public static CraftSlimeWorld deserializeWorld(
            SlimeLoader loader,
            String worldName,
            byte[] serializedWorld,
            SlimePropertyMap propertyMap,
            boolean readOnly
    ) throws IOException, CorruptedWorldException, NewerFormatException {

        DataInputStream dataStream = new DataInputStream(new ByteArrayInputStream(serializedWorld));

        try {
            // Header
            byte[] fileHeader = new byte[SlimeFormat.SLIME_HEADER.length];
            dataStream.readFully(fileHeader);
            if (!Arrays.equals(SlimeFormat.SLIME_HEADER, fileHeader)) {
                throw new CorruptedWorldException(worldName);
            }

            // File version
            byte version = dataStream.readByte();
            if (version > SlimeFormat.SLIME_VERSION) {
                throw new NewerFormatException(version);
            }

            // World version
            byte worldVersion;
            if (version >= 6) {
                worldVersion = dataStream.readByte();
            } else if (version >= 4) {
                worldVersion = (byte) (dataStream.readBoolean() ? 0x04 : 0x01);
            } else {
                worldVersion = 0;
            }

            // Chunk bounds
            short minX = dataStream.readShort();
            short minZ = dataStream.readShort();
            int width = dataStream.readShort();
            int depth = dataStream.readShort();

            if (width <= 0 || depth <= 0) {
                throw new CorruptedWorldException(worldName);
            }

            // Chunk bitmask
            int bitmaskSize = (int) Math.ceil((width * depth) / 8.0D);
            byte[] chunkBitmask = new byte[bitmaskSize];
            dataStream.readFully(chunkBitmask);
            BitSet chunkBitset = BitSet.valueOf(chunkBitmask);

            // ---- Chunk data (legacy or segmented) ----
            int first = dataStream.readInt();
            DataInputStream chunkDataStream;

            if (first != CHUNK_DATA_SEGMENTED_MARKER) {
                // Legacy: [int compressedLen][int rawLen][compressedBytes]
                int compressedChunkLen = first;
                int rawChunkLen = dataStream.readInt();

                if (compressedChunkLen < 0 || rawChunkLen < 0) {
                    throw new CorruptedWorldException(worldName);
                }

                byte[] compressedChunkData = new byte[compressedChunkLen];
                dataStream.readFully(compressedChunkData);

                byte[] chunkData = new byte[rawChunkLen];
                Zstd.decompress(chunkData, compressedChunkData);

                chunkDataStream = new DataInputStream(new ByteArrayInputStream(chunkData));
            } else {
                // Segmented:
                // [-1][int segmentCount][long totalRaw]
                // repeat: [int segCompressedLen][int segRawLen][segCompressedBytes]
                int segmentCount = dataStream.readInt();
                dataStream.readLong(); // totalRaw (alignment)

                if (segmentCount < 0) {
                    throw new CorruptedWorldException(worldName);
                }

                chunkDataStream = new DataInputStream(new SegmentedDecompressedInputStream(dataStream, segmentCount));
            }

            // Segmented chunk data streams from the main data stream.
            // Consume all chunk bytes before reading the secondary blobs.
            Map<Long, SlimeChunk> chunks = readChunks(worldVersion, version, worldName, minX, minZ, width, depth, chunkBitset, chunkDataStream);

            // ---- Tile Entities ----
            int compressedTileEntitiesLength = dataStream.readInt();
            int tileEntitiesLength = dataStream.readInt();
            if (compressedTileEntitiesLength < 0 || tileEntitiesLength < 0) {
                throw new CorruptedWorldException(worldName);
            }

            byte[] compressedTileEntities = new byte[compressedTileEntitiesLength];
            dataStream.readFully(compressedTileEntities);

            // ---- Entities ----
            byte[] compressedEntities = new byte[0];
            byte[] entities = new byte[0];

            if (version >= 3) {
                boolean hasEntities = dataStream.readBoolean();
                if (hasEntities) {
                    int compressedEntitiesLength = dataStream.readInt();
                    int entitiesLength = dataStream.readInt();
                    if (compressedEntitiesLength < 0 || entitiesLength < 0) {
                        throw new CorruptedWorldException(worldName);
                    }

                    compressedEntities = new byte[compressedEntitiesLength];
                    entities = new byte[entitiesLength];
                    dataStream.readFully(compressedEntities);
                }
            }

            // ---- Extra NBT ----
            byte[] compressedExtraTag = new byte[0];
            byte[] extraTag = new byte[0];

            if (version >= 2) {
                int compressedExtraTagLength = dataStream.readInt();
                int extraTagLength = dataStream.readInt();
                if (compressedExtraTagLength < 0 || extraTagLength < 0) {
                    throw new CorruptedWorldException(worldName);
                }

                compressedExtraTag = new byte[compressedExtraTagLength];
                extraTag = new byte[extraTagLength];
                dataStream.readFully(compressedExtraTag);
            }

            // ---- Maps ----
            byte[] compressedMapsTag = new byte[0];
            byte[] mapsTag = new byte[0];

            if (version >= 7) {
                int compressedMapsTagLength = dataStream.readInt();
                int mapsTagLength = dataStream.readInt();
                if (compressedMapsTagLength < 0 || mapsTagLength < 0) {
                    throw new CorruptedWorldException(worldName);
                }

                compressedMapsTag = new byte[compressedMapsTagLength];
                mapsTag = new byte[mapsTagLength];
                dataStream.readFully(compressedMapsTag);
            }

            // Ensure no trailing bytes
            if (dataStream.read() != -1) {
                throw new CorruptedWorldException(worldName);
            }

            // Decompress secondary blobs
            byte[] tileEntities = new byte[tileEntitiesLength];
            Zstd.decompress(tileEntities, compressedTileEntities);

            if (entities.length > 0 && compressedEntities.length > 0) {
                Zstd.decompress(entities, compressedEntities);
            }
            if (extraTag.length > 0 && compressedExtraTag.length > 0) {
                Zstd.decompress(extraTag, compressedExtraTag);
            }
            if (mapsTag.length > 0 && compressedMapsTag.length > 0) {
                Zstd.decompress(mapsTag, compressedMapsTag);
            }

            // Entities -> assign to chunk
            CompoundTag entitiesCompound = readCompoundTag(entities);
            if (entitiesCompound != null) {
                @SuppressWarnings("unchecked")
                ListTag<CompoundTag> entitiesList = (ListTag<CompoundTag>) entitiesCompound.getValue().get("entities");

                for (CompoundTag entityCompound : entitiesList.getValue()) {
                    @SuppressWarnings("unchecked")
                    ListTag<DoubleTag> pos = (ListTag<DoubleTag>) entityCompound.getAsListTag("Pos").get();

                    int chunkX = floor(pos.getValue().get(0).getValue()) >> 4;
                    int chunkZ = floor(pos.getValue().get(2).getValue()) >> 4;
                    long key = ((long) chunkZ) * Integer.MAX_VALUE + (long) chunkX;

                    SlimeChunk c = chunks.get(key);
                    if (c == null) throw new CorruptedWorldException(worldName);

                    c.getEntities().add(entityCompound);
                }
            }

            // Tile entities -> assign to chunk
            CompoundTag tileEntitiesCompound = readCompoundTag(tileEntities);
            if (tileEntitiesCompound != null) {
                @SuppressWarnings("unchecked")
                ListTag<CompoundTag> tiles = (ListTag<CompoundTag>) tileEntitiesCompound.getValue().get("tiles");

                for (CompoundTag te : tiles.getValue()) {
                    int chunkX = ((IntTag) te.getValue().get("x")).getValue() >> 4;
                    int chunkZ = ((IntTag) te.getValue().get("z")).getValue() >> 4;
                    long key = ((long) chunkZ) * Integer.MAX_VALUE + (long) chunkX;

                    SlimeChunk c = chunks.get(key);
                    if (c == null) throw new CorruptedWorldException(worldName);

                    c.getTileEntities().add(te);
                }
            }

            // Extra
            CompoundTag extraCompound = readCompoundTag(extraTag);
            if (extraCompound == null) extraCompound = new CompoundTag("", new CompoundMap());

            // Maps
            CompoundTag mapsCompound = readCompoundTag(mapsTag);
            List<CompoundTag> mapList;
            if (mapsCompound != null) {
                @SuppressWarnings("unchecked")
                List<CompoundTag> tmp = (List<CompoundTag>) mapsCompound.getAsListTag("maps")
                        .map(ListTag::getValue)
                        .orElse(new ArrayList<>());
                mapList = tmp;
            } else {
                mapList = new ArrayList<>();
            }

            // Auto-detect old world version if needed
            if (worldVersion == 0) {
                mainLoop:
                for (SlimeChunk c : chunks.values()) {
                    for (SlimeChunkSection s : c.getSections()) {
                        if (s != null) {
                            worldVersion = (byte) (s.getBlocks() == null ? 0x04 : 0x01);
                            break mainLoop;
                        }
                    }
                }
            }

            // Properties merge
            SlimePropertyMap worldPropertyMap = propertyMap;
            Optional<CompoundTag> propertiesTag = extraCompound.getAsCompoundTag("properties");

            if (propertiesTag.isPresent()) {
                worldPropertyMap = SlimePropertyMap.fromCompound(propertiesTag.get());
                worldPropertyMap.merge(propertyMap);
            } else if (propertyMap == null) {
                worldPropertyMap = new SlimePropertyMap();
            }

            return new CraftSlimeWorld(loader, worldName, chunks, extraCompound, mapList, worldVersion, worldPropertyMap, readOnly, !readOnly);

        } catch (EOFException ex) {
            throw new CorruptedWorldException(worldName, ex);
        }
    }

    private static int floor(double num) {
        final int floor = (int) num;
        return floor == num ? floor : floor - (int) (Double.doubleToRawLongBits(num) >>> 63);
    }

    private static Map<Long, SlimeChunk> readChunks(
            byte worldVersion,
            int version,
            String worldName,
            int minX,
            int minZ,
            int width,
            int depth,
            BitSet chunkBitset,
            DataInputStream dataStream
    ) throws IOException {

        Map<Long, SlimeChunk> chunkMap = new HashMap<>();

        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int bitsetIndex = z * width + x;
                if (!chunkBitset.get(bitsetIndex)) continue;

                // Height Maps
                CompoundTag heightMaps;
                if (worldVersion >= 0x04) {
                    int len = dataStream.readInt();
                    byte[] arr = new byte[len];
                    dataStream.readFully(arr);

                    heightMaps = readCompoundTag(arr);
                    if (heightMaps == null) heightMaps = new CompoundTag("", new CompoundMap());
                } else {
                    int[] heightMap = new int[256];
                    for (int i = 0; i < 256; i++) heightMap[i] = dataStream.readInt();

                    CompoundMap map = new CompoundMap();
                    map.put("heightMap", new IntArrayTag("heightMap", heightMap));
                    heightMaps = new CompoundTag("", map);
                }

                // Biomes
                int[] biomes;
                if (version == 8 && worldVersion < 0x04) {
                    dataStream.readInt(); // v8 bug compat
                }

                if (worldVersion >= 0x04) {
                    int biomeLen = version >= 8 ? dataStream.readInt() : 256;
                    biomes = new int[biomeLen];
                    for (int i = 0; i < biomes.length; i++) biomes[i] = dataStream.readInt();
                } else {
                    byte[] byteBiomes = new byte[256];
                    dataStream.readFully(byteBiomes);
                    biomes = toIntArray(byteBiomes);
                }

                SlimeChunkSection[] sections = readChunkSections(dataStream, worldVersion, version);

                long key = ((long) (minZ + z)) * Integer.MAX_VALUE + (long) (minX + x);
                chunkMap.put(key, new CraftSlimeChunk(worldName, minX + x, minZ + z, sections, heightMaps, biomes, new ArrayList<>(), new ArrayList<>()));
            }
        }

        return chunkMap;
    }

    private static int[] toIntArray(byte[] buf) {
        ByteBuffer buffer = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int[] ret = new int[buf.length / 4];
        buffer.asIntBuffer().get(ret);
        return ret;
    }

    private static SlimeChunkSection[] readChunkSections(DataInputStream dataStream, byte worldVersion, int version) throws IOException {
        SlimeChunkSection[] arr = new SlimeChunkSection[16];

        byte[] sectionBitmask = new byte[2];
        dataStream.readFully(sectionBitmask);
        BitSet sectionBitset = BitSet.valueOf(sectionBitmask);

        for (int i = 0; i < 16; i++) {
            if (!sectionBitset.get(i)) continue;

            // Block light
            NibbleArray blockLight;
            if (version < 5 || dataStream.readBoolean()) {
                byte[] bl = new byte[2048];
                dataStream.readFully(bl);
                blockLight = new NibbleArray(bl);
            } else {
                blockLight = null;
            }

            byte[] blocks;
            NibbleArray data;

            ListTag<CompoundTag> palette;
            long[] blockStates;

            if (worldVersion >= 0x04) {
                int paletteLen = dataStream.readInt();
                List<CompoundTag> paletteList = new ArrayList<>(paletteLen);

                for (int p = 0; p < paletteLen; p++) {
                    int tagLen = dataStream.readInt();
                    byte[] tagBytes = new byte[tagLen];
                    dataStream.readFully(tagBytes);
                    paletteList.add(readCompoundTag(tagBytes));
                }

                palette = new ListTag<>("", TagType.TAG_COMPOUND, paletteList);

                int bsLen = dataStream.readInt();
                blockStates = new long[bsLen];
                for (int b = 0; b < bsLen; b++) blockStates[b] = dataStream.readLong();

                blocks = null;
                data = null;
            } else {
                blocks = new byte[4096];
                dataStream.readFully(blocks);

                byte[] dataBytes = new byte[2048];
                dataStream.readFully(dataBytes);
                data = new NibbleArray(dataBytes);

                palette = null;
                blockStates = null;
            }

            // Sky light
            NibbleArray skyLight;
            if (version < 5 || dataStream.readBoolean()) {
                byte[] sl = new byte[2048];
                dataStream.readFully(sl);
                skyLight = new NibbleArray(sl);
            } else {
                skyLight = null;
            }

            if (version < 4) {
                short hypixelBlocksLength = dataStream.readShort();
                if (hypixelBlocksLength > 0) dataStream.skipBytes(hypixelBlocksLength);
            }

            arr[i] = new CraftSlimeChunkSection(blocks, data, palette, blockStates, blockLight, skyLight);
        }

        return arr;
    }

    private static CompoundTag readCompoundTag(byte[] serializedCompound) throws IOException {
        if (serializedCompound == null || serializedCompound.length == 0) return null;

        NBTInputStream stream = new NBTInputStream(
                new ByteArrayInputStream(serializedCompound),
                NBTInputStream.NO_COMPRESSION,
                ByteOrder.BIG_ENDIAN
        );

        return (CompoundTag) stream.readTag();
    }

    /**
     * Streams segmented chunk raw bytes by decompressing one segment at a time.
     */
    private static final class SegmentedDecompressedInputStream extends InputStream {
        private final DataInputStream in;
        private int remainingSegments;
        private ByteArrayInputStream current;

        SegmentedDecompressedInputStream(DataInputStream in, int segmentCount) {
            this.in = in;
            this.remainingSegments = segmentCount;
        }

        private boolean openNext() throws IOException {
            if (remainingSegments <= 0) return false;

            int compressedLen = in.readInt();
            int rawLen = in.readInt();
            if (compressedLen < 0 || rawLen < 0) {
                throw new EOFException("Invalid segment lengths: " + compressedLen + ", " + rawLen);
            }

            byte[] compressed = new byte[compressedLen];
            in.readFully(compressed);

            byte[] raw = Zstd.decompress(compressed, rawLen);
            current = new ByteArrayInputStream(raw);

            remainingSegments--;
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return (r == -1) ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (true) {
                if (current == null) {
                    if (!openNext()) return -1;
                }

                int r = current.read(b, off, len);
                if (r != -1) return r;

                current = null; // next segment
            }
        }
    }
}
