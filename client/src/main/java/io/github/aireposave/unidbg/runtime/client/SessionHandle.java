package io.github.aireposave.unidbg.runtime.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SessionHandle implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Process process;
    private final RunRequest request;
    private final Path outputDirectory;
    private final String sessionId;
    private final BufferedReader messages;
    private final BufferedWriter commands;
    private final CompletableFuture<RunResult> completion = new CompletableFuture<>();
    private volatile boolean cancelled;

    SessionHandle(Process process, RunRequest request, Path outputDirectory, String sessionId) {
        this.process = process;
        this.request = request;
        this.outputDirectory = outputDirectory;
        this.sessionId = sessionId;
        messages = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("unidbg-session-" + process.pid()).start(() -> {
            try {
                int code = process.waitFor();
                synchronized (SessionHandle.this) {
                    Path result = outputDirectory.resolve("result.json");
                    JsonNode value = Files.isRegularFile(result) ? JSON.readTree(result.toFile()) : JSON.createObjectNode();
                    RunResult.Status status = cancelled || value.path("status").asText().equals("CANCELLED") ? RunResult.Status.CANCELLED
                            : code == 0 && value.path("status").asText().equals("COMPLETED")
                            ? RunResult.Status.COMPLETED : RunResult.Status.FAILED;
                    completion.complete(new RunResult(status, code, process.pid(), value.path("payload"),
                            value.path("failure"), outputDirectory));
                }
            } catch (IOException | InterruptedException failure) {
                completion.completeExceptionally(failure);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        });
    }

    public long pid() { return process.pid(); }
    public Path outputDirectory() { return outputDirectory; }
    public CompletableFuture<RunResult> completion() { return completion; }
    public synchronized void send(JsonNode message) throws IOException {
        commands.write(JSON.writeValueAsString(message));
        commands.newLine();
        commands.flush();
    }
    public JsonNode receive() throws IOException {
        String line = messages.readLine();
        return line == null ? NullNode.instance : JSON.readTree(line);
    }
    public void endInput() throws IOException { commands.close(); }

    public synchronized void cancel() throws IOException, InterruptedException {
        if (!process.isAlive()) return;
        CancellationTrace trace = new CancellationTrace(request, sessionId, process.pid(), outputDirectory);
        ProcessTree tree = ProcessTree.capture(process, trace);
        cancelled = true;
        boolean interrupted = Thread.interrupted();
        Throwable cleanupFailure = null;
        try {
            trace.phase("GRACE", request.cancellationGrace().toMillis(), java.util.List.of(process.toHandle()));
            IOException signalFailure = null;
            try { Files.writeString(outputDirectory.resolve("cancel.request"), "cancel\n"); }
            catch (IOException failure) { signalFailure = failure; }
            boolean rootExited = !process.isAlive();
            if (!interrupted && signalFailure == null && !rootExited) {
                trace.waiting(process.toHandle(), request.cancellationGrace().toNanos());
                try {
                    rootExited = process.waitFor(request.cancellationGrace().toMillis(), TimeUnit.MILLISECONDS);
                    trace.waited(process.toHandle(), rootExited);
                } catch (InterruptedException failure) { interrupted = true; }
            }
            trace.graceCompleted(rootExited);
            tree.terminate(request.terminationWait());
            if (signalFailure != null) throw signalFailure;
        } catch (IOException | RuntimeException | Error failure) {
            cleanupFailure = failure;
            completion.completeExceptionally(failure);
            throw failure;
        } finally {
            try { trace.finish(tree.handles(), cleanupFailure); }
            catch (IOException evidenceFailure) {
                if (cleanupFailure != null) cleanupFailure.addSuppressed(evidenceFailure);
                else { completion.completeExceptionally(evidenceFailure); throw evidenceFailure; }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Interrupted while cancelling session " + process.pid());
    }

    @Override
    public void close() throws IOException, InterruptedException {
        try { cancel(); }
        finally { commands.close(); messages.close(); }
    }
}
