package io.github.aireposave.unidbg.runtime.client;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ProcessTree {
    private final ProcessHandle root;
    private final CancellationTrace trace;
    private final LinkedHashMap<Long, ProcessHandle> known = new LinkedHashMap<>();

    static ProcessTree capture(Process process, CancellationTrace trace) {
        ProcessTree tree = new ProcessTree(process.toHandle(), trace);
        tree.known.put(tree.root.pid(), tree.root);
        try (var descendants = tree.root.descendants()) {
            descendants.forEach(handle -> tree.known.put(handle.pid(), handle));
        }
        trace.captured(tree.handles());
        return tree;
    }

    private ProcessTree(ProcessHandle root, CancellationTrace trace) { this.root = root; this.trace = trace; }
    List<ProcessHandle> handles() { return List.copyOf(known.values()); }

    void terminate(Duration wait) throws IOException {
        boolean[] interrupted = {Thread.interrupted()};
        try {
            refresh();
            var children = known.values().stream().filter(handle -> handle.pid() != root.pid())
                    .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed()).toList();
            var alive = handles().stream().filter(ProcessHandle::isAlive).toList();
            if (alive.isEmpty()) return;
            trace.phase("TERM", wait.toMillis(), alive);
            long deadline = System.nanoTime() + wait.toNanos();
            for (ProcessHandle child : children) signal(child, false);
            for (ProcessHandle child : children) await(child, deadline, interrupted);
            signal(root, false);
            await(root, deadline, interrupted);
            alive = handles().stream().filter(ProcessHandle::isAlive).toList();
            if (!alive.isEmpty()) {
                trace.phase("KILL", wait.toMillis(), alive);
                for (ProcessHandle handle : alive) signal(handle, true);
                long forcedDeadline = System.nanoTime() + wait.toNanos();
                for (ProcessHandle handle : alive) {
                    if (!await(handle, forcedDeadline, interrupted)) throw new IOException("Process did not exit within cancellation budget: " + handle.pid());
                }
            }
        } finally {
            if (interrupted[0]) Thread.currentThread().interrupt();
        }
    }

    private void refresh() {
        var discovered = new ArrayList<ProcessHandle>();
        for (ProcessHandle parent : handles()) {
            if (!parent.isAlive()) continue;
            try (var descendants = parent.descendants()) {
                descendants.forEach(handle -> {
                    if (known.putIfAbsent(handle.pid(), handle) == null) discovered.add(handle);
                });
            }
        }
        if (!discovered.isEmpty()) trace.captured(discovered);
    }

    private void signal(ProcessHandle handle, boolean force) {
        if (handle.isAlive()) trace.signal(handle, force ? handle.destroyForcibly() : handle.destroy());
    }

    private boolean await(ProcessHandle handle, long deadline, boolean[] interrupted) throws IOException {
        if (!handle.isAlive()) return true;
        trace.waiting(handle, deadline - System.nanoTime());
        var exit = handle.onExit();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { trace.waited(handle, false); return false; }
            try { exit.get(remaining, TimeUnit.NANOSECONDS); trace.waited(handle, true); return true; }
            catch (InterruptedException interruption) { interrupted[0] = true; }
            catch (TimeoutException timeout) { trace.waited(handle, false); return false; }
            catch (ExecutionException failure) { throw new IOException("Cannot observe process exit: " + handle.pid(), failure.getCause()); }
        }
    }
}
