/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.state;

import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.uuid.UUID;

import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.Collections;

import EDU.oswego.cs.dl.util.concurrent.ReadWriteLock;
import EDU.oswego.cs.dl.util.concurrent.Sync;
import EDU.oswego.cs.dl.util.concurrent.Latch;
import EDU.oswego.cs.dl.util.concurrent.WriterPreferenceReadWriteLock;

/**
 * <code>FineGrainedISMLocking</code>...
 */
public class FineGrainedISMLocking implements ISMLocking {

    /**
     * Avoid creating commonly used Integer instances.
     */
    private static final Integer ONE = new Integer(1);

    /**
     * An anonymous read lock without an id assigned.
     */
    private final ReadLock ANONYMOUS_READ_LOCK = new ReadLockImpl();

    /**
     * The active writer or <code>null</code> if there is none.
     */
    private WriteLockImpl activeWriter;

    private volatile Thread activeWriterThread;

    private ReadWriteLock writerStateRWLock = new WriterPreferenceReadWriteLock();

    /**
     * Map that contains the read locks.
     */
    private final LockMap readLockMap = new LockMap();

    /**
     * List of waiting readers that are blocked because they conflict with
     * the current writer.
     */
    private List waitingReaders = Collections.synchronizedList(new LinkedList());

    /**
     * List of waiting writers that are blocked because there is already a
     * current writer or one of the current reads conflicts with the change log
     * of the blocked writer.
     */
    private List waitingWriters = new LinkedList();

    /**
     * {@inheritDoc}
     */
    public ReadLock acquireReadLock(ItemId id)
            throws InterruptedException {
        if (activeWriterThread == Thread.currentThread()) {
            // we hold the write lock
            readLockMap.addLock(id);
            return new ReadLockImpl(id);
        }

        // if we get here the following is true:
        // - the current thread does not hold a write lock
        for (;;) {
            Sync signal;
            // make sure writer state does not change
            Sync shared = writerStateRWLock.readLock();
            shared.acquire();
            try {
                if (activeWriter == null
                        || !hasDependency(activeWriter.changes, id)) {
                    readLockMap.addLock(id);
                    return new ReadLockImpl(id);
                } else {
                    signal = new Latch();
                    waitingReaders.add(signal);
                }
            } finally {
                shared.release();
            }

            // if we get here there was an active writer with
            // a dependency to the current id.
            // wait for the writer until it is done, then try again
            signal.acquire();
        }
    }

    /**
     * {@inheritDoc}
     */
    public WriteLock acquireWriteLock(ChangeLog changeLog)
            throws InterruptedException {
        for (;;) {
            Sync signal;
            // we want to become the current writer
            Sync exclusive = writerStateRWLock.writeLock();
            exclusive.acquire();
            try {
                if (activeWriter == null
                        && !readLockMap.hasDependency(changeLog)) {
                    activeWriter = new WriteLockImpl(changeLog);
                    activeWriterThread = Thread.currentThread();
                    return activeWriter;
                } else {
                    signal = new Latch();
                    waitingWriters.add(signal);
                }
            } finally {
                exclusive.release();
            }
            // if we get here there is an active writer or there is a read
            // lock that conflicts with the change log
            signal.acquire();
        }
    }

    //----------------------------< internal >----------------------------------

    private final class WriteLockImpl implements WriteLock {

        private final ChangeLog changes;

        WriteLockImpl(ChangeLog changes) {
            this.changes = changes;
        }

        public void release() {
            Sync exclusive = writerStateRWLock.writeLock();
            for (;;) {
                try {
                    exclusive.acquire();
                    break;
                } catch (InterruptedException e) {
                    // try again
                    Thread.interrupted();
                }
            }
            try {
                activeWriter = null;
                activeWriterThread = null;
                notifyWaitingReaders();
                notifyWaitingWriters();
            } finally {
                exclusive.release();
            }
        }

        public ReadLock downgrade() {
            readLockMap.addLock(null);
            Sync exclusive = writerStateRWLock.writeLock();
            for (;;) {
                try {
                    exclusive.acquire();
                    break;
                } catch (InterruptedException e) {
                    // try again
                    Thread.interrupted();
                }
            }
            try {
                activeWriter = null;
                activeWriterThread = null;
                // only notify waiting readers since we still hold a down
                // graded lock, which is kind of exclusiv with respect to
                // other writers
                notifyWaitingReaders();
            } finally {
                exclusive.release();
            }
            return ANONYMOUS_READ_LOCK;
        }

    }

    private final class ReadLockImpl implements ReadLock {

        private final ItemId id;

