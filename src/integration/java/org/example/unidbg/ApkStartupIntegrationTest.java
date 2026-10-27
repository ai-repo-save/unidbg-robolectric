package org.example.unidbg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aireposave.unidbg.runtime.client.RunRequest;
import io.github.aireposave.unidbg.runtime.client.RunResult;
import io.github.aireposave.unidbg.runtime.client.RuntimeClient;
import io.github.aireposave.unidbg.runtime.spi.WorkerEntrypoint;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.dongliu.apk.parser.ApkFile;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class ApkStartupIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test public void startsWorkerAndPersistsWitness() throws Exception {
        Path runtime = Path.of(System.getProperty("integration.runtime")).toAbsolutePath();
        Path evidence = Path.of(System.getProperty("integration.evidenceDir")).toAbsolutePath();
        Path apk = Path.of(System.getProperty("integration.apk")).toAbsolutePath();
        String nonce = UUID.randomUUID().toString();
        var profile = JSON.createObjectNode().put("androidSdk", 30);
        profile.putObject("package").put("apk", apk.toString());
        var request = new RunRequest(runtime, Fixture.class.getName(),
                Arrays.stream(System.getProperty("integration.fixtureClasspath").split(File.pathSeparator)).map(Path::of).toList(),
                JSON.createObjectNode().put("nonce", nonce), RunRequest.Mode.ANDROID, profile,
                Path.of(System.getProperty("user.dir")), evidence, Duration.ofSeconds(1), Duration.ofSeconds(5), Map.of(),
                UUID.randomUUID().toString().replace("-", ""));
        try (var handle = new RuntimeClient().start(request)) {
            RunResult result = handle.completion().get(90, TimeUnit.SECONDS);
            assertEquals(result.failure().toString(), RunResult.Status.COMPLETED, result.status());
        }
        assertEquals(nonce, Files.readString(evidence.resolve("startup.bin")));
        JsonNode observed = JSON.readTree(evidence.resolve("startup.json").toFile());
        String source = "jar:" + runtime.resolve("lib/apk-parser-2.6.11.jar").toUri().toURL()
                + "!/net/dongliu/apk/parser/ApkFile.class";
        assertEquals(source, observed.required("parser_class_resource").asText());
        assertEquals(JSON.valueToTree(List.of(source)),
                observed.required("parser_resources"));
    }

    public static final class Fixture implements WorkerEntrypoint {
        @Override public JsonNode run(WorkerSession session, JsonNode arguments) throws Exception {
            var observed = JSON.createObjectNode();
            observed.put("application_package", session.application().getPackageName());
            observed.put("parser_class_resource", ApkFile.class.getResource("ApkFile.class").toString());
            var resources = observed.putArray("parser_resources");
            for (var resource : Collections.list(getClass().getClassLoader().getResources("net/dongliu/apk/parser/ApkFile.class"))) {
                resources.add(resource.toString());
            }
            Files.writeString(session.outputDirectory().resolve("startup.json"), JSON.writeValueAsString(observed), StandardOpenOption.CREATE_NEW);
            Files.writeString(session.outputDirectory().resolve("startup.bin"), arguments.required("nonce").asText(), StandardOpenOption.CREATE_NEW);
            return observed;
        }
    }
}
