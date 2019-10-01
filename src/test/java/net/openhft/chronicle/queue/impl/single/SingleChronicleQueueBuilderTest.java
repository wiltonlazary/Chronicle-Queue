package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.DirectoryUtils;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.Wires;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class SingleChronicleQueueBuilderTest {
    private static final String TEST_QUEUE_FILE = "src/test/resources/tr2/20170320.cq4";

    @Test
    public void shouldDetermineQueueDirectoryFromQueueFile() {
        final Path path = Paths.get(OS.USER_DIR, TEST_QUEUE_FILE);
        try (final ChronicleQueue queue =
                     ChronicleQueue.singleBuilder(path)
                             .testBlockSize()
                             .build()) {
            assertThat(queue.createTailer().readingDocument().isPresent(), is(false));
        } finally {
            IOTools.deleteDirWithFiles(path.toFile(), 20);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionIfQueuePathIsFileWithIncorrectExtension() throws Exception {
        final File tempFile = File.createTempFile(SingleChronicleQueueBuilderTest.class.getSimpleName(), ".txt");
        tempFile.deleteOnExit();
        SingleChronicleQueueBuilder.
                binary(tempFile);
    }

    @Test
    public void setAllNullFields() {
        SingleChronicleQueueBuilder b1 = SingleChronicleQueueBuilder.builder();
        SingleChronicleQueueBuilder b2 = SingleChronicleQueueBuilder.builder();
        b1.blockSize(1234567);
        b2.bufferCapacity(98765);
        b2.setAllNullFields(b1);
        assertEquals(1234567, b2.blockSize());
        assertEquals(98765, b2.bufferCapacity());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setAllNullFieldsShouldFailWithDifferentHierarchy() {
        SingleChronicleQueueBuilder b1 = Wires.tupleFor(SingleChronicleQueueBuilder.class, "ChronicleQueueBuilder");
        SingleChronicleQueueBuilder b2 = SingleChronicleQueueBuilder.builder();
        b2.bufferCapacity(98765);
        b1.blockSize(1234567);
        b2.setAllNullFields(b1);
    }

    @Test
    public void readMarshallable() {
        SingleChronicleQueueBuilder builder = Marshallable.fromString("!net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder {\n" +
                "  writeBufferMode: None,\n" +
                "  readBufferMode: None,\n" +
                "  wireType: BINARY_LIGHT,\n" +
                "  path: " + DirectoryUtils.tempDir("marshallable") +
                "  rollCycle: !net.openhft.chronicle.queue.RollCycles DAILY,\n" +
                "  onRingBufferStats: !net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder$NoBytesRingBufferStats NONE,\n" +
                "  timeProvider: !net.openhft.chronicle.core.time.SystemTimeProvider INSTANCE,\n" +
                "}\n");
        builder.build().close();
    }
}