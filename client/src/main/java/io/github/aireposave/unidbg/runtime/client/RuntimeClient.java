package io.github.aireposave.unidbg.runtime.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

public final class RuntimeClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    public SessionHandle start(RunRequest request) throws IOException {
        Path output = request.outputDirectory().toAbsolutePath();
        Files.createDirectories(output.resolve("logs"));
        String id = UUID.randomUUID().toString();
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("protocol", RuntimeVersion.PROTOCOL);
        envelope.put("version", RuntimeVersion.VERSION);
        envelope.put("sessionId", id);
        envelope.put("traceId", request.traceId());
        envelope.put("entrypoint", request.entrypoint());
        envelope.put("mode", request.mode().name());
        envelope.set("arguments", request.arguments());
        envelope.set("profile", request.profile());
        envelope.put("outputDirectory", output.toString());
        envelope.put("runtimeDirectory", request.runtimeDirectory().toAbsolutePath().toString());
        var classpath = envelope.putArray("classpath");
        request.classpath().forEach(path -> classpath.add(path.toAbsolutePath().toString()));
        Path input = output.resolve("request.json");
        Files.deleteIfExists(output.resolve("cancel.request"));
        Files.deleteIfExists(output.resolve("result.json"));
        Files.writeString(input, JSON.writeValueAsString(envelope));
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(Files.readAllLines(request.runtimeDirectory().resolve("jvm-args.txt")));
        request.systemProperties().forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.add("-cp");
        command.add(request.runtimeDirectory().toAbsolutePath().resolve("lib/*").toString());
        command.add("io.github.aireposave.unidbg.runtime.worker.WorkerMain");
        command.add("--request");
        command.add(input.toString());
        Process process = new ProcessBuilder(command).directory(request.workingDirectory().toFile())
                .redirectError(output.resolve("logs/stderr.log").toFile()).start();
        return new SessionHandle(process, request, output, id);
    }
}
