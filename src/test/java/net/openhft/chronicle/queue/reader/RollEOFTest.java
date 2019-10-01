/*
 * Copyright 2014-2017 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
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
package net.openhft.chronicle.queue.reader;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.core.time.TimeProvider;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.DirectoryUtils;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueStore;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireType;
import net.openhft.chronicle.wire.Wires;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static net.openhft.chronicle.queue.impl.single.SingleChronicleQueue.SUFFIX;
import static org.junit.Assert.*;

public class RollEOFTest {

    @Test(timeout = 5000L)
    public void testRollWritesEOF() throws Exception {
        final File path = DirectoryUtils.tempDir(getClass().getName());
        try {
            path.mkdirs();
            final SetTimeProvider timeProvider = new SetTimeProvider();
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -1);
            timeProvider.currentTimeMillis(cal.getTimeInMillis());
            createQueueAndWriteData(timeProvider, path);
            assertEquals(1, getNumberOfQueueFiles(path));

            // adjust time
            timeProvider.currentTimeMillis(System.currentTimeMillis());
            createQueueAndWriteData(timeProvider, path);
            assertEquals(2, getNumberOfQueueFiles(path));

            List<String> l = new LinkedList<>();
            new ChronicleReader().withMessageSink(l::add).withBasePath(path.toPath()).execute();
            // 2 entries per message
            assertEquals(4, l.size());
        } finally {
            IOTools.deleteDirWithFiles(path, 20);
        }
    }

    @Test(timeout = 5000L)
    public void testRollWithoutEOFDoesntBlowup() throws Exception {
        final File path = DirectoryUtils.tempDir(getClass().getName());
        try {
            path.mkdirs();
            final SetTimeProvider timeProvider = new SetTimeProvider();
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -1);
            timeProvider.currentTimeMillis(cal.getTimeInMillis());
            createQueueAndWriteData(timeProvider, path);
            assertEquals(1, getNumberOfQueueFiles(path));

            // adjust time
            timeProvider.currentTimeMillis(System.currentTimeMillis());
            createQueueAndWriteData(timeProvider, path);
            assertEquals(2, getNumberOfQueueFiles(path));

            Optional<Path> firstQueueFile = Files.list(path.toPath()).filter(p -> p.toString().endsWith(SUFFIX)).sorted().findFirst();

            assertTrue(firstQueueFile.isPresent());

            // remove EOF from first file
            removeEOF(firstQueueFile.get());

            List<String> l = new LinkedList<>();
            new ChronicleReader().withMessageSink(l::add).withBasePath(path.toPath()).execute();
            // 2 entries per message
            assertEquals(4, l.size());
        } finally {

            IOTools.deleteDirWithFiles(path, 20);
        }
    }

    @Test(timeout = 5000L)
    public void testRollWithoutEOF() throws Exception {
        final File path = DirectoryUtils.tempDir(getClass().getName());
        try {
            path.mkdirs();
            final SetTimeProvider timeProvider = new SetTimeProvider();
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -3);
            timeProvider.currentTimeMillis(cal.getTimeInMillis());
            createQueueAndWriteData(timeProvider, path);
            assertEquals(1, getNumberOfQueueFiles(path));

            // adjust time
            timeProvider.currentTimeMillis(System.currentTimeMillis());
            createQueueAndWriteData(timeProvider, path);
            assertEquals(2, getNumberOfQueueFiles(path));

            Optional<Path> firstQueueFile = Files.list(path.toPath()).filter(p -> p.toString().endsWith(SUFFIX)).sorted().findFirst();

            assertTrue(firstQueueFile.isPresent());

            // remove EOF from first file
            removeEOF(firstQueueFile.get());

            List<String> l = new LinkedList<>();
            new ChronicleReader().withMessageSink(l::add).withBasePath(path.toPath()).withReadOnly(false).execute();
            // 2 entries per message
            assertEquals(4, l.size());
        } finally {

            IOTools.deleteDirWithFiles(path, 20);
        }
    }

    private void removeEOF(Path path) throws IOException {
        long blockSize = 64 << 10;
        long chunkSize = OS.pageAlign(blockSize);
        long overlapSize = OS.pageAlign(blockSize / 4);
        final MappedBytes mappedBytes = MappedBytes.mappedBytes(path.toFile(), chunkSize, overlapSize, false);
        mappedBytes.reserve();
        try {
            final Wire wire = WireType.BINARY_LIGHT.apply(mappedBytes);
            final Bytes<?> bytes = wire.bytes();
            bytes.readLimit(bytes.capacity());
            bytes.readSkip(4);
            // move past header
            try (final SingleChronicleQueueStore qs = loadStore(wire)) {
                assertNotNull(qs);
                long l = qs.writePosition();
                long len = Wires.lengthOf(bytes.readVolatileInt(l));
                long eofOffset = l + len + 4L;
                bytes.writePosition(eofOffset);
                bytes.writeInt(0);
            }
        } finally {
            mappedBytes.release();
        }
    }

    private SingleChronicleQueueStore loadStore(Wire wire) {
        try {
            Method loadStoreMethod = SingleChronicleQueueBuilder.class.getDeclaredMethod("loadStore", Wire.class);
            Jvm.setAccessible(loadStoreMethod);
            return (SingleChronicleQueueStore) loadStoreMethod.invoke(null, wire);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private long getNumberOfQueueFiles(final File path) throws IOException {
        return getQueueFilesStream(path).count();
    }

    private Stream<Path> getQueueFilesStream(final File path) throws IOException {
        return Files.list(path.toPath()).filter(p -> p.toString().endsWith(SingleChronicleQueue.SUFFIX));
    }

    private void createQueueAndWriteData(TimeProvider timeProvider, File path) {

        final ChronicleQueue queue = SingleChronicleQueueBuilder
                .binary(path)
                .testBlockSize()
                .rollCycle(RollCycles.TEST_DAILY)
                .timeProvider(timeProvider)
                .build();

        ExcerptAppender excerptAppender = queue.acquireAppender();

        try (DocumentContext dc = excerptAppender.writingDocument(false)) {
            dc.wire().write(() -> "test").int64(0);
        }
    }
}
