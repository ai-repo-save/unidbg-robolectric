package io.github.aireposave.unidbg.runtime.worker;

import android.app.Application;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.dvm.VM;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import io.github.aireposave.unidbg.runtime.spi.jni.JniConfiguration;
import io.github.aireposave.unidbg.runtime.spi.jni.RuntimeClassFactory;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SessionState extends WorkerSession {
    private static final Logger LOG = LoggerFactory.getLogger(SessionState.class);
    private final JsonNode request;
    private final Application application;
    private final ArrayDeque<AutoCloseable> resources = new ArrayDeque<>();
    private final IdentityHashMap<AutoCloseable, Boolean> owned = new IdentityHashMap<>();
    private final IdentityHashMap<AndroidEmulator, Boolean> configured = new IdentityHashMap<>();
    private final Map<String, String> properties;
    private final InputStream input = new FileInputStream(FileDescriptor.in);
    private final OutputStream output;
    private boolean closed;

    SessionState(JsonNode request, Application application) throws IOException {
        this.request = request;
        this.application = application;
        properties = ProfileProjection.nativeProperties(profile(), application != null);
        if (application != null) ProfileProjection.apply(application, profile(), properties);
        bind();
        try {
            output = own(new ProtocolOutput(new FileOutputStream(FileDescriptor.out),
                    Files.newOutputStream(outputDirectory().resolve("protocol.jsonl"))));
        } catch (RuntimeException | Error | IOException failure) {
            unbind();
            throw failure;
        }
    }

    @Override public String id() { return request.required("sessionId").asText(); }
    @Override public JsonNode profile() { return request.required("profile"); }
    @Override public Path outputDirectory() { return RequestData.output(request); }
    @Override public boolean androidEnabled() { return application != null; }
    @Override public Application application() {
        if (application == null) throw new IllegalStateException("Android environment is unavailable in NATIVE_ONLY mode");
        return application;
    }
    @Override public InputStream input() { return input; }
    @Override public OutputStream output() { return output; }
    @Override public <T extends AutoCloseable> T own(T resource) {
        if (closed) throw new IllegalStateException("Session is closed");
        if (owned.putIfAbsent(resource, true) == null) resources.push(resource);
        return resource;
    }
    @Override public void checkCancelled() throws InterruptedException {
        if (Thread.interrupted() || Files.exists(outputDirectory().resolve("cancel.request"))) {
            throw new InterruptedException("Session cancelled: " + id());
        }
    }
    @Override public AndroidEmulator createEmulator(EmulatorFactory factory) throws Exception {
        AndroidEmulator emulator = own(factory.create());
        configureEmulator(emulator);
        return emulator;
    }
    @Override public VM createVm(AndroidEmulator emulator, File apk, JniConfiguration jni) throws IOException {
        VM vm = emulator.createDalvikVM(apk);
        configureVm(vm, jni);
        return vm;
    }
    @Override public void configureVm(VM vm, JniConfiguration jni) {
        RuntimeClassFactory factory = new RuntimeClassFactory(getClass().getClassLoader(), jni);
        vm.setDvmClassFactory(factory);
        vm.setJni(factory.jni());
    }

    @Override public void configureEmulator(AndroidEmulator emulator) throws IOException {
        if (configured.putIfAbsent(emulator, true) != null) return;
        NativeProperties.install(emulator, properties);
        if (application != null) GuestFiles.install(emulator, application, profile());
        LOG.info("event=emulator_configured backend={} classLoader={} nativeProperties={}",
                emulator.getBackend().getClass().getName(), emulator.getClass().getClassLoader(), properties);
    }

    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        while (!resources.isEmpty()) {
            try { resources.pop().close(); }
            catch (Throwable current) {
                if (failure == null) failure = current;
                else if (failure != current) failure.addSuppressed(current);
            }
        }
        unbind();
        if (application != null) {
            try { Files.writeString(outputDirectory().resolve("application-data.location"), application.getDataDir().toString()); }
            catch (Throwable current) { if (failure == null) failure = current; else if (failure != current) failure.addSuppressed(current); }
        }
        LOG.info("event=session_resources_closed success={}", failure == null);
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private static final class ProtocolOutput extends OutputStream {
        private final OutputStream protocol;
        private final OutputStream evidence;
        ProtocolOutput(OutputStream protocol, OutputStream evidence) { this.protocol = protocol; this.evidence = evidence; }
        @Override public void write(int value) throws IOException { protocol.write(value); evidence.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { protocol.write(value, offset, length); evidence.write(value, offset, length); }
        @Override public void flush() throws IOException { protocol.flush(); evidence.flush(); }
        @Override public void close() throws IOException { try { flush(); } finally { evidence.close(); } }
    }
}
