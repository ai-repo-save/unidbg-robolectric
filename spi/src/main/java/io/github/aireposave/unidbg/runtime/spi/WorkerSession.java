package io.github.aireposave.unidbg.runtime.spi;

import android.app.Application;
import android.content.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.dvm.VM;
import io.github.aireposave.unidbg.runtime.spi.jni.JniConfiguration;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

public abstract class WorkerSession implements AutoCloseable {
    private static final ThreadLocal<WorkerSession> CURRENT = new ThreadLocal<>();

    public static WorkerSession current() {
        WorkerSession session = CURRENT.get();
        if (session == null) throw new IllegalStateException("No worker session on this execution thread");
        return session;
    }

    protected final void bind() {
        if (CURRENT.get() != null) throw new IllegalStateException("A worker session is already active");
        CURRENT.set(this);
    }

    protected final void unbind() { CURRENT.remove(); }

    public abstract String id();
    public abstract JsonNode profile();
    public abstract Path outputDirectory();
    public abstract boolean androidEnabled();
    public abstract Application application();
    public final Context androidContext() { return application(); }
    public abstract InputStream input();
    public abstract OutputStream output();
    public abstract <T extends AutoCloseable> T own(T resource);
    public abstract void checkCancelled() throws InterruptedException;
    public abstract AndroidEmulator createEmulator(EmulatorFactory factory) throws Exception;
    public abstract void configureEmulator(AndroidEmulator emulator) throws IOException;
    public abstract VM createVm(AndroidEmulator emulator, File apk, JniConfiguration jni) throws IOException;
    public abstract void configureVm(VM vm, JniConfiguration jni);
    public abstract void close() throws Exception;

    @FunctionalInterface
    public interface EmulatorFactory {
        AndroidEmulator create() throws Exception;
    }
}
