package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.bytes.NewChunkListener;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.time.TimeProvider;
import net.openhft.chronicle.queue.impl.WireStore;

import java.io.Closeable;
import java.util.function.IntConsumer;

/**
 * A class designed to be called from a long-lived thread.
 * <p>
 * Upon invocation of the {@code execute()} method, this object will pre-touch pages in the supplied queue's underlying store file,
 * attempting to keep ahead of any appenders to the queue.
 * <p>
 * Resources held by this object will be released when the underlying queue is closed.
 * <p>
 * Alternatively, the {@code shutdown()} method can be called to close the supplied queue and release any other resources.
 * Invocation of the {@code execute()} method after {@code shutdown()} has been called with cause an {@code IllegalStateException} to be thrown.
 */
public final class Pretoucher implements Closeable {
    private final long PRETOUCHER_PREROLL_TIME_MS = Long.getLong("SingleChronicleQueueExcerpts.pretoucherPrerollTimeMs", 2_000L);
    private final boolean EARLY_ACQUIRE_NEXT_CYCLE = Boolean.getBoolean("SingleChronicleQueueExcerpts.earlyAcquireNextCycle");
    private final SingleChronicleQueue queue;
    private final NewChunkListener chunkListener;
    private final IntConsumer cycleChangedListener;
    private final PretoucherState pretoucherState;
    private final TimeProvider pretouchTimeProvider;
    private int currentCycle = Integer.MIN_VALUE;
    private WireStore currentCycleWireStore;
    private MappedBytes currentCycleMappedBytes;

    public Pretoucher(final SingleChronicleQueue queue) {
        this(queue, null, c -> {
        });
    }

    // visible for testing
    Pretoucher(final SingleChronicleQueue queue, final NewChunkListener chunkListener,
               final IntConsumer cycleChangedListener) {
        this.queue = queue;
        this.chunkListener = chunkListener;
        this.cycleChangedListener = cycleChangedListener;
        queue.addCloseListener(this, Pretoucher::releaseResources);
        pretoucherState = new PretoucherState(this::getStoreWritePosition);
        pretouchTimeProvider = () -> queue.time().currentTimeMillis() + (EARLY_ACQUIRE_NEXT_CYCLE ? PRETOUCHER_PREROLL_TIME_MS : 0);
    }

    public void execute() throws InvalidEventHandlerException {
        assignCurrentCycle();
        try {
            pretoucherState.pretouch(currentCycleMappedBytes);
        } catch (IllegalStateException e) {
            if (queue.isClosed())
                throw new InvalidEventHandlerException(e);
            else
                Jvm.warn().on(getClass(), e);
        }
    }

    public void shutdown() {
        queue.close();
    }

    /**
     * used by the pretoucher to acquire the next cycle file, but does NOT do the roll. If configured,
     * we acquire the cycle file early
     */
    private void assignCurrentCycle() {
        final int qCycle = queue.cycle(pretouchTimeProvider);
        if (qCycle != currentCycle) {
            releaseResources();

            queue.writeLock().lock();
            try {
                currentCycleWireStore = queue.storeForCycle(qCycle, queue.epoch(), true);
            } finally {
                queue.writeLock().unlock();
            }

            currentCycleMappedBytes = currentCycleWireStore.bytes();
            currentCycle = qCycle;
            if (chunkListener != null)
                currentCycleMappedBytes.setNewChunkListener(chunkListener);

            cycleChangedListener.accept(qCycle);

            if (EARLY_ACQUIRE_NEXT_CYCLE)
                if (Jvm.isDebugEnabled(getClass()))
                    Jvm.debug().on(getClass(), "Pretoucher ROLLING early to next file=" + currentCycleWireStore.file());
        }
    }

    private long getStoreWritePosition() {
        return currentCycleWireStore.writePosition();
    }

    private void releaseResources() {
        if (currentCycleWireStore != null) {
            queue.release(currentCycleWireStore);
            currentCycleWireStore = null;
        }
        if (currentCycleMappedBytes != null) {
            currentCycleMappedBytes.close();
            currentCycleMappedBytes = null;
        }
    }

    @Override
    public void close() {
        releaseResources();
    }
}