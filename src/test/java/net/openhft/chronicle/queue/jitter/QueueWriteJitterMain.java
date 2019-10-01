package net.openhft.chronicle.queue.jitter;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.MappedFile;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;

public class QueueWriteJitterMain {
    public static final String PROFILE_OF_THE_THREAD = "profile of the thread";

    static int runTime = Integer.getInteger("runTime", 600); // seconds
    static int size = Integer.getInteger("size", 128); // bytes
    static int sampleTime = Integer.getInteger("sampleTime", 30); // micro-seconds
    static volatile boolean running = true;
    static volatile long writeStarted = Long.MAX_VALUE;

    static {
        System.setProperty("jvm.safepoint.enabled", "true");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
    }

    public static void main(String[] args) {
        new QueueWriteJitterMain().run();
    }

    protected void run() {
        MappedFile.warmup();

        String path = OS.TMP + "/test-q-" + System.nanoTime();
        System.out.println("Writing to " + path);

        Thread pretoucher = new Thread(() -> {
            try (ChronicleQueue q = createQueue(path)) {
                ExcerptAppender appender = q.acquireAppender();
                while (true) {
                    Thread.sleep(50);
                    appender.pretouch();
                }
            } catch (InterruptedException ie) {
                if (running)
                    ie.printStackTrace();
            }
        });
        pretoucher.setDaemon(true);
        pretoucher.start();

        Thread writer = new Thread(() -> {
            try (ChronicleQueue q = createQueue(path)) {
                ExcerptAppender appender = q.acquireAppender();
                while (running) {
                    writeStarted = System.nanoTime();
                    Jvm.safepoint();
                    try (DocumentContext dc = appender.writingDocument(false)) {
                        Jvm.safepoint();
                        Bytes<?> bytes = dc.wire().bytes();
                        for (int i = 0; i < size; i += 8)
                            bytes.writeLong(i);
                        Jvm.safepoint();
                    }
                    Jvm.safepoint();
                    writeStarted = Long.MAX_VALUE;
                    waitForNext(Math.min(100, sampleTime));
                }
            }
        });
        writer.setDaemon(true);
        writer.start();
        Jvm.pause(100); // give it time to start

        try (ChronicleQueue q = createQueue(path)) {
            ExcerptTailer tailer = q.createTailer();
            long start0 = System.currentTimeMillis();
            do {
                if (writeStarted < Long.MAX_VALUE) {
                    // overflow exists loop
                    while (writeStarted + sampleTime * 1000 > System.nanoTime())
                        Thread.yield();

                    if (writeStarted < Long.MAX_VALUE) {

                        StackTraceElement[] stes = writer.getStackTrace();
                        if (!stes[1].getMethodName().equals("waitForNext")) {
                            StringBuilder sb = new StringBuilder();
                            sb.append(PROFILE_OF_THE_THREAD);
                            Jvm.trimStackTrace(sb, stes);
                            System.out.println(sb);
                        }
                    }
                }
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent())
                        waitForNext(Math.min(100, sampleTime));
                }
                Thread.yield();

            } while (System.currentTimeMillis() < start0 + runTime * 1_000);
        }
        running = false;
        pretoucher.interrupt();

        IOTools.deleteDirWithFiles(path, 2);
    }

    private void waitForNext(int sampleTime) {
        long start1 = System.nanoTime();
        do {
            Thread.yield();
        } while (System.nanoTime() < start1 + sampleTime * 1000);
        long time1 = System.nanoTime() - start1;
        if (time1 > sampleTime * 1000 * 10) {
            System.out.println("Time paused = " + time1 / 1000 + " us");
        }
    }

    protected ChronicleQueue createQueue(String path) {
        return SingleChronicleQueueBuilder.single(path).testBlockSize().build();
    }
}
