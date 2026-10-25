package io.github.aireposave.unidbg.runtime.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aireposave.unidbg.runtime.client.RuntimeVersion;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "unidbg-worker", mixinStandardHelpOptions = true, version = RuntimeVersion.VERSION,
        description = "Execute one complete native or Android analysis session in this JVM.")
public final class WorkerMain implements Callable<Integer> {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Option(names = "--request", description = "Runtime request JSON file") Path request;
    @Option(names = "--verify-installation", description = "Verify all immutable installation files, without starting a session") Path verifyInstallation;
    @Option(names = "--mode", description = "NATIVE_ONLY or ANDROID") String mode;
    @Option(names = "--entrypoint", description = "WorkerEntrypoint implementation FQCN") String entrypoint;
    @Option(names = "--arguments", description = "Entrypoint JSON arguments file") Path arguments;
    @Option(names = "--profile", description = "Environment profile JSON file") Path profile;
    @Option(names = "--output-dir", description = "New session output directory") Path outputDirectory;
    @Option(names = "--task-classpath", description = "Task jars/directories, separated by the host path separator") String classpath = "";
    @Option(names = "--runtime-dir", description = "Installed runtime (defaults to this distribution)") Path runtimeDirectory;
    @Option(names = "--trace-id", description = "Parent trace correlation identifier") String traceId;

    public static void main(String[] arguments) {
        int code = new CommandLine(new WorkerMain()).execute(arguments);
        System.exit(code);
    }

    @Override
    public Integer call() throws Exception {
        System.setProperty("unidbg.startedNanos", Long.toString(System.nanoTime()));
        if (verifyInstallation != null) {
            System.out.println(JSON.writeValueAsString(InstallationVerifier.verify(verifyInstallation)));
            return 0;
        }
        if (request == null) request = createRequest();
        ObjectNode envelope = (ObjectNode) JSON.readTree(request.toFile());
        if (envelope.required("protocol").intValue() != RuntimeVersion.PROTOCOL
                || !envelope.required("version").asText().equals(RuntimeVersion.VERSION)) {
            throw new IllegalArgumentException("Unsupported runtime protocol/version");
        }
        var urls = new ArrayList<URL>();
        for (String item : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(Path.of(item).toUri().toURL());
        for (var item : envelope.required("classpath")) urls.add(Path.of(item.asText()).toUri().toURL());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        // Robolectric shutdown hooks load additional classes during JVM shutdown.
        URLClassLoader execution = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        try {
            Thread.currentThread().setContextClassLoader(execution);
            var run = execution.loadClass(WorkerExecution.class.getName()).getMethod("execute", String.class);
            try { return (Integer) run.invoke(null, request.toAbsolutePath().toString()); }
            catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            }
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    private Path createRequest() throws Exception {
        if (mode == null || entrypoint == null || outputDirectory == null) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--mode, --entrypoint and --output-dir are required without --request");
        }
        if (!mode.equals("ANDROID") && !mode.equals("NATIVE_ONLY")) throw new IllegalArgumentException("Unsupported mode: " + mode);
        if (runtimeDirectory == null) {
            Path jar = Path.of(WorkerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("--runtime-dir required outside an installed distribution");
            runtimeDirectory = jar.getParent().getParent();
        }
        Path output = outputDirectory.toAbsolutePath();
        Files.createDirectories(output);
        var value = JSON.createObjectNode();
        value.put("protocol", RuntimeVersion.PROTOCOL);
        value.put("version", RuntimeVersion.VERSION);
        value.put("sessionId", UUID.randomUUID().toString());
        value.put("traceId", traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId);
        value.put("mode", mode);
        value.put("entrypoint", entrypoint);
        value.set("arguments", arguments == null ? JSON.createObjectNode() : JSON.readTree(arguments.toFile()));
        value.set("profile", profile == null ? JSON.createObjectNode() : JSON.readTree(profile.toFile()));
        value.put("outputDirectory", output.toString());
        value.put("runtimeDirectory", runtimeDirectory.toAbsolutePath().toString());
        var paths = value.putArray("classpath");
        if (!classpath.isEmpty()) for (String item : classpath.split(File.pathSeparator)) paths.add(Path.of(item).toAbsolutePath().toString());
        Path file = output.resolve("request.json");
        Files.writeString(file, JSON.writeValueAsString(value), StandardOpenOption.CREATE_NEW);
        return file;
    }
}
