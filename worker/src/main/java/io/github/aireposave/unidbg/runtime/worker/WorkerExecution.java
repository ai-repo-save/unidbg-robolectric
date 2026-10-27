package io.github.aireposave.unidbg.runtime.worker;

import android.app.Application;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aireposave.unidbg.runtime.client.RuntimeVersion;
import io.github.aireposave.unidbg.runtime.spi.WorkerEntrypoint;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.runner.JUnitCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class WorkerExecution {
    public static int execute(String requestPath) throws Exception {
        System.setProperty("unidbg.request", requestPath);
        JsonNode request = RequestData.read();
        Path output = RequestData.output(request);
        Files.createDirectories(output.resolve("logs"));
        System.setProperty("unidbg.output", output.toString());
        PrintStream previous = System.out;
        try (PrintStream diagnostics = new PrintStream(Files.newOutputStream(output.resolve("logs/stdout.log")), true, StandardCharsets.UTF_8)) {
            System.setOut(diagnostics);
            MDC.put("trace_id", request.required("traceId").asText());
            MDC.put("session_id", request.required("sessionId").asText());
            Logger log = LoggerFactory.getLogger(WorkerExecution.class);
            ObjectNode result = RequestData.JSON.createObjectNode();
            result.put("version", RuntimeVersion.VERSION);
            result.put("pid", ProcessHandle.current().pid());
            try {
                log.info("event=worker_started request={} classLoader={}", request, WorkerExecution.class.getClassLoader());
                String mode = request.required("mode").asText();
                switch (mode) {
                    case "NATIVE_ONLY" -> runSession(null);
                    case "ANDROID" -> {
                        prepareAndroid(request);
                        ObjectNode effective = ApkMetadata.resolve((ObjectNode) request);
                        Path effectivePath = output.resolve("effective-request.json");
                        Files.writeString(effectivePath, RequestData.JSON.writeValueAsString(effective));
                        System.setProperty("unidbg.request", effectivePath.toString());
                        String packageName = effective.required("profile").required("package").required("name").textValue();
                        System.setProperty("robolectric.applicationPackageName", packageName);
                        System.setProperty("robolectric.applicationProcessName", ProfileValues.text(effective.required("profile"), "processName", packageName));
                        var sourceApk = effective.required("profile").required("package").get("apk");
                        if (sourceApk != null) {
                            System.setProperty("robolectric.applicationSourceDir", ProfileValues.text(sourceApk, "package.apk"));
                        }
                        log.info("event=application_metadata_resolved package={} version={} effectiveRequest={}",
                                effective.path("profile").path("package").path("name").asText(),
                                effective.path("profile").path("package").path("versionName").asText(), effectivePath);
                        var bootstrap = JUnitCore.runClasses(AndroidBootstrap.class);
                        if (!bootstrap.wasSuccessful()) {
                            Exception failure = new Exception("Android lifecycle failed");
                            bootstrap.getFailures().forEach(item -> failure.addSuppressed(item.getException()));
                            throw failure;
                        }
                        if (bootstrap.getRunCount() != 1) throw new IllegalStateException("Android lifecycle did not execute exactly once");
                    }
                    default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
                }
                result.put("status", "COMPLETED");
                result.set("payload", RequestData.JSON.readTree(output.resolve("payload.json").toFile()));
                log.info("event=worker_completed");
            } catch (Throwable failure) {
                boolean cancelled = Files.exists(output.resolve("cancel.request")) || failure instanceof InterruptedException;
                result.put("status", cancelled ? "CANCELLED" : "FAILED");
                var error = result.putObject("failure");
                error.put("type", failure.getClass().getName());
                error.put("message", failure.getMessage());
                StringWriter stack = new StringWriter();
                failure.printStackTrace(new PrintWriter(stack));
                error.put("stack", stack.toString());
                log.error("event=worker_failed", failure);
            } finally {
                EvidenceFiles.preserveApplication(output);
                Files.writeString(output.resolve("host-proc-self-maps.txt"), Files.readString(Path.of("/proc/self/maps")));
                log.info("event=worker_closed");
                MDC.clear();
            }
            Path pending = output.resolve("result.pending.json");
            Files.writeString(pending, RequestData.JSON.writeValueAsString(result));
            Files.move(pending, output.resolve("result.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return result.path("status").asText().equals("COMPLETED") ? 0 : result.path("status").asText().equals("CANCELLED") ? 130 : 1;
        } finally { System.setOut(previous); }
    }

    static void runSession(Application application) throws Exception {
        JsonNode request = RequestData.read();
        var previousContext = MDC.getCopyOfContextMap();
        MDC.put("trace_id", request.required("traceId").asText());
        MDC.put("session_id", request.required("sessionId").asText());
        Logger log = LoggerFactory.getLogger(WorkerExecution.class);
        try (SessionState session = new SessionState(request, application)) {
            session.checkCancelled();
            long startupMillis = (System.nanoTime() - Long.parseLong(System.getProperty("unidbg.startedNanos"))) / 1_000_000;
            log.info("event=session_started android={} applicationPackage={} processName={} uid={} startup_ms={} sessionLoader={}", session.androidEnabled(),
                    application == null ? null : application.getPackageName(), application == null ? null : Application.getProcessName(),
                    application == null ? null : android.os.Process.myUid(), startupMillis, session.getClass().getClassLoader());
            Class<?> type = Class.forName(request.required("entrypoint").asText(), true, session.getClass().getClassLoader());
            WorkerEntrypoint entry = type.asSubclass(WorkerEntrypoint.class).getConstructor().newInstance();
            JsonNode payload = entry.run(session, request.required("arguments"));
            session.checkCancelled();
            Files.writeString(session.outputDirectory().resolve("payload.json"), RequestData.JSON.writeValueAsString(payload));
        } finally {
            log.info("event=session_closed");
            if (previousContext == null) MDC.clear();
            else MDC.setContextMap(previousContext);
        }
    }

    private static void prepareAndroid(JsonNode request) throws Exception {
        if (ProfileValues.integer(request.required("profile"), "androidSdk", 30) != 30) throw new IllegalArgumentException("This distribution supports Android API 30");
        Path runtime = Path.of(request.required("runtimeDirectory").asText());
        JsonNode manifest = RequestData.JSON.readTree(runtime.resolve("runtime-manifest.json").toFile());
        Path sdk = runtime.resolve(manifest.required("sdk").required("file").asText());
        if (!Files.isRegularFile(sdk)) throw new IllegalArgumentException("Required offline SDK is missing: " + sdk);
        System.setProperty("robolectric.offline", "true");
        System.setProperty("robolectric.dependency.dir", sdk.getParent().toString());
    }
    private WorkerExecution() {}
}
