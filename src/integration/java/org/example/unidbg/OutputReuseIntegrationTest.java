package org.example.unidbg;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aireposave.unidbg.runtime.client.RunRequest;
import io.github.aireposave.unidbg.runtime.client.RunResult;
import io.github.aireposave.unidbg.runtime.client.RuntimeClient;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OutputReuseIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void reuseOutputAfterCancellationAndThroughCli() throws Exception {
        Path runtime = Path.of(System.getProperty("integration.runtime"));
        Path output = Path.of(System.getProperty("integration.evidenceDir")).resolve("session");
        String classpath = System.getProperty("integration.fixtureClasspath");
        RuntimeClient client = new RuntimeClient();
        try (var handle = client.start(request(runtime, output, classpath, "cooperative"))) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            Path ready = output.resolve("ready");
            while (!Files.isRegularFile(ready) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("ready", Files.readString(ready));
            handle.cancel();
            assertEquals(RunResult.Status.CANCELLED, handle.completion().get(30, TimeUnit.SECONDS).status());
        }
        try (var handle = client.start(request(runtime, output, classpath, "native"))) {
            RunResult result = handle.completion().get(30, TimeUnit.SECONDS);
            assertEquals(RunResult.Status.COMPLETED, result.status());
            assertEquals(handle.pid(), result.payload().required("pid").longValue());
        }
        Files.delete(output.resolve("resource-closed"));
        Path arguments = output.getParent().resolve("arguments.json");
        Files.writeString(arguments, JSON.writeValueAsString(JSON.createObjectNode().put("operation", "native")));
        Process cli = new ProcessBuilder(runtime.resolve("bin/unidbg-worker").toString(),
                "--mode", "NATIVE_ONLY", "--entrypoint", RuntimeFixture.class.getName(),
                "--arguments", arguments.toString(), "--task-classpath", classpath,
                "--output-dir", output.toString()).redirectOutput(output.resolve("cli.stdout").toFile())
                .redirectError(output.resolve("cli.stderr").toFile()).start();
        try {
            assertEquals(true, cli.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, cli.exitValue());
            assertEquals("closed", Files.readString(output.resolve("resource-closed")));
        } finally {
            if (cli.isAlive()) {
                cli.destroyForcibly();
                cli.waitFor();
            }
        }
    }

    private static RunRequest request(Path runtime, Path output, String classpath, String operation) {
        return new RunRequest(runtime, RuntimeFixture.class.getName(),
                Arrays.stream(classpath.split(File.pathSeparator)).map(Path::of).toList(),
                JSON.createObjectNode().put("operation", operation), RunRequest.Mode.NATIVE_ONLY,
                JSON.createObjectNode(), Path.of(System.getProperty("user.dir")), output,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Map.of(), UUID.randomUUID().toString());
    }
}
