/*
 * Copyright 2016 higherfrequencytrading.com
 *
 */

package net.openhft.chronicle.queue;

import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.IOTools;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class DirectoryUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryUtils.class);
    private static final AtomicLong TIMESTAMPER = new AtomicLong(System.currentTimeMillis());

    /**
     * Beware, this can give different results depending on whether you are
     * a) running inside maven
     * b) are running in a clean directory (without a "target" dir)
     * See OS.TARGET
     */
    @NotNull
    public static File tempDir(String name) {
        String replacedName = name.replaceAll("[\\[\\]\\s]+", "_").replace(':', '_');
        final File tmpDir = new File(OS.TARGET, replacedName + "-" + Long.toString(TIMESTAMPER.getAndIncrement(), 36));
        DeleteStatic.INSTANCE.add(tmpDir);

        // Log the temporary directory in OSX as it is quite obscure
        if (OS.isMacOSX()) {
            LOGGER.info("Tmp dir: {}", tmpDir);
        }

        return tmpDir;
    }

    public static void deleteDir(@NotNull File dir) {
        try {
            IOTools.deleteDirWithFiles(dir, 20);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    enum DeleteStatic {
        INSTANCE;
        final Set<File> toDeleteList = Collections.synchronizedSet(new LinkedHashSet<>());

        {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> toDeleteList.forEach(DirectoryUtils::deleteDir)
            ));
        }

        synchronized void add(File path) {
            toDeleteList.add(path);
        }
    }
}
