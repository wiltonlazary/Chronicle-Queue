/*
 * Copyright 2014-2018 Chronicle Software
 *
 * http://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.queue.impl.table;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.StackTrace;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.queue.impl.TableStore;
import net.openhft.chronicle.threads.TimingPauser;

import java.io.File;
import java.util.function.Supplier;

public abstract class AbstractTSQueueLock implements Closeable {
    protected static final long UNLOCKED = Long.MIN_VALUE;

    protected final LongValue lock;
    protected final TimingPauser pauser;
    protected final File path;
    protected final TableStore tableStore;

    public AbstractTSQueueLock(final String lockKey, final TableStore<?> tableStore, final Supplier<TimingPauser> pauser) {
        this.tableStore = tableStore;
        this.lock = tableStore.doWithExclusiveLock(ts -> ts.acquireValueFor(lockKey));
        this.pauser = pauser.get();
        this.path = tableStore.file();
    }

    public void close() {
        Closeable.closeQuietly(lock);
    }

    protected void closeCheck() {
        if (tableStore.isClosed()) {
            throw new IllegalStateException("Underlying TableStore is already closed - was the Queue closed?");
        }
    }

    protected void forceUnlock() {
        Jvm.warn().on(getClass(), "Forced unlock for the lock file:" + path, new StackTrace());
        lock.setValue(UNLOCKED);
    }
}
