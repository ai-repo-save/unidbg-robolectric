package io.github.aireposave.unidbg.runtime.client;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ProcessTree {
    static void terminate(Process process, Duration wait) throws IOException {
        boolean[] interrupted = {Thread.interrupted()};
        try {
            terminate(process, wait, interrupted);
        } finally {
            if (interrupted[0]) Thread.currentThread().interrupt();
        }
    }
    private static void terminate(Process process, Duration wait, boolean[] interrupted) throws IOException {
        var children = process.descendants().sorted(Comparator.comparingLong(ProcessHandle::pid).reversed()).toList();
        long deadline = System.nanoTime() + wait.toNanos();
        children.forEach(ProcessHandle::destroy);
        for (ProcessHandle child : children) await(child, deadline, interrupted);
        process.destroy();
        await(process.toHandle(), deadline, interrupted);
        var all = new ArrayList<>(children);
        all.add(process.toHandle());
        for (ProcessHandle handle : all) if (handle.isAlive()) handle.destroyForcibly();
        long forcedDeadline = System.nanoTime() + wait.toNanos();
        for (ProcessHandle handle : all) {
            if (!await(handle, forcedDeadline, interrupted)) throw new IOException("Process did not exit within cancellation budget: " + handle.pid());
        }
    }
    private static boolean await(ProcessHandle handle, long deadline, boolean[] interrupted) throws IOException {
        if (!handle.isAlive()) return true;
        var exit = handle.onExit();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try { exit.get(remaining, TimeUnit.NANOSECONDS); return true; }
            catch (InterruptedException interruption) { interrupted[0] = true; }
            catch (TimeoutException timeout) { return false; }
            catch (ExecutionException failure) { throw new IOException("Cannot observe process exit: " + handle.pid(), failure.getCause()); }
        }
    }
    private ProcessTree() {}
}
