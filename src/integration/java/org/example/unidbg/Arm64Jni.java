package org.example.unidbg;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JNI table indices follow the JNI specification, using the ARM64 jvalue layout. */
final class Arm64Jni implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(Arm64Jni.class);
    private final AndroidEmulator emulator;
    private final VM vm;
    private final Pointer env;
    private final Pointer table;
    private final MemoryBlock scratch;

    Arm64Jni(ClassLoader androidLoader) throws IOException {
        emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false)).build();
        try {
            vm = emulator.createDalvikVM();
            vm.setDvmClassFactory(new ProxyClassFactory(androidLoader));
            env = vm.getJNIEnv();
            table = env.getPointer(0);
            scratch = emulator.getMemory().malloc(4096, true);
            LOG.info("event=emulator_started backend={} backendLoader={} androidLoader={} vmLoader={} "
                            + "unicornLoader={} androidAndVmLoaderSame={}",
                    emulator.getBackend().getClass().getName(), emulator.getBackend().getClass().getClassLoader(),
                    androidLoader, vm.getClass().getClassLoader(),
                    com.github.unidbg.arm.backend.unicorn.Unicorn.class.getClassLoader(),
                    androidLoader == vm.getClass().getClassLoader());
        } catch (RuntimeException | Error failure) {
            try { emulator.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    int wrap(Object value) {
        return vm.addLocalObject(ProxyDvmObject.createObject(vm, value));
    }

    private int type(String name) {
        scratch.getPointer().setString(0, name);
        return invoke(6, env, scratch.getPointer());
    }

    private int member(int type, int index, String name, String descriptor) {
        Pointer data = scratch.getPointer();
        data.setString(0, name);
        data.setString(1024, descriptor);
        return invoke(index, env, type, data, data.share(1024));
    }

    int objectCall(int receiver, String name, String descriptor, int... args) {
        int type = invoke(31, env, receiver);
        return invoke(36, env, receiver, member(type, 33, name, descriptor), arguments(args));
    }

    boolean booleanCall(int receiver, String name, String descriptor, int... args) {
        int type = invoke(31, env, receiver);
        return invoke(39, env, receiver, member(type, 33, name, descriptor), arguments(args)) == VM.JNI_TRUE;
    }

    int staticObjectCall(String typeName, String name, String descriptor, int... args) {
        int type = type(typeName);
        return invoke(116, env, type, member(type, 113, name, descriptor), arguments(args));
    }

    boolean staticBooleanCall(String typeName, String name, String descriptor, int... args) {
        int type = type(typeName);
        return invoke(119, env, type, member(type, 113, name, descriptor), arguments(args)) == VM.JNI_TRUE;
    }

    Object staticObjectField(String typeName, String name, String descriptor) {
        int type = type(typeName);
        return value(invoke(145, env, type, member(type, 144, name, descriptor)));
    }

    Object objectField(int receiver, String name, String descriptor) {
        int type = invoke(31, env, receiver);
        return value(invoke(95, env, receiver, member(type, 94, name, descriptor)));
    }

    Object value(int reference) {
        return reference == 0 ? null : vm.getObject(reference).getValue();
    }

    private Pointer arguments(int[] args) {
        Pointer data = scratch.getPointer().share(2048);
        for (int i = 0; i < args.length; i++) {
            data.setLong(i * 8L, Integer.toUnsignedLong(args[i]));
        }
        return data;
    }

    private int invoke(int index, Object... args) {
        int result = Module.emulateFunction(emulator,
                UnidbgPointer.nativeValue(table.getPointer(index * 8L)), args).intValue();
        int exception = Module.emulateFunction(emulator,
                UnidbgPointer.nativeValue(table.getPointer(15 * 8L)), env).intValue();
        if (exception != 0) {
            throw new AssertionError("JNI index " + index + " raised an exception", (Throwable) value(exception));
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        try { scratch.free(); } finally { emulator.close(); }
        LOG.info("event=emulator_closed");
    }
}
