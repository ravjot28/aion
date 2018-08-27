/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 * This file is part of the aion network project.
 *
 * The aion network project is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * The aion network project is distributed in the hope that it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the aion network project source files.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * The aion network project leverages useful source code from other
 * open source projects. We greatly appreciate the effort that was
 * invested in these projects and we thank the individual contributors
 * for their work. For provenance information and contributors
 * please see <https://github.com/aionnetwork/aion/wiki/Contributors>.
 *
 * Contributors to the aion source files in decreasing order of code volume:
 * Aion foundation.
 */

package org.aion.zero.impl.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.aion.base.util.ByteArrayWrapper;
import org.aion.mcf.core.ImportResult;
import org.aion.p2p.P2pConstant;
import org.aion.zero.impl.AionBlockchainImpl;
import org.aion.zero.impl.sync.PeerState.Mode;
import org.aion.zero.impl.types.AionBlock;
import org.slf4j.Logger;

/**
 * handle process of importing blocks to repo
 *
 * <p>TODO: targeted send
 *
 * @author chris
 */
final class TaskImportBlocks implements Runnable {

    private final AionBlockchainImpl chain;

    private final AtomicBoolean start;

    private final BlockingQueue<BlocksWrapper> downloadedBlocks;

    private final SyncStatics statis;

    private final Map<ByteArrayWrapper, Object> importedBlockHashes;

    private final Map<Integer, PeerState> peerStates;

    private final Logger log;

    private List<Long> baseList;
    private PeerState localState;

    TaskImportBlocks(
            final AionBlockchainImpl _chain,
            final AtomicBoolean _start,
            final SyncStatics _statis,
            final BlockingQueue<BlocksWrapper> _downloadedBlocks,
            final Map<ByteArrayWrapper, Object> _importedBlockHashes,
            final Map<Integer, PeerState> _peerStates,
            final Logger _log) {
        this.chain = _chain;
        this.start = _start;
        this.statis = _statis;
        this.downloadedBlocks = _downloadedBlocks;
        this.importedBlockHashes = _importedBlockHashes;
        this.peerStates = _peerStates;
        this.log = _log;
        this.baseList = new ArrayList<>();
        this.localState = new PeerState(Mode.NORMAL, 0L);
    }

    private boolean isNotImported(AionBlock b) {
        return importedBlockHashes.get(ByteArrayWrapper.wrap(b.getHash())) == null;
    }

    private boolean isNotRestricted(AionBlock b) {
        return !chain.isPruneRestricted(b.getNumber());
    }

    private long getStateCount(Mode mode) {
        return peerStates.values().stream().filter(s -> s.getMode() == mode).count();
    }

    /** Returns either a recycled base or generates a new one. */
    private long getNextBase() {
        long best = getBestBlockNumber();

        // remove bases that are no longer relevant
        while (!baseList.isEmpty() && baseList.get(0) <= best) {
            baseList.remove(0);
        }

        if (baseList.isEmpty()) {
            return chain.nextBase(best);
        } else {
            return baseList.remove(0);
        }
    }

    /** Add an unused base to the list. */
    private void recycleNextBase(long base) {
        baseList.add(base);
    }

    private boolean canTorrent(PeerState state, long nextBase) {
        return state.getLastBestBlock() > nextBase + P2pConstant.TORRENT_REQUEST_SIZE;
    }

