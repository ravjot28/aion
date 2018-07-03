package org.aion.zero.impl.db;

import java.io.Closeable;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.aion.base.db.Flushable;
import org.aion.base.db.IByteArrayKeyValueDatabase;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.ds.ObjectDataSource;
import org.aion.mcf.ds.Serializer;
import org.aion.zero.impl.types.AionBlock;
import org.slf4j.Logger;

public class PendingBlockStore implements Flushable, Closeable {

    private static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.DB.name());

    protected ReadWriteLock lock = new ReentrantReadWriteLock();

    private ObjectDataSource<AionBlock> pending_blocks;

    public PendingBlockStore(IByteArrayKeyValueDatabase pending) {
        this.pending_blocks =
            new ObjectDataSource<>(
                pending,
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
    }

    @Override
    public void flush() {
        lock.writeLock().lock();
        try {
            pending_blocks.flush();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addBlock(AionBlock block) {
        lock.writeLock().lock();
        try {
            pending_blocks.put(block.getHash(), block);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AionBlock getBlock(byte[] hash) {
        lock.readLock().lock();
        try {
            return pending_blocks.get(hash);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();

        try {
            pending_blocks.close();
        } catch (Exception e) {
            LOG.error("Not able to close the pending_blocks database:", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
