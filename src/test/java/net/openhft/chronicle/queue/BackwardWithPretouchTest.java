package net.openhft.chronicle.queue;

import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BackwardWithPretouchTest extends ChronicleQueueTestBase {

    @Test
    public void testAppenderBackwardWithPretoucher() {
        test(1000);
    }

    @Test
    public void testAppenderBackwardWithPretoucherPause2Seconds() {
        test(2000);
    }

    @Test
    public void testAppenderBackwardWithPretoucherPause3Seconds() {
        test(3000);
    }

    private void test(final int pause) {
        SetTimeProvider timeProvider = new SetTimeProvider();
        timeProvider.currentTimeMillis(System.currentTimeMillis());
        File tmpDir = getTmpDir();
        SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(tmpDir).timeProvider(timeProvider).rollCycle(RollCycles.TEST_SECONDLY).build();
        ExcerptAppender excerptAppender = queue.acquireAppender();
        try (DocumentContext dc = excerptAppender.writingDocument()) {
            dc.wire().write("hello").text("world");
        }

        timeProvider.advanceMillis(pause);

        {
            ExcerptTailer tailer = queue.createTailer().direction(TailerDirection.BACKWARD);
            tailer.toEnd();
            try (DocumentContext dc = tailer.readingDocument()) {
                assertEquals("world", dc.wire().read("hello").text());
            }
        }

        // pretouch to create next cycle file  ----- IF YOU COMMENT THIS LINE THE TEST PASSES
        excerptAppender.pretouch();

        {
            ExcerptTailer tailer = queue.createTailer().direction(TailerDirection.BACKWARD);
            tailer.toEnd();
            try (DocumentContext dc = tailer.readingDocument()) {
                assertTrue(dc.isPresent());
                assertEquals("world", dc.wire().read("hello").text());
            }
        }
    }

}