    @Override
    public void run() {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        while (start.get()) {
            BlocksWrapper bw;
            try {
                bw = downloadedBlocks.take();
            } catch (InterruptedException ex) {
                return;
            }

            List<AionBlock> batch;

            if (chain.checkPruneRestriction()) {
                // filter out restricted blocks if prune restrictions enabled
                batch =
                        bw.getBlocks()
                                .stream()
                                .filter(this::isNotImported)
                                .filter(this::isNotRestricted)
                                .collect(Collectors.toList());
            } else {
                // filter out only imported blocks
                batch =
                        bw.getBlocks()
                                .stream()
                                .filter(this::isNotImported)
                                .collect(Collectors.toList());
            }

            PeerState state = peerStates.get(bw.getNodeIdHash());
            if (state == null) {
                // ignoring these blocks
                log.warn("Peer {} sent blocks that were not requested.", bw.getDisplayId());
            } else { // the state is not null after this

                // ensuring a header request is not made while processing blocks
                // by setting time in the future
                state.setLastHeaderRequest(System.currentTimeMillis() + 20000);

                if (batch.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Empty batch received from node = {} in mode = {} with base = {}.",
                                bw.getDisplayId(),
                                state.getMode(),
                                state.getBase());
                    }
                } else {
                    // process batch with a copy of the state
                    processBatch(state, batch, bw.getDisplayId());
                    // make all the changes in one operation
                    state.copy(localState);
                }

                // so we can continue immediately
                state.resetLastHeaderRequest();

                statis.update(getBestBlockNumber());
            }
        }
        log.info(Thread.currentThread().getName() + " RIP.");
    }

    /** @implNote This method is called only when state is not null and batch in not empty. */
    private PeerState processBatch(PeerState givenState, List<AionBlock> batch, String displayId) {
        // make a copy of the original state
        localState.copy(givenState);

        ImportResult importResult = ImportResult.INVALID_BLOCK;

        // importing last block in batch to see if we can skip batch
        if (givenState.getMode() == Mode.FORWARD) {
            AionBlock b = batch.get(batch.size() - 1);

            try {
                importResult = importBlock(b, displayId, localState);
            } catch (Throwable e) {
                log.error("<import-block throw> {}", e.toString());
                if (e.getMessage() != null && e.getMessage().contains("No space left on device")) {
                    log.error("Shutdown due to lack of disk space.");
                    System.exit(0);
                }
                return localState;
            }

            if (importResult.isStored()) {
                importedBlockHashes.put(ByteArrayWrapper.wrap(b.getHash()), true);

                // TODO test and determine consequences
                forwardModeUpdate(localState, b.getNumber(), importResult, b.getNumber());

                // since last import worked skipping the batch
                batch.clear();
                if (log.isDebugEnabled()) {
                    log.debug("FORWARD skip for node {}.", displayId);
                }
                return localState;
            }
        }

        // check for late TORRENT or NORMAL nodes
        if ((givenState.getMode() == Mode.TORRENT || givenState.getMode() == Mode.NORMAL)
                && batch.get(batch.size() - 1).getNumber() < getBestBlockNumber()) {

            long nextBase = getNextBase();
            if (canTorrent(localState, nextBase)) {
                localState.update(Mode.TORRENT, nextBase, true);
            } else {
                localState.update(Mode.NORMAL, getBestBlockNumber(), true);
                recycleNextBase(nextBase);
            }

            batch.clear();
            if (log.isDebugEnabled()) {
                log.debug("TORRENT skip for node {}.", displayId);
            }
            return localState;
        }

        // remembering imported range
        long first = -1L, last = -1L;

        for (AionBlock b : batch) {
            try {
                importResult = importBlock(b, displayId, localState);

                if (importResult.isStored()) {
                    importedBlockHashes.put(ByteArrayWrapper.wrap(b.getHash()), true);

                    if (last <= b.getNumber()) {
                        last = b.getNumber() + 1;
                    }
                }
            } catch (Throwable e) {
                log.error("<import-block throw> {}", e.toString());
                if (e.getMessage() != null && e.getMessage().contains("No space left on device")) {
                    log.error("Shutdown due to lack of disk space.");
                    System.exit(0);
                }
                // TODO test and determine consequences
                continue;
            }

            // decide whether to change mode based on the first
            if (b == batch.get(0)) {
                first = b.getNumber();
                Mode mode = givenState.getMode();

                switch (importResult) {
                    case IMPORTED_BEST:
                    case IMPORTED_NOT_BEST:
                    case EXIST:
                        // assuming the remaining blocks will be imported. if not, the state
                        // and base will be corrected by the next cycle
                        long lastBlock = batch.get(batch.size() - 1).getNumber();

                        if (mode == Mode.BACKWARD) {
                            // we found the fork point
                            localState.update(Mode.FORWARD, lastBlock, true);
                        } else if (mode == Mode.FORWARD) {
                            forwardModeUpdate(localState, lastBlock, importResult, b.getNumber());
                        }
                        break;
                    case NO_PARENT:
                        if (mode == Mode.BACKWARD) {
                            // update base
                            localState.setBase(b.getNumber());
                        } else {
                            if (mode != Mode.TORRENT && givenState.isBackwardAble()) {
                                // switch to backward mode
                                localState.update(Mode.BACKWARD, b.getNumber(), false);
                            } else {
                                if (!givenState.isBackwardAble()
                                        && givenState.getRepeated() == givenState.getMaxRepeats()) {
                                    localState.setBackwardAble(true);
                                } else {
                                    localState.incRepeated();
                                }
                                localState.update(Mode.NORMAL, getBestBlockNumber(), true);
                            }
                        }
                        break;
                }
            }

            // if any block results in NO_PARENT, all subsequent blocks will too
            if (importResult == ImportResult.NO_PARENT) {
                int stored = chain.storePendingBlockRange(batch);
                log.debug(
                        "Stopped importing batch due to NO_PARENT result.\n"
                                + "Stored {} out of {} blocks starting at hash = {}, number = {} from node = {}.",
                        stored,
                        batch.size(),
                        b.getShortHash(),
                        b.getNumber(),
                        displayId);

                // check for repeated work
                if (localState.getMode() == Mode.TORRENT) {

                    long nextBase = getNextBase();
                    if (!canTorrent(localState, nextBase)) {
                        log.debug(
                                "Node {} switched from TORRENT to NORMAL with base = {}.",
                                displayId,
                                nextBase);

                        localState.update(Mode.NORMAL, getBestBlockNumber(), true);
                        recycleNextBase(nextBase);
                    } else {
                        if (stored < batch.size()) {
                            // use generated next base
                            localState.setBase(nextBase);
                        } else {
                            localState.incRepeated();
                            // continue queue if not repeated
                            localState.setBase(b.getNumber() + batch.size());
                            log.debug(
                                    "Node {} TORRENT continued with base = {}.",
                                    displayId,
                                    localState.getBase());
                            recycleNextBase(nextBase);
                        }
                    }
                }
                break;
            }
        }

        if (localState.getMode() == Mode.FORWARD && importResult == ImportResult.EXIST) {
            // increment the repeat count every time
            // we finish a batch of imports with EXIST
            localState.incRepeated();
        }

        if (localState.getMode() == Mode.FORWARD && importResult == ImportResult.IMPORTED_BEST) {
            // behaving like normal so switch back
            localState.setMode(Mode.NORMAL);
        }

        // check for stored blocks
        if (first < last) {
            int imported = importFromStorage(localState, first, last);
            if (imported > 0) {
                // TODO: may have already updated torrent mode
                if (localState.getMode() == Mode.TORRENT) {
                    if (localState.getBase() == givenState.getBase() // was not already updated
                            || localState.getBase()
                                    <= getBestBlockNumber() + P2pConstant.REQUEST_SIZE) {
                        long nextBase = getNextBase();
                        if (canTorrent(localState, nextBase)) {
                            localState.setBase(nextBase);
                        } else {
                            localState.update(Mode.NORMAL, getBestBlockNumber(), true);
                            recycleNextBase(nextBase);
                        }
                    } // else already updated to a correct request
                } else {
                    localState.setBase(getBestBlockNumber());
                }
            }
        }

        long nrmStates = getStateCount(Mode.NORMAL);
        long trtStates = getStateCount(Mode.TORRENT);
        long nextBase = getNextBase();
        // in NORMAL mode and blocks filtered out
        if (localState.getMode() == Mode.NORMAL
                && (batch.isEmpty() || (nrmStates > 4 && trtStates < nrmStates - 1))
                && canTorrent(localState, nextBase)) {
            // targeting around same number of TORRENT and NORMAL sync nodes
            // with a minimum of 2 NORMAL nodes
            log.debug(
                    "<import-mode-before: node = {}, sync mode = {}, base = {}>",
                    displayId,
                    localState.getMode(),
                    localState.getBase());

            localState.update(Mode.TORRENT, nextBase, true);
            localState.resetLastHeaderRequest();

            log.debug(
                    "<import-mode-after: node = {}, sync mode = {}, base = {}>",
                    displayId,
                    localState.getMode(),
                    localState.getBase());
            return localState;
        }
        return localState;
    }

    private ImportResult importBlock(AionBlock b, String displayId, PeerState state) {
        ImportResult importResult;
        long t1 = System.currentTimeMillis();
        importResult = this.chain.tryToConnect(b);
        long t2 = System.currentTimeMillis();
        log.info(
                "<import-status: node = {}, sync mode = {}, hash = {}, number = {}, txs = {}, result = {}, time elapsed = {} ms>",
                displayId,
                (state != null ? state.getMode() : Mode.NORMAL),
                b.getShortHash(),
                b.getNumber(),
                b.getTransactionsList().size(),
                importResult,
                t2 - t1);
        return importResult;
    }

    private void forwardModeUpdate(
            PeerState state, long lastBlock, ImportResult importResult, long blockNumber) {
        // continue
        state.setBase(lastBlock);
        // if the imported best block, switch back to normal mode
        if (importResult.isBest()) {
            state.setMode(Mode.NORMAL);
            // switch peers to NORMAL otherwise they may never switch back
            for (PeerState peerState : peerStates.values()) {
                if (peerState.getMode() != Mode.NORMAL) {
                    peerState.update(Mode.NORMAL, blockNumber, false);
                    peerState.resetLastHeaderRequest();
                }
            }
        }
        // if the maximum number of repeats is passed
        // then the peer is stuck endlessly importing old blocks
        // otherwise it would have found an IMPORTED block already
        if (state.getRepeated() >= state.getMaxRepeats()) {
            state.update(Mode.NORMAL, getBestBlockNumber(), false);
            state.resetLastHeaderRequest();
        }
    }

    /**
     * Imports blocks from storage as long as there are blocks to import.
     *
     * @return the total number of imported blocks from all iterations
     */
    private int importFromStorage(PeerState state, long first, long last) {
        ImportResult importResult = ImportResult.NO_PARENT;
        int imported = 0, batch;
        long level = first;

        while (level <= last) {
            // get blocks stored for level
            Map<ByteArrayWrapper, List<AionBlock>> levelFromDisk =
                    chain.loadPendingBlocksAtLevel(level);

            if (levelFromDisk.isEmpty()) {
                // move on to next level
                level++;
                continue;
            }

            List<ByteArrayWrapper> importedQueues = new ArrayList<>(levelFromDisk.keySet());

            for (Map.Entry<ByteArrayWrapper, List<AionBlock>> entry : levelFromDisk.entrySet()) {
                // initialize batch counter
                batch = 0;

                List<AionBlock> batchFromDisk = entry.getValue();

                if (log.isDebugEnabled()) {
                    log.debug(
                            "Loaded {} blocks from disk from level {} queue {} before filtering.",
                            batchFromDisk.size(),
                            entry.getKey(),
                            level);
                }

                // filter already imported blocks
                batchFromDisk =
                        batchFromDisk
                                .stream()
                                .filter(this::isNotImported)
                                .collect(Collectors.toList());

                if (batchFromDisk.size() > 0) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "{} {} left after filtering out imported blocks.",
                                batchFromDisk.size(),
                                (batchFromDisk.size() == 1 ? "block" : "blocks"));
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("No blocks left after filtering out imported blocks.");
                    }
                    // move on to next queue
                    // this queue will be deleted from storage
                    continue;
                }

                for (AionBlock b : batchFromDisk) {
                    try {
                        importResult = importBlock(b, "STORAGE", state);

                        if (importResult.isStored()) {
                            importedBlockHashes.put(ByteArrayWrapper.wrap(b.getHash()), true);

                            batch++;

                            if (last <= b.getNumber()) {
                                // can try importing more
                                last = b.getNumber() + 1;
                            }
                        } else {
                            // do not delete queue from storage
                            importedQueues.remove(entry.getKey());
                            // stop importing this queue
                            break;
                        }
                    } catch (Throwable e) {
                        log.error("<import-block throw> {}", e.toString());
                        if (e.getMessage() != null
                                && e.getMessage().contains("No space left on device")) {
                            log.error("Shutdown due to lack of disk space.");
                            System.exit(0);
                        }
                    }
                }

                imported += batch;
            }

            // remove imported data from storage
            chain.dropImported(level, importedQueues, levelFromDisk);

            // increment level
            level++;
        }

        // switch to NORMAL if in FORWARD mode
        if (importResult.isBest() && state.getMode() == Mode.FORWARD) {
            state.update(Mode.NORMAL, getBestBlockNumber(), false);
        }

        return imported;
    }

    private long getBestBlockNumber() {
        return chain.getBestBlock() == null ? 0 : chain.getBestBlock().getNumber();
    }
}
