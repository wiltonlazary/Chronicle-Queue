package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.MappedFile;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.core.time.TimeProvider;
import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.queue.impl.StoreFileListener;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.Wires;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RollCycleTest {
    @Test
    public void newRollCycleIgnored() throws Exception {
        File path = DirectoryUtils.tempDir("newRollCycleIgnored");
        SetTimeProvider timeProvider = new SetTimeProvider();
        ParallelQueueObserver observer = new ParallelQueueObserver(timeProvider, path.toPath());

        try (ChronicleQueue queue = SingleChronicleQueueBuilder
                .fieldlessBinary(path)
                .testBlockSize()
                .rollCycle(RollCycles.DAILY)
                .timeProvider(timeProvider)
                .build()) {
            ExcerptAppender appender = queue.acquireAppender();

            Thread thread = new Thread(observer);
            thread.start();

            observer.await();

            // two days pass
            timeProvider.advanceMillis(TimeUnit.DAYS.toMillis(2));

            appender.writeText("0");

            // allow parallel tailer to finish iteration
            for (int i = 0; i < 5_000 && observer.documentsRead != 1; i++) {
                timeProvider.advanceMicros(100);
                Thread.sleep(1);
            }

            thread.interrupt();
        }

        assertEquals(1, observer.documentsRead);
        observer.queue.close();
    }

    @Test
    public void newRollCycleIgnored2() throws Exception {
        File path = DirectoryUtils.tempDir("newRollCycleIgnored2");

        SetTimeProvider timeProvider = new SetTimeProvider();
        ParallelQueueObserver observer = new ParallelQueueObserver(timeProvider, path.toPath());

        int cyclesToWrite = 100;
        try (ChronicleQueue queue = SingleChronicleQueueBuilder.fieldlessBinary(path)
                .testBlockSize()
                .rollCycle(RollCycles.DAILY)
                .timeProvider(timeProvider)
                .build()) {
            ExcerptAppender appender = queue.acquireAppender();
            appender.writeText("0");

            Thread thread = new Thread(observer);
            thread.start();

            observer.await();

            for (int i = 1; i <= cyclesToWrite; i++) {
                // two days pass
                timeProvider.advanceMillis(TimeUnit.DAYS.toMillis(2));
                appender.writeText(Integer.toString(i));
            }

            // allow parallel tailer to finish iteration
            for (int i = 0; i < 5_000 && observer.documentsRead != 1 + cyclesToWrite; i++) {
                Thread.sleep(1);
            }

            thread.interrupt();
        }

        assertEquals(1 + cyclesToWrite, observer.documentsRead);
        observer.queue.close();
    }

    @Test
    public void testWriteToCorruptedFile() {

        File dir = DirectoryUtils.tempDir("testWriteToCorruptedFile");
        try (ChronicleQueue queue = SingleChronicleQueueBuilder
                .binary(dir)
                .testBlockSize()
                .rollCycle(RollCycles.TEST_DAILY)
                .build()) {

            ExcerptAppender appender = queue.acquireAppender();

            try (DocumentContext dc = appender.writingDocument()) {
                dc.wire().write().text("hello world");
            }
            Bytes bytes;
            long pos;
            try (DocumentContext dc = appender.writingDocument()) {
                bytes = dc.wire().bytes();
                pos = bytes.writePosition() - 4;
            }

            // write as not complete.
            bytes.writeInt(pos, Wires.NOT_COMPLETE_UNKNOWN_LENGTH);

            try (DocumentContext dc = appender.writingDocument()) {
                dc.wire().write().text("hello world 2");
            }

            try (DocumentContext dc = appender.writingDocument()) {
                dc.wire().write().text("hello world 3");
            }
        }
    }

    @After
    public void checkMappedFiles() {
        MappedFile.checkMappedFiles();
    }

    @After
    public void clearInterrupt() {
        Thread.interrupted();
    }

    class ParallelQueueObserver implements Runnable, StoreFileListener {
        ChronicleQueue queue;
        CountDownLatch progressLatch;
        volatile int documentsRead;

        public ParallelQueueObserver(TimeProvider timeProvider, @NotNull Path path) {
            queue = SingleChronicleQueueBuilder.fieldlessBinary(path.toFile())
                    .testBlockSize()
                    .rollCycle(RollCycles.DAILY)
                    .timeProvider(timeProvider)
                    .storeFileListener(this)
                    .build();

            documentsRead = 0;
            progressLatch = new CountDownLatch(1);
        }

        @Override
        public void run() {

            ExcerptTailer tailer = queue.createTailer();

            progressLatch.countDown();

            int lastDocId = -1;
            while (!Thread.currentThread().isInterrupted()) {

                String readText = tailer.readText();
                if (readText != null) {
                    System.out.println("Read a document " + readText);
                    documentsRead++;
                    int docId = Integer.parseInt(readText);
                    assertTrue(docId == lastDocId + 1);
                    lastDocId = docId;
                }
            }
        }

        public void await() throws Exception {
            progressLatch.await();
        }

        public int documentsRead() {
            return documentsRead;
        }

        @Override
        public void onAcquired(int cycle, File file) {
            System.out.println("Acquiring " + file);
        }

        @Override
        public void onReleased(int cycle, File file) {
            System.out.println("Releasing " + file);
        }
    }
}