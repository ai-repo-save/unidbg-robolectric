package org.example.unidbg;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BlockingChild {
    public static void main(String[] arguments) throws Exception {
        signalReady(Path.of(arguments[0]));
        Thread.sleep(Long.MAX_VALUE);
    }

    static void signalReady(Path target) throws Exception {
        Path pending = target.resolveSibling(target.getFileName() + ".pending");
        Files.writeString(pending, "ready");
        Files.move(pending, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
