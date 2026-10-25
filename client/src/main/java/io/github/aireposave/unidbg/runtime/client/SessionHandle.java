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
    private final BufferedReader messages;
    private final BufferedWriter commands;
    private final CompletableFuture<RunResult> completion = new CompletableFuture<>();
    private volatile boolean cancelled;

    SessionHandle(Process process, RunRequest request, Path outputDirectory) {
        this.process = process;
        this.request = request;
        this.outputDirectory = outputDirectory;
        messages = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("unidbg-session-" + process.pid()).start(() -> {
            try {
                int code = process.waitFor();
                Path result = outputDirectory.resolve("result.json");
                JsonNode value = Files.isRegularFile(result) ? JSON.readTree(result.toFile()) : JSON.createObjectNode();
                RunResult.Status status = cancelled || value.path("status").asText().equals("CANCELLED") ? RunResult.Status.CANCELLED
                        : code == 0 && value.path("status").asText().equals("COMPLETED")
                        ? RunResult.Status.COMPLETED : RunResult.Status.FAILED;
                completion.complete(new RunResult(status, code, process.pid(), value.path("payload"),
                        value.path("failure"), outputDirectory));
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

    public void cancel() throws IOException, InterruptedException {
        if (!process.isAlive()) return;
        cancelled = true;
        boolean interrupted = Thread.interrupted();
        IOException signalFailure = null;
        try { Files.writeString(outputDirectory.resolve("cancel.request"), "cancel\n"); }
        catch (IOException failure) { signalFailure = failure; }
        try {
            if (!interrupted && signalFailure == null) {
                try {
                    if (process.waitFor(request.cancellationGrace().toMillis(), TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException failure) { interrupted = true; }
            }
            ProcessTree.terminate(process, request.terminationWait());
            Files.writeString(outputDirectory.resolve("aborted.json"),
                    "{\"status\":\"CANCELLED\",\"finallyConfirmed\":false,\"pid\":" + process.pid() + "}");
            if (signalFailure != null) throw signalFailure;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Interrupted while cancelling session " + process.pid());
    }

    @Override
    public void close() throws IOException, InterruptedException {
        try { cancel(); }
        finally { commands.close(); messages.close(); }
    }
}
