package net.openhft.chronicle.queue.reader;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.wire.BinaryWire;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireType;

import java.util.function.Consumer;

public final class MethodReaderQueueEntryHandler implements QueueEntryHandler {
    private final Bytes textConversionTarget = Bytes.elasticByteBuffer();
    private final WireType wireType;

    public MethodReaderQueueEntryHandler(WireType wireType) {
        this.wireType = wireType;
    }

    @Override
    public void accept(final WireIn wireIn, final Consumer<String> messageHandler) {
        long elementCount = 0;
        while (wireIn.hasMore()) {
            new BinaryWire(wireIn.bytes()).copyOne(wireType.apply(textConversionTarget));

            elementCount++;
            if ((elementCount & 1) == 0) {
                messageHandler.accept(textConversionTarget.toString());
                textConversionTarget.clear();
            }
        }
    }

    @Override
    public void close() {
        textConversionTarget.release();
    }
}