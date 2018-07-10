/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 */
package org.aion.zero.impl.db;

import static org.aion.mcf.db.DatabaseUtils.connectAndOpen;

import java.io.Closeable;
import java.io.File;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.aion.base.db.Flushable;
import org.aion.base.db.IByteArrayKeyValueDatabase;
import org.aion.base.util.ByteUtil;
import org.aion.db.impl.DBVendor;
import org.aion.db.impl.DatabaseFactory;
import org.aion.db.impl.DatabaseFactory.Props;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.db.exception.InvalidFilePathException;
import org.aion.mcf.ds.ObjectDataSource;
import org.aion.mcf.ds.Serializer;
import org.aion.rlp.RLP;
import org.aion.rlp.RLPElement;
import org.aion.rlp.RLPList;
import org.aion.zero.impl.types.AionBlock;
import org.slf4j.Logger;

public class PendingBlockStore implements Flushable, Closeable {

    private static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.DB.name());

    protected ReadWriteLock lock = new ReentrantReadWriteLock();

    // database names
    private static final String BLOCK_DB_NAME = "block";
    private static final String LEVEL_DB_NAME = "level";
    private static final String QUEUE_DB_NAME = "queue";
    private static final String INDEX_DB_NAME = "index";

    // data sources
    /** maps pending block hashes to their block data */
    private ObjectDataSource<AionBlock> blockSource;
    /** maps a level to the queue hashes with blocks starting at that level */
    private ObjectDataSource<List<byte[]>> levelSource;
    /** maps a queue hash to a list of consecutive block hashes */
    private ObjectDataSource<List<byte[]>> queueSource;
    /** maps a block hash to its current queue hash */
    private IByteArrayKeyValueDatabase indexSource;

    public PendingBlockStore(Properties props) throws InvalidFilePathException {

        File f = new File(props.getProperty(Props.DB_PATH), props.getProperty(Props.DB_NAME));
        try {
            // ask the OS if the path is valid
            f.getCanonicalPath();

            // try to create the directory
            if (!f.exists()) {
                f.mkdirs();
            }
        } catch (Exception e) {
            throw new InvalidFilePathException(
                    "Pending block store file path \""
                            + f.getAbsolutePath()
                            + "\" not valid as reported by the OS or a read/write permissions error occurred. Please provide an alternative DB file path in /config/config.xml.");
        }
        props.setProperty(Props.DB_PATH, f.getAbsolutePath());

        init(props);
    }

    /** Constructor for testing. Using {@link org.aion.db.impl.DBVendor#MOCKDB}. */
    public PendingBlockStore() {
        Properties props = new Properties();
        props.setProperty(DatabaseFactory.Props.DB_TYPE, DBVendor.MOCKDB.toValue());

        init(props);
    }

    public void init(Properties props) {

        // create the block source
        props.setProperty(Props.DB_NAME, BLOCK_DB_NAME);
        IByteArrayKeyValueDatabase database = connectAndOpen(props, LOG);

        this.blockSource =
                new ObjectDataSource<>(
                        database,
                        new Serializer<>() {
                            @Override
                            public byte[] serialize(AionBlock block) {
                                return block.getEncoded();
                            }

                            @Override
                            public AionBlock deserialize(byte[] bytes) {
                                return new AionBlock(bytes);
                            }
                        });

        // create the level source

        props.setProperty(Props.DB_NAME, LEVEL_DB_NAME);
        database = connectAndOpen(props, LOG);

        this.levelSource = new ObjectDataSource<>(database, HASH_LIST_RLP_SERIALIZER);

        // create the queue source

        props.setProperty(Props.DB_NAME, QUEUE_DB_NAME);
        database = connectAndOpen(props, LOG);

        this.queueSource = new ObjectDataSource<>(database, HASH_LIST_RLP_SERIALIZER);

        // create the index source

        props.setProperty(Props.DB_NAME, INDEX_DB_NAME);
        indexSource = connectAndOpen(props, LOG);
    }

    public List<AionBlock> loadBlockRange(long first, long last) {
        // get all the queues for the given levels
        List<byte[]> current, queueHashes = new ArrayList<>();
        for (long i = first; i <= last; i++) {
            current = levelSource.get(ByteUtil.longToBytes(i));
            if (current != null) {
                queueHashes.addAll(current);
            }
        }

        // get all the blocks in the given queues
        List<byte[]> blockHashes = new ArrayList<>();
        for (byte[] queue : queueHashes) {
            current = queueSource.get(queue);
            if (current != null) {
                blockHashes.addAll(current);
            }
        }

        // load blocks
        List<AionBlock> blocks = new ArrayList<>();
        for (byte[] hash : blockHashes) {
            blocks.add(blockSource.get(hash));
        }

        return blocks;
    }

    public static final Serializer<List<byte[]>, byte[]> HASH_LIST_RLP_SERIALIZER =
            new Serializer<>() {
                @Override
                public byte[] serialize(List<byte[]> object) {
                    byte[][] infoList = new byte[object.size()][];
                    int i = 0;
                    for (byte[] b : object) {
                        infoList[i] = RLP.encodeElement(b);
                        i++;
                    }
                    return RLP.encodeList(infoList);
                }

                @Override
                public List<byte[]> deserialize(byte[] stream) {
                    RLPList list = (RLPList) RLP.decode2(stream).get(0);
                    List<byte[]> res = new ArrayList<>(list.size());

                    for (RLPElement aList : list) {
                        res.add(aList.getRLPData());
                    }
                    return res;
                }
            };

    @Override
    public void flush() {
        lock.writeLock().lock();
        try {
            blockSource.flush();
            levelSource.flush();
            queueSource.flush();
            if (!this.indexSource.isAutoCommitEnabled()) {
                this.indexSource.commit();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Steps for storing the block data for later importing:
     *
     * <ol>
     *   <li>store block object in the <b>block</b> database
     *   <li>find or create queue hash and store it in the <b>index</b> database
     *   <li>add block hash to its queue in the <b>queue</b> database
     *   <li>if new queue, add it to the <b>level</b> database
     * </ol>
     */
    public boolean addBlock(AionBlock block) {
        // nothing to do with null parameter
        if (block == null) {
            return false;
        }

        lock.writeLock().lock();

        try {
            // skip if already stored
            if (!indexSource.get(block.getHash()).isPresent()) {

                // store block data
                blockSource.put(block.getHash(), block);

                // find parent queue hash
                Optional<byte[]> existingQueueHash = indexSource.get(block.getParentHash());
                byte[] currentQueueHash = null;
                List<byte[]> currentQueue = null;

                // get existing queue if present
                if (existingQueueHash.isPresent()) {
                    // using parent queue hash
                    currentQueueHash = existingQueueHash.get();

                    // append block to queue
                    currentQueue = queueSource.get(currentQueueHash);
                } // do not add else here!

                // when no queue exists OR problem with existing queue
                if (currentQueue == null || currentQueue.size() == 0) {
                    // start new queue

                    // queue hash = the node hash
                    currentQueueHash = block.getHash();
                    currentQueue = new ArrayList<>();

                    // add (to) level
                    byte[] levelKey = ByteUtil.longToBytes(block.getNumber());
                    List<byte[]> levelData = levelSource.get(levelKey);

                    if (levelData == null) {
                        levelData = new ArrayList<>();
                    }

                    levelData.add(currentQueueHash);
                    levelSource.put(levelKey, levelData);
                }

                // NOTE: at this point the currentQueueHash was initialized
                // either with a previous hash OR the block hash

                // index block with queue hash
                indexSource.put(block.getHash(), currentQueueHash);

                // add element to queue
                currentQueue.add(block.getHash());
                queueSource.put(currentQueueHash, currentQueue);

                // the block was added
                return true;
            } else {
                // block already stored
                return false;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Steps for storing the block data for later importing:
     *
     * <ol>
     *   <li>store block object in the <b>block</b> database
     *   <li>find or create queue hash and store it in the <b>index</b> database
     *   <li>add block hash to its queue in the <b>queue</b> database
     *   <li>if new queue, add it to the <b>level</b> database
     * </ol>
     *
     * @apiNote The blocks must be ordered by block number and direct ancestry, otherwise an {@link
     *     IllegalArgumentException} will be thrown when the inconsistency is encountered.
     */
    public int addBlockRange(List<AionBlock> blockRange) {
        // nothing to do when 0 blocks given
        if (blockRange == null || blockRange.size() == 0) {
            return 0;
        }

        lock.writeLock().lock();

        try {
            // first block determines the batch queue placement
            AionBlock first = blockRange.remove(0);

            // skip if already stored
            while (indexSource.get(first.getHash()).isPresent()) {
                if (blockRange.size() == 0) {
                    return 0;
                } else {
                    first = blockRange.remove(0);
                }
            }

            // store block data
            blockSource.putToBatch(first.getHash(), first);

            // find parent queue hash
            Optional<byte[]> existingQueueHash = indexSource.get(first.getParentHash());
            byte[] currentQueueHash = null;
            List<byte[]> currentQueue = null;

            // get existing queue if present
            if (existingQueueHash.isPresent()) {
                // using parent queue hash
                currentQueueHash = existingQueueHash.get();

                // append block to queue
                currentQueue = queueSource.get(currentQueueHash);
            } // do not add else here!

            // when no queue exists OR problem with existing queue
            if (currentQueue == null || currentQueue.size() == 0) {
                // start new queue

                // queue hash = the first node hash
                currentQueueHash = first.getHash();
                currentQueue = new ArrayList<>();

                // add (to) level
                byte[] levelKey = ByteUtil.longToBytes(first.getNumber());
                List<byte[]> levelData = levelSource.get(levelKey);

                if (levelData == null) {
                    levelData = new ArrayList<>();
                }

                levelData.add(currentQueueHash);
                levelSource.putToBatch(levelKey, levelData);
            }

            // NOTE: at this point the currentQueueHash was initialized
            // either with a previous hash or the first block hash

            // index block with queue hash
            indexSource.putToBatch(first.getHash(), currentQueueHash);

            // add element to queue
            currentQueue.add(first.getHash());

            // keep track of parent to ensure correct range
            AionBlock parent = first;

            // process rest of block range
            for (AionBlock current : blockRange) {
                // check correct input
                if (!Arrays.equals(current.getParentHash(), parent.getHash())) {
                    // TODO discard batch
                    throw new IllegalArgumentException(
                            "PendingBlockStore#addBlockRange called with non-sequential blocks.");
                }

                // store block data
                blockSource.putToBatch(current.getHash(), current);

                // index block to current queue
                indexSource.put(current.getHash(), currentQueueHash);

                // append block to queue
                currentQueue.add(current.getHash());

                // update parent
                parent = current;
            }

            // save data to disk
            blockSource.flushBatch();
            indexSource.commitBatch();
            queueSource.put(currentQueueHash, currentQueue);
            levelSource.flushBatch();

            // the number of blocks added
            return blockRange.size() + 1;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AionBlock getBlock(byte[] hash) {
        lock.readLock().lock();
        try {
            return blockSource.get(hash);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();

        try {
            blockSource.close();
        } catch (Exception e) {
            LOG.error("Not able to close the pending blocks database:", e);
        } finally {
            try {
                levelSource.close();
            } catch (Exception e) {
                LOG.error("Not able to close the pending blocks levels database:", e);
            } finally {
                try {
                    queueSource.close();
                } catch (Exception e) {
                    LOG.error("Not able to close the pending blocks queue database:", e);
                } finally {
                    try {
                        indexSource.close();
                    } catch (Exception e) {
                        LOG.error("Not able to close the pending blocks index database:", e);
                    } finally {
                        lock.writeLock().unlock();
                    }
                }
            }
        }
    }
}