        public ReadLockImpl() {
            this(null);
        }

        ReadLockImpl(ItemId id) {
            this.id = id;
        }

        public void release() {
            Sync shared = writerStateRWLock.readLock();
            for (;;) {
                try {
                    shared.acquire();
                    break;
                } catch (InterruptedException e) {
                    // try again
                    Thread.interrupted();
                }
            }
            try {
                readLockMap.removeLock(id);
                if (activeWriterThread != Thread.currentThread()) {
                    // only notify waiting writers if we do *not* hold a write
                    // lock at the same time. that would be a waste of cpu time.
                    notifyWaitingWriters();
                }
            } finally {
                shared.release();
            }
        }
    }

    private static boolean hasDependency(ChangeLog changeLog, ItemId id) {
        try {
            if (changeLog.get(id) == null) {
                if (!id.denotesNode() || changeLog.get(new NodeReferencesId((NodeId) id)) == null) {
                    // change log does not contain the item
                    return false;
                }
            }
        } catch (NoSuchItemStateException e) {
            // is deleted
        }
        return true;
    }

    /**
     * This method is not thread-safe and calling threads must ensure that
     * only one thread calls this method at a time.
     */
    private void notifyWaitingReaders() {
        Iterator it = waitingReaders.iterator();
        while (it.hasNext()) {
            ((Sync) it.next()).release();
            it.remove();
        }
    }

    /**
     * This method may be called concurrently by multiple threads.
     */
    private void notifyWaitingWriters() {
        synchronized (waitingWriters) {
            if (waitingWriters.isEmpty()) {
                return;
            }
            Iterator it = waitingWriters.iterator();
            while (it.hasNext()) {
                ((Sync) it.next()).release();
                it.remove();
            }
        }
    }

    private static final class LockMap {

        /**
         * 16 slots
         */
        private final Map[] slots = new Map[0x10];

        /**
         * Flag that indicates if the entire map is locked.
         */
        private volatile boolean global = false;

        public LockMap() {
            for (int i = 0; i < slots.length; i++) {
                slots[i] = new HashMap();
            }
        }

        /**
         * This method must be called while holding the reader sync of the
         * {@link FineGrainedISMLocking#writerStateRWLock}!
         *
         * @param id the item id.
         */
        public void addLock(ItemId id) {
            if (id == null) {
                if (global) {
                    throw new IllegalStateException(
                            "Map already globally locked");
                }
                global = true;
                return;
            }
            Map locks = slots[slotIndex(id)];
            synchronized (locks) {
                Integer i = (Integer) locks.get(id);
                if (i == null) {
                    i = ONE;
                } else {
                    i = new Integer(i.intValue() + 1);
                }
                locks.put(id, i);
            }
        }

        /**
         * This method must be called while holding the reader sync of the
         * {@link FineGrainedISMLocking#writerStateRWLock}!
         *
         * @param id the item id.
         */
        public void removeLock(ItemId id) {
            if (id == null) {
                if (!global) {
                    throw new IllegalStateException(
                            "Map not globally locked");
                }
                global = false;
                return;
            }
            Map locks = slots[slotIndex(id)];
            synchronized (locks) {
                Integer i = (Integer) locks.get(id);
                if (i != null) {
                    if (i.intValue() == 1) {
                        locks.remove(id);
                    } else {
                        locks.put(id, new Integer(i.intValue() - 1));
                    }
                } else {
                    throw new IllegalStateException(
                            "No lock present for id: " + id);
                }
            }
        }

        /**
         * This method must be called while holding the write sync of {@link
         * FineGrainedISMLocking#writerStateRWLock} to make sure no additional
         * read locks are added to or removed from the map!
         *
         * @param changes the change log.
         * @return if the change log has a dependency to the locks currently
         *         present in this map.
         */
        public boolean hasDependency(ChangeLog changes) {
            if (global) {
                // read lock present, which was downgraded from a write lock
                return true;
            }
            for (int i = 0; i < slots.length; i++) {
                Map locks = slots[i];
                if (!locks.isEmpty()) {
                    Iterator it = locks.keySet().iterator();
                    while (it.hasNext()) {
                        ItemId id = (ItemId) it.next();
                        if (FineGrainedISMLocking.hasDependency(changes, id)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static int slotIndex(ItemId id) {
            UUID uuid;
            if (id.denotesNode()) {
                uuid = ((NodeId) id).getUUID();
            } else {
                uuid = ((PropertyId) id).getParentId().getUUID();
            }
            return ((int) uuid.getLeastSignificantBits()) & 0xf;
        }
    }
}
