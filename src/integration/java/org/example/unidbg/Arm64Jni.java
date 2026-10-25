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
    private static final int JNI_FIND_CLASS = 6;
    private static final int JNI_EXCEPTION_OCCURRED = 15;
    private static final int JNI_GET_OBJECT_CLASS = 31;
    private static final int JNI_GET_METHOD_ID = 33;
    private static final int JNI_CALL_OBJECT_METHOD_A = 36;
    private static final int JNI_CALL_BOOLEAN_METHOD_A = 39;
    private static final int JNI_GET_FIELD_ID = 94;
    private static final int JNI_GET_OBJECT_FIELD = 95;
    private static final int JNI_GET_STATIC_METHOD_ID = 113;
    private static final int JNI_CALL_STATIC_OBJECT_METHOD_A = 116;
    private static final int JNI_CALL_STATIC_BOOLEAN_METHOD_A = 119;
    private static final int JNI_GET_STATIC_FIELD_ID = 144;
    private static final int JNI_GET_STATIC_OBJECT_FIELD = 145;
    private static final int POINTER_BYTES = Long.BYTES;
    private static final int JVALUE_BYTES = Long.BYTES;
    private static final int SCRATCH_SIZE = 4096;
    private static final int NAME_OFFSET = 0;
    private static final int DESCRIPTOR_OFFSET = 1024;
    private static final int ARGUMENTS_OFFSET = 2048;
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
            scratch = emulator.getMemory().malloc(SCRATCH_SIZE, true);
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
        Pointer namePointer = scratch.getPointer().share(NAME_OFFSET);
        namePointer.setString(0, name);
        return invoke(JNI_FIND_CLASS, env, namePointer);
    }

    private int member(int type, int index, String name, String descriptor) {
        Pointer data = scratch.getPointer();
        Pointer namePointer = data.share(NAME_OFFSET);
        Pointer descriptorPointer = data.share(DESCRIPTOR_OFFSET);
        namePointer.setString(0, name);
        descriptorPointer.setString(0, descriptor);
        return invoke(index, env, type, namePointer, descriptorPointer);
    }

    int objectCall(int receiver, String name, String descriptor, int... args) {
        int type = invoke(JNI_GET_OBJECT_CLASS, env, receiver);
        return invoke(JNI_CALL_OBJECT_METHOD_A, env, receiver,
                member(type, JNI_GET_METHOD_ID, name, descriptor), arguments(args));
    }

    boolean booleanCall(int receiver, String name, String descriptor, int... args) {
        int type = invoke(JNI_GET_OBJECT_CLASS, env, receiver);
        return invoke(JNI_CALL_BOOLEAN_METHOD_A, env, receiver,
                member(type, JNI_GET_METHOD_ID, name, descriptor), arguments(args)) == VM.JNI_TRUE;
    }

    int staticObjectCall(String typeName, String name, String descriptor, int... args) {
        int type = type(typeName);
        return invoke(JNI_CALL_STATIC_OBJECT_METHOD_A, env, type,
                member(type, JNI_GET_STATIC_METHOD_ID, name, descriptor), arguments(args));
    }

    boolean staticBooleanCall(String typeName, String name, String descriptor, int... args) {
        int type = type(typeName);
        return invoke(JNI_CALL_STATIC_BOOLEAN_METHOD_A, env, type,
                member(type, JNI_GET_STATIC_METHOD_ID, name, descriptor), arguments(args)) == VM.JNI_TRUE;
    }

    Object staticObjectField(String typeName, String name, String descriptor) {
        int type = type(typeName);
        return value(invoke(JNI_GET_STATIC_OBJECT_FIELD, env, type,
                member(type, JNI_GET_STATIC_FIELD_ID, name, descriptor)));
    }

    Object objectField(int receiver, String name, String descriptor) {
        int type = invoke(JNI_GET_OBJECT_CLASS, env, receiver);
        return value(invoke(JNI_GET_OBJECT_FIELD, env, receiver,
                member(type, JNI_GET_FIELD_ID, name, descriptor)));
    }

    Object value(int reference) {
        return reference == 0 ? null : vm.getObject(reference).getValue();
    }

    private Pointer arguments(int[] args) {
        Pointer data = scratch.getPointer().share(ARGUMENTS_OFFSET);
        for (int i = 0; i < args.length; i++) {
            data.setLong((long) i * JVALUE_BYTES, Integer.toUnsignedLong(args[i]));
        }
        return data;
    }

    private int invoke(int index, Object... args) {
        int result = Module.emulateFunction(emulator,
                UnidbgPointer.nativeValue(table.getPointer((long) index * POINTER_BYTES)), args).intValue();
        int exception = Module.emulateFunction(emulator,
                UnidbgPointer.nativeValue(table.getPointer((long) JNI_EXCEPTION_OCCURRED * POINTER_BYTES)), env).intValue();
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
