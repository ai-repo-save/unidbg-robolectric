package org.example.unidbg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aireposave.unidbg.runtime.client.RunRequest;
import io.github.aireposave.unidbg.runtime.client.RunResult;
import io.github.aireposave.unidbg.runtime.client.RuntimeClient;
import io.github.aireposave.unidbg.runtime.client.SessionHandle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RuntimeIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path runtime = Path.of(System.getProperty("integration.runtime"));
    private final Path evidence = Path.of(System.getProperty("integration.evidenceDir"));
    private final String taskClasspath = System.getProperty("integration.fixtureClasspath");
    private final RuntimeClient client = new RuntimeClient();

    @Test
    public void independentWorkersAndNativeSideEffects() throws Exception {
        Files.createDirectories(evidence);
        long firstPid = android("first");
        long secondPid = android("second");
        assertEquals(false, firstPid == secondPid);
        Path nativeRuntime = evidence.resolve("native-installation");
        Files.createDirectories(nativeRuntime);
        Files.createSymbolicLink(nativeRuntime.resolve("lib"), runtime.resolve("lib"));
        Files.copy(runtime.resolve("jvm-args.txt"), nativeRuntime.resolve("jvm-args.txt"));
        try (SessionHandle handle = client.start(request("native", RunRequest.Mode.NATIVE_ONLY, nativeRuntime))) {
            RunResult result = complete(handle);
            assertEquals(RunResult.Status.COMPLETED, result.status());
            assertEquals(false, result.payload().required("android").booleanValue());
            assertEquals(handle.pid(), result.payload().required("pid").longValue());
            assertClosed(handle);
        }
        try (SessionHandle handle = client.start(request("fail", RunRequest.Mode.NATIVE_ONLY, nativeRuntime))) {
            assertEquals(RunResult.Status.FAILED, complete(handle).status());
            assertClosed(handle);
        }
        try (SessionHandle handle = client.start(request("service", RunRequest.Mode.NATIVE_ONLY, nativeRuntime))) {
            String id = handle.receive().required("ready").asText();
            for (int count = 1; count <= 2; count++) {
                var message = JSON.createObjectNode().put("value", count);
                handle.send(message);
                var actual = handle.receive();
                assertEquals(JSON.createObjectNode().put("session", id).put("count", count).set("value", message), actual);
            }
            handle.send(JSON.createObjectNode().put("close", true));
            assertEquals(2, complete(handle).payload().required("count").intValue());
            assertClosed(handle);
        }
        for (String operation : new String[] {"cooperative", "cooperative-child", "blocked"}) {
            try (SessionHandle handle = client.start(request(operation, RunRequest.Mode.NATIVE_ONLY, nativeRuntime))) {
                awaitFile(handle.outputDirectory().resolve("ready"));
                long child = operation.equals("cooperative") ? 0 : Long.parseLong(Files.readString(handle.outputDirectory().resolve("child.pid")));
                handle.cancel();
                RunResult result = complete(handle);
                assertEquals(RunResult.Status.CANCELLED, result.status());
                if (operation.equals("cooperative-child")) assertEquals(130, result.exitCode());
                assertEquals(false, ProcessHandle.of(handle.pid()).map(ProcessHandle::isAlive).orElse(false));
                if (child != 0) assertEquals(false, ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
                if (!operation.equals("blocked")) assertClosed(handle);
            }
        }
    }

    private long android(String label) throws Exception {
        RunRequest request = request(label, RunRequest.Mode.ANDROID, runtime);
        try (SessionHandle handle = client.start(request)) {
            RunResult result = complete(handle);
            assertEquals(result.failure().toString(), RunResult.Status.COMPLETED, result.status());
            assertEquals(handle.pid(), result.payload().required("pid").longValue());
            var expectedPaths = expectedFilePaths(Files.readString(handle.outputDirectory().resolve("application-data.location")),
                    request.profile().required("filesystem").required("guestDataDirectory").textValue());
            var actualPaths = JSON.readTree(handle.outputDirectory().resolve("file-paths.json").toFile());
            assertEquals(expectedPaths, actualPaths.required("constructed"));
            assertEquals(expectedPaths, actualPaths.required("wrapped"));
            assertEquals(false, handle.pid() == ProcessHandle.current().pid());
            assertEquals("org.example." + label, result.payload().required("package").textValue());
            assertEquals("JNI-model-" + label, Files.readString(handle.outputDirectory().resolve("observed-model")));
            assertArrayEquals(("native-JNI-model-" + label).getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(handle.outputDirectory().resolve("application-data/files/cross-boundary.bin")));
            assertArrayEquals("file-query-content".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(handle.outputDirectory().resolve("query-content.bin")));
            assertArrayEquals("open-1open-2".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(handle.outputDirectory().resolve("controlled-query.bin")));
            try (var entries = Files.walk(handle.outputDirectory().resolve("native-read"))) {
                for (Path path : entries.filter(Files::isRegularFile).toList()) {
                    Path relative = handle.outputDirectory().resolve("native-read").relativize(path);
                    assertArrayEquals(Files.readAllBytes(handle.outputDirectory().resolve("application-data").resolve(relative)), Files.readAllBytes(path));
                }
            }
            assertClosed(handle);
            return handle.pid();
        }
    }

    private RunRequest request(String operation, RunRequest.Mode mode, Path installation) {
        var arguments = JSON.createObjectNode().put("operation", mode == RunRequest.Mode.ANDROID ? "android" : operation)
                .put("label", operation).put("childClasspath", taskClasspath);
        var profile = JSON.createObjectNode();
        if (mode == RunRequest.Mode.ANDROID) {
            profile.putObject("package").put("name", "org.example." + operation).put("versionName", "1.0").put("versionCode", 1).put("targetSdk", 30)
                    .put("uid", operation.equals("first") ? 10001 : 10002).put("installer", "org.example.installer");
            profile.putArray("packages").addObject().put("name", "org.example.sideloaded").putNull("installer");
            profile.put("processName", "org.example." + operation + ":worker");
            profile.putObject("processCredentials").put("euid", operation.equals("first") ? 11001 : 11002)
                    .put("gid", 20001).put("egid", 20002).putArray("groups").add(3003).add(9997);
            profile.putObject("settings").putObject("global").put("library_seed", "seed-" + operation);
            profile.putObject("preferences").putObject("seeded").put("initial", "seeded-" + operation);
            var build = profile.putObject("build").put("MODEL", "JNI-model-" + operation)
                    .put("ID", "ZP11.260821.010").put("DISPLAY", "ONEPLUS A6010_41_210928")
                    .put("TAGS", "release-keys").put("TYPE", "user")
                    .put("FINGERPRINT", "google/tokay_beta/tokay:CANARY/ZP11.260821.010/16290768:user/release-keys");
            build.putObject("VERSION").put("RELEASE", "CANARY").put("INCREMENTAL", "16290768")
                    .put("SECURITY_PATCH", "2026-09-05");
            profile.putObject("nativeProperties").put("ro.product.model", "ONEPLUS A6010")
                    .put("ro.build.fingerprint", "OnePlus/OnePlus6T/OnePlus6T:11/RKQ1.201217.002/2109280555:user/release-keys")
                    .put("ro.build.id", "RKQ1.201217.002").put("ro.build.version.release", "11")
                    .put("ro.build.version.incremental", "2109280555").put("ro.build.version.security_patch", "2021-09-01");
            profile.putObject("filesystem").put("guestDataDirectory", "/data/user/0/org.example." + operation);
        }
        return new RunRequest(installation, RuntimeFixture.class.getName(),
                Arrays.stream(taskClasspath.split(File.pathSeparator)).map(Path::of).toList(), arguments, mode, profile,
                Path.of(System.getProperty("user.dir")), evidence.resolve(operation),
                Duration.ofMillis(operation.equals("blocked") ? 50 : 1000), Duration.ofSeconds(5), java.util.Map.of(),
                UUID.randomUUID().toString().replace("-", ""));
    }
    private static JsonNode expectedFilePaths(String data, String guestData) {
        String[] paths = {"", "relative\0path", "relative//./child/../leaf/", "/outside//./child/../leaf/",
                guestData + "/files/\0leaf", data + "-sibling/\0leaf", data + "\0suffix", guestData, guestData,
                guestData + "/files/./child/../leaf", guestData + "/files/\0leaf", guestData + "/../sibling", guestData + "/."};
        var expected = JSON.createArrayNode();
        for (String path : paths) expected.add(new File(path).getPath());
        return expected;
    }

    private static RunResult complete(SessionHandle handle) throws Exception { return handle.completion().get(90, TimeUnit.SECONDS); }
    private static void assertClosed(SessionHandle handle) throws Exception {
        assertEquals("closed", Files.readString(handle.outputDirectory().resolve("resource-closed")));
    }
    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.isRegularFile(file) && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals("ready", Files.readString(file));
    }
}
