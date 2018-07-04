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
import java.util.ArrayList;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.aion.base.db.Flushable;
import org.aion.base.db.IByteArrayKeyValueDatabase;
import org.aion.base.util.ByteUtil;
import org.aion.db.impl.DatabaseFactory.Props;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.db.exception.InvalidFilePathException;
import org.aion.mcf.ds.ObjectDataSource;
import org.aion.mcf.ds.Serializer;
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
    private ObjectDataSource<HashList> levelSource;
    /** maps a queue hash to a list of consecutive block hashes */
    private ObjectDataSource<HashList> queueSource;
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

        // create the block source

        props.setProperty(Props.DB_NAME, BLOCK_DB_NAME);
        IByteArrayKeyValueDatabase database = connectAndOpen(props, LOG);

        this.blockSource =
                new ObjectDataSource<>(
                        database,
                        new Serializer<AionBlock, byte[]>() {
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

        this.levelSource =
                new ObjectDataSource<>(
                        database,
                        new Serializer<HashList, byte[]>() {
                            @Override
                            public byte[] serialize(HashList hashes) {
                                return hashes.getEncoded();
                            }

                            @Override
                            public HashList deserialize(byte[] bytes) {
                                return new HashList(bytes);
                            }
                        });

        // create the queue source

        props.setProperty(Props.DB_NAME, QUEUE_DB_NAME);
        database = connectAndOpen(props, LOG);

        this.queueSource =
                new ObjectDataSource<>(
                        database,
                        new Serializer<HashList, byte[]>() {
                            @Override
                            public byte[] serialize(HashList hashes) {
                                return hashes.getEncoded();
                            }

                            @Override
                            public HashList deserialize(byte[] bytes) {
                                return new HashList(bytes);
                            }
                        });

        // create the index source

        props.setProperty(Props.DB_NAME, INDEX_DB_NAME);
        indexSource = connectAndOpen(props, LOG);
    }

    public static class HashList extends ArrayList<byte[]> {

        public HashList() {}

        public HashList(byte[] encoding) {
            String[] values = new String(encoding).split(",");
            for (String val : values) {
                this.add(val.getBytes());
            }
        }

        public byte[] getEncoded() {
            // TODO needs optimization
            StringBuffer sb = new StringBuffer();
            this.forEach(
                    v -> {
                        sb.append(new String(v));
                        sb.append(",");
                    });
            return sb.toString().getBytes();
        }
    }

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

    public void addBlock(AionBlock block) {
        lock.writeLock().lock();

        try {
            // 1. store block data
            blockSource.put(block.getHash(), block);

            // 2. add (to) queue & add index

            // find parent queue hash
            Optional<byte[]> queueHash = indexSource.get(block.getParentHash());

            if (!queueHash.isPresent()) {
                // parent not stored -> start new queue
                startNewQueue(block.getHash(), block.getNumber());
            } else {
                byte[] blockQueueHash = queueHash.get();

                // index block to same queue as parent
                indexSource.put(block.getHash(), queueHash.get());

                // append block to queue
                HashList queue = queueSource.get(blockQueueHash);

                if (queue == null || queue.size() == 0) {
                    // some error occurred -> start new queue
                    startNewQueue(block.getHash(), block.getNumber());
                } else {
                    // add element
                    queue.add(block.getHash());
                    // store to db
                    queueSource.put(blockQueueHash, queue);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void startNewQueue(byte[] blockHash, long blockNumber) {
        // the queue hash is the same as the block hash
        indexSource.put(blockHash, blockHash);

        // create and store queue
        HashList queue = new HashList();
        queue.add(blockHash);
        queueSource.put(blockHash, queue);

        // 3. add (to) level
        byte[] levelKey = ByteUtil.longToBytes(blockNumber);
        HashList levelData = levelSource.get(levelKey);

        if (levelData == null) {
            levelData = new HashList();
        }

        levelData.add(blockHash);
        levelSource.put(levelKey, levelData);
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
