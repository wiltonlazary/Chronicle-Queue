package net.openhft.chronicle.queue.bench;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.NativeBytesStore;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.UnsafeMemory;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;

/*
ARMv7 Processor rev 10 (v7l), Quad 1 GHz.
Writing 52,427,575 messages took 22.112 seconds, at a rate of 2,371,039 per second
Reading 52,427,575 messages took 39.190 seconds, at a rate of 1,337,796 per second
 */
public class ThroughputPerfMain2 {
    static final int time = Integer.getInteger("time", 20);
    static final int size = Integer.getInteger("size", 40);
    static final String path = System.getProperty("path", OS.TMP);
    static NativeBytesStore nbs;

    @SuppressWarnings("deprecation")
    public static void main(String[] args) {
        String base = path + "/delete-" + System.nanoTime() + ".me";
        long start = System.nanoTime();
        long count = 0;
        nbs = NativeBytesStore.nativeStoreWithFixedCapacity(size);

        long blockSize = OS.is64Bit() ? 4L << 30 : 256L << 20;
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(base)
                .rollCycle(RollCycles.LARGE_HOURLY_XSPARSE)
                .blockSize(blockSize)
                .build()) {

            ExcerptAppender appender = q.acquireAppender();
            count += appender.batchAppend(time * 1000, ThroughputPerfMain2::writeMessages);
        }

        nbs.release();
        long mid = System.nanoTime();
        long time1 = mid - start;

        Bytes bytes = Bytes.allocateElasticDirect(64);
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(base)
                .rollCycle(RollCycles.LARGE_HOURLY_XSPARSE)
                .blockSize(blockSize)
                .build()) {
            ExcerptTailer tailer = q.createTailer();
            for (long i = 0; i < count; i++) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    bytes.clear();
                    bytes.write(dc.wire().bytes());
                }
            }
        }
        bytes.release();
        long end = System.nanoTime();
        long time2 = end - mid;

        System.out.printf("Writing %,d messages took %.3f seconds, at a rate of %,d per second%n",
                count, time1 / 1e9, (long) (1e9 * count / time1));
        System.out.printf("Reading %,d messages took %.3f seconds, at a rate of %,d per second%n",
                count, time2 / 1e9, (long) (1e9 * count / time2));

        System.gc(); // make sure its cleaned up for windows to delete.
        IOTools.deleteDirWithFiles(base, 2);
    }

    @SuppressWarnings("restriction")
    private static long writeMessages(long address, long canWrite, int writeCount) {
        long length = 0;
        long count = 0;
//        writeCount = writeCount == 1 ? 1 : ThreadLocalRandom.current().nextInt(writeCount-1)+1;
        long fromAddress = nbs.addressForRead(0);
        while (writeCount > count && length + 4 + size <= canWrite) {
            UnsafeMemory.UNSAFE.copyMemory(fromAddress, address + 4, size);
            UnsafeMemory.UNSAFE.putOrderedInt(null, address, size);
            address += 4 + size;
            length += 4 + size;
            count++;
        }
//        System.out.println("w "+count+" "+length);
        return (count << 32) | length;
    }
}
