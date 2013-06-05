package com.maxmind.maxminddb;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Instances of this class provide a reader for the MaxMind DB format. IP
 * addresses can be looked up using the <code>get</code> method.
 */
public final class Reader {
    private static final int DATA_SECTION_SEPARATOR_SIZE = 16;
    private static final byte[] METADATA_START_MARKER = { (byte) 0xAB,
            (byte) 0xCD, (byte) 0xEF, 'M', 'a', 'x', 'M', 'i', 'n', 'd', '.',
            'c', 'o', 'm' };

    private final boolean DEBUG;
    private final Decoder decoder;
    private final Metadata metadata;
    private final ThreadBuffer threadBuffer;

    /**
     * The file mode to use when opening a MaxMind DB.
     */
    public enum FileMode {
        /**
         * The default file mode. This maps the database to virtual memory. This
         * often provides similar performance to loading the database into real
         * memory without the overhead.
         */
        MEMORY_MAPPED,
        /**
         * Loads the database into memory when the reader is constructed.
         */
        IN_MEMORY
    }

    /**
     * Constructs a Reader for the MaxMind DB format. The file passed to it must
     * be a valid MaxMind DB file such as a GeoIP2 database file.
     * 
     * @param database
     *            the MaxMind DB file to use.
     * @throws IOException
     *             if there is an error opening or reading from the file.
     */
    public Reader(File database) throws IOException {
        this(database, FileMode.MEMORY_MAPPED);
    }

    /**
     * Constructs a Reader for the MaxMind DB format. The file passed to it must
     * be a valid MaxMind DB file such as a GeoIP2 database file.
     * 
     * @param database
     *            the MaxMind DB file to use.
     * @param fileMode
     *            the mode to open the file with.
     * @throws IOException
     *             if there is an error opening or reading from the file.
     */
    public Reader(File database, FileMode fileMode) throws IOException {
        this.DEBUG = System.getenv().get("MAXMIND_DB_READER_DEBUG") != null;
        this.threadBuffer = new ThreadBuffer(database, fileMode);

        /*
         * We need to make sure that whatever chunk we read will have the
         * metadata in it. The description metadata key is a hash of
         * descriptions, one per language. The description could be something
         * verbose like "GeoIP 2.0 City Database, Multilingual - English,
         * Chinese (Taiwan), Chinese (China), French, German, Portuguese" (but
         * with c. 20 languages). That comes out to about 250 bytes _per key_.
         * Multiply that by 20 languages, and the description alon ecould use up
         * about 5k. The other keys in the metadata are very, very tiny.
         * 
         * Given all this, reading 20k seems fairly future-proof. We'd have to
         * have extremely long descriptions or descriptions in 80 languages
         * before this became too long.
         */
        long start = this.findMetadataStart();

        if (start < 0) {
            throw new InvalidDatabaseException(
                    "Could not find a MaxMind DB metadata marker in this file ("
                            + database.getName()
                            + "). Is this a valid MaxMind DB file?");
        }

        Decoder metadataDecoder = new Decoder(this.threadBuffer, 0);

        this.metadata = new Metadata(metadataDecoder.decode(start).getNode());

        this.decoder = new Decoder(this.threadBuffer,
                this.metadata.searchTreeSize + DATA_SECTION_SEPARATOR_SIZE);

        if (this.DEBUG) {
            Log.debug(this.metadata.toString());
        }
    }

    /**
     * Looks up the <code>address</code> in the MaxMind DB.
     * 
     * @param ipAddress
     *            the IP address to look up.
     * @return the record for the IP address.
     * @throws IOException
     *             if a file I/O error occurs.
     */
    public JsonNode get(InetAddress ipAddress) throws IOException {

        long pointer = this.findAddressInTree(ipAddress);

        if (pointer == 0) {
            return null;
        }

        this.threadBuffer.get().position((int) pointer);
        return this.resolveDataPointer(pointer);
    }

    private long findAddressInTree(InetAddress address)
            throws InvalidDatabaseException {
        byte[] rawAddress = address.getAddress();

        if (this.DEBUG) {
            Log.debugNewLine();
            Log.debug("IP address", address);
            Log.debug("IP address", rawAddress);
            Log.debugNewLine();
        }

        boolean isIp4AddressInIp6Db = rawAddress.length == 4
                && this.metadata.ipVersion == 6;
        int ipStartBit = isIp4AddressInIp6Db ? 96 : 0;

        // The first node of the tree is always node 0, at the beginning of the
        // value
        long nodeNum = 0;

        for (int i = 0; i < rawAddress.length * 8 + ipStartBit; i++) {
            int bit = 0;
            if (i >= ipStartBit) {
                int b = 0xFF & rawAddress[(i - ipStartBit) / 8];
                bit = 1 & (b >> 7 - (i % 8));
            }
            long record = this.readNode(nodeNum, bit);

            if (this.DEBUG) {
                Log.debug("Bit #", i);
                Log.debug("Bit value", bit);
                Log.debug("Record", bit == 1 ? "right" : "left");
                // Log.debug("Node count", this.metadata.nodeCount);
                Log.debug("Record value", record);
            }

            if (record == this.metadata.nodeCount) {
                if (this.DEBUG) {
                    Log.debug("Record is empty");
                }
                return 0;
            }

            if (record >= this.metadata.nodeCount) {
                if (this.DEBUG) {
                    Log.debug("Record is a data pointer");
                }
                return record;
            }

            if (this.DEBUG) {
                Log.debug("Record is a node number");
            }

            nodeNum = record;
        }

        throw new InvalidDatabaseException("Something bad happened");
    }

    private long readNode(long nodeNumber, int index)
            throws InvalidDatabaseException {
        ByteBuffer buffer = this.threadBuffer.get();
        int baseOffset = (int) nodeNumber * this.metadata.nodeByteSize;
        buffer.position(baseOffset);

        switch (this.metadata.recordSize) {
            case 24:
                buffer.position(baseOffset + index * 3);
                return Decoder.decodeLong(buffer, 0, 3);
            case 28:
                long middle = buffer.get(baseOffset + 3);

                if (index == 0) {
                    middle = (0xF0 & middle) >>> 4;
                } else {
                    middle = 0x0F & middle;
                }
                buffer.position(baseOffset + index * 4);
                return Decoder.decodeLong(buffer, middle, 3);
            case 32:
                buffer.position(baseOffset + index * 4);
                return Decoder.decodeLong(buffer, 0, 4);
            default:
                throw new InvalidDatabaseException("Unknown record size: "
                        + this.metadata.recordSize);
        }
    }

    private JsonNode resolveDataPointer(long pointer) throws IOException {
        long resolved = (pointer - this.metadata.nodeCount)
                + this.metadata.searchTreeSize;

        if (this.DEBUG) {
            long treeSize = this.metadata.searchTreeSize;

            Log.debug("Resolved data pointer", "( " + pointer + " - "
                    + this.metadata.nodeCount + " ) + " + treeSize + " = "
                    + resolved);

        }

        // We only want the data from the decoder, not the offset where it was
        // found.
        return this.decoder.decode(resolved).getNode();
    }

    /*
     * And here I though searching a file was a solved problem.
     * 
     * This is an extremely naive but reasonably readable implementation. There
     * are much faster algorithms (e.g., Boyer-Moore) for this if speed is ever
     * an issue, but I suspect it won't be.
     */
    private long findMetadataStart() {
        ByteBuffer buffer = this.threadBuffer.get();
        int fileSize = buffer.capacity();

        FILE: for (int i = 0; i < fileSize - METADATA_START_MARKER.length + 1; i++) {
            for (int j = 0; j < METADATA_START_MARKER.length; j++) {
                byte b = buffer.get(fileSize - i - j - 1);
                if (b != METADATA_START_MARKER[METADATA_START_MARKER.length - j
                        - 1]) {
                    continue FILE;
                }
            }
            return fileSize - i;
        }
        return -1;
    }

    Metadata getMetadata() {
        return this.metadata;
    }

    /**
     * Closes the MaxMind DB and returns resources to the system.
     * 
     * @throws IOException
     *             if an I/O error occurs.
     */
    public void close() throws IOException {
        this.threadBuffer.close();
    }
}