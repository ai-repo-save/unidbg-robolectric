package org.example.unidbg;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.AndroidSyscallHandler;
import com.github.unidbg.linux.struct.Stat64;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.IO;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.Jni;
import com.github.unidbg.linux.android.dvm.HostJni;
import com.github.unidbg.linux.android.dvm.VarArg;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyJni;
import io.github.aireposave.unidbg.runtime.spi.WorkerEntrypoint;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import io.github.aireposave.unidbg.runtime.spi.jni.JniConfiguration;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import static org.junit.Assert.*;

public final class RuntimeFixture implements WorkerEntrypoint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "unidbg_probe";
    private static final String GET = "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;";
    private static final String PUT = "(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z";

    @Override
    public JsonNode run(WorkerSession session, JsonNode arguments) throws Exception {
        assertSame(session, WorkerSession.current());
        session.own((AutoCloseable) () -> Files.writeString(session.outputDirectory().resolve("resource-closed"), "closed"));
        String operation = arguments.required("operation").asText();
        return switch (operation) {
            case "android" -> android(session, arguments.required("label").asText());
            case "native" -> JSON.createObjectNode().put("android", session.androidEnabled()).put("pid", ProcessHandle.current().pid());
            case "fail" -> throw new java.io.IOException("Integration fixture failure");
            case "service" -> service(session);
            case "stdin" -> stdin(session);
            case "cooperative", "cooperative-child" -> {
                if (operation.equals("cooperative-child")) startChild(session, arguments);
                else BlockingChild.signalReady(session.outputDirectory().resolve("ready"));
                while (true) { session.checkCancelled(); Thread.sleep(10); }
            }
            case "blocked" -> {
                startChild(session, arguments);
                Thread.sleep(Long.MAX_VALUE);
                throw new AssertionError("Blocked fixture unexpectedly returned");
            }
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private static void startChild(WorkerSession session, JsonNode arguments) throws Exception {
        Path childReady = session.outputDirectory().resolve("child-ready");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", arguments.required("childClasspath").asText(), BlockingChild.class.getName(), childReady.toString()).start();
        Files.writeString(session.outputDirectory().resolve("child.pid"), Long.toString(child.pid()));
        while (!Files.isRegularFile(childReady)) {
            if (!child.isAlive()) throw new java.io.IOException("Child exited before signalling readiness: " + child.exitValue());
            session.checkCancelled();
            Thread.sleep(10);
        }
        BlockingChild.signalReady(session.outputDirectory().resolve("ready"));
    }

    private static JsonNode service(WorkerSession session) throws Exception {
        var input = new BufferedReader(new InputStreamReader(session.input(), StandardCharsets.UTF_8));
        var output = new OutputStreamWriter(session.output(), StandardCharsets.UTF_8);
        output.write(JSON.writeValueAsString(JSON.createObjectNode().put("ready", session.id())) + "\n");
        output.flush();
        int count = 0;
        String line;
        while ((line = input.readLine()) != null) {
            JsonNode message = JSON.readTree(line);
            if (message.path("close").asBoolean()) break;
            output.write(JSON.writeValueAsString(JSON.createObjectNode().put("session", session.id()).put("count", ++count)
                    .set("value", message)) + "\n");
            output.flush();
        }
        return JSON.createObjectNode().put("count", count);
    }

    private static JsonNode stdin(WorkerSession session) throws Exception {
        InputStream original = System.in;
        var observed = new ByteArrayOutputStream();
        try {
            for (boolean existing : new boolean[] {false, true}) {
                AndroidEmulator emulator = existing
                        ? session.own(AndroidEmulatorBuilder.for64Bit().addBackendFactory(new Unicorn2Factory(true)).build())
                        : session.createEmulator(() -> AndroidEmulatorBuilder.for64Bit().addBackendFactory(new Unicorn2Factory(true)).build());
                if (existing) {
                    emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
                    session.configureEmulator(emulator);
                } else {
                    emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
                }
                System.setIn(new ByteArrayInputStream(new byte[] {99}));
                AndroidSyscallHandler handler = (AndroidSyscallHandler) emulator.getSyscallHandler();
                var code = emulator.getMemory().mmap(4096, 7);
                var memory = emulator.getMemory().malloc(4096, true);
                try {
                    var buffer = memory.getPointer();
                    var expected = Files.readAttributes(Path.of("/proc/self/fd/" + IO.FD_STDIN), "unix:*");
                    var facts = handler.queryMetadataAt(emulator, IO.FD_STDIN, "", 0x1000).value;
                    assertEquals(((Number) expected.get("mode")).intValue(), facts.mode.getAsInt());
                    assertEquals(010000, facts.mode.getAsInt() & 0170000);
                    assertEquals(((Number) expected.get("ino")).longValue(), facts.ino.getAsLong());
                    assertEquals(0, stdinCall(emulator, code, 80, IO.FD_STDIN, buffer.peer));
                    Stat64 stat = new Stat64(buffer);
                    stat.unpack();
                    assertEquals(((Number) expected.get("mode")).intValue(), stat.st_mode);
                    assertEquals(((Number) expected.get("ino")).longValue(), stat.st_ino);
                    assertEquals(((Number) expected.get("size")).longValue(), stat.st_size);
                    assertEquals(((Number) expected.get("dev")).longValue(), stat.st_dev);
                    assertEquals(((Number) expected.get("rdev")).longValue(), stat.st_rdev);
                    assertEquals(((Number) expected.get("uid")).intValue(), stat.st_uid);
                    assertEquals(((Number) expected.get("gid")).intValue(), stat.st_gid);
                    assertEquals(((Number) expected.get("nlink")).intValue(), stat.st_nlink);
                    for (String path : new String[] {"relative/child", "null/cache"}) {
                        buffer.setString(0, path);
                        assertEquals(-20, stdinCall(emulator, code, 48, IO.FD_STDIN, buffer.peer, 0));
                    }
                    assertEquals(1, stdinCall(emulator, code, 63, IO.FD_STDIN, buffer.peer, 1));
                    observed.write(buffer.getByte(0));
                    assertEquals(99, System.in.read());
                    assertEquals(0, stdinCall(emulator, code, 57, IO.FD_STDIN));
                } finally {
                    memory.free();
                    emulator.getMemory().munmap(code.peer, 4096);
                }
            }
            observed.write(session.input().read());
            Files.write(session.outputDirectory().resolve("stdin-content.bin"), observed.toByteArray());
            return JSON.createObjectNode().put("pid", ProcessHandle.current().pid());
        } finally {
            System.setIn(original);
        }
    }

    private static int stdinCall(AndroidEmulator emulator, UnidbgPointer code, int number, Number... arguments) {
        long offset = number * 16L;
        code.setInt(offset, 0xd2800008 | (number << 5));
        code.setInt(offset + 4, 0xd4000001);
        code.setInt(offset + 8, 0xd65f03c0);
        return emulator.eFunc(code.peer + offset, arguments).intValue();
    }

    public static final class JavaCallback {
        static final java.io.IOException FAILURE = new java.io.IOException("Target failure");
        public static String value() { return "java-owner"; }
        public static long targetFailure() throws java.io.IOException { throw FAILURE; }
    }

    public static final class HostCallback {
        public static String value() { return "host-owner"; }
    }

    private static final class HostOwnerFactory extends ProxyClassFactory {
        HostOwnerFactory(ClassLoader loader) { super(loader); }
        HostJni provider() { return new ProxyJni(classLoader, visitor, null); }
    }

    public static final class CapturedHost {
        private final String model;
        private final Path directory;
        private final ClassLoader loader;
        CapturedHost(WorkerSession session) {
            model = session.profile().required("build").required("MODEL").asText();
            directory = session.outputDirectory();
            loader = getClass().getClassLoader();
        }
        public String getModel() throws java.io.IOException {
            assertThrows(IllegalStateException.class, WorkerSession::current);
            Files.writeString(directory.resolve("host-captured-model"), model);
            return model;
        }
        public ClassLoader getClassLoader() {
            assertThrows(IllegalStateException.class, WorkerSession::current);
            return loader;
        }
        public java.io.File getCacheDir() {
            assertThrows(IllegalStateException.class, WorkerSession::current);
            return directory.resolve("host-cache").toFile();
        }
    }

    private static JsonNode android(WorkerSession session, String label) throws Exception {
        Application application = session.application();
        assertEquals(session.profile().required("package").required("name").asText(), application.getPackageName());
        assertSame(application, application.getApplicationContext());
        assertEquals(session.profile().required("processName").textValue(), Application.getProcessName());
        assertEquals(session.profile().required("package").required("uid").intValue(), android.os.Process.myUid());
        assertEquals(android.os.Process.myUid(), application.getApplicationInfo().uid);
        assertEquals(android.os.Process.myUid(), application.getPackageManager().getApplicationInfo(application.getPackageName(), 0).uid);
        assertEquals(application.getPackageName(), application.getPackageManager().getPackageInfo(application.getPackageName(), 0).packageName);
        assertEquals("org.example.installer", application.getPackageManager().getInstallerPackageName(application.getPackageName()));
        assertEquals(null, application.getPackageManager().getInstallerPackageName("org.example.sideloaded"));
        AndroidEmulator emulator = session.createEmulator(() -> AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false)).build());
        AndroidResolver resolver = new AndroidResolver(23);
        emulator.getMemory().setLibraryResolver(resolver);
        var vm = emulator.createDalvikVM();
        Thread backend = Thread.currentThread();
        String javaOwner = JavaCallback.class.getName().replace('.', '/');
        String hostOwner = HostCallback.class.getName().replace('.', '/');
        CapturedHost captured = new CapturedHost(session);
        Jni modeled = new AbstractJni() {
            @Override public long callStaticLongMethod(BaseVM target, DvmClass type, String signature, VarArg args) { return 17L; }
            @Override public long callStaticLongMethodV(BaseVM target, DvmClass type, String signature, VaList args) { return 23L; }
        };
        JniConfiguration configuration = new JniConfiguration()
                .handle("java/lang/System->currentTimeMillis()J", call -> 1234567890123L)
                .businessOwner(hostOwner, new HostOwnerFactory(RuntimeFixture.class.getClassLoader()).provider())
                .businessOwner("example/Overload", modeled)
                .handle("example/Overload->value()J", call -> {
                    assertSame(Jni.class, call.method().getDeclaringClass());
                    boolean variadic = call.arguments()[3] instanceof VaList;
                    assertEquals(variadic ? "callStaticLongMethodV" : "callStaticLongMethod", call.method().getName());
                    assertArrayEquals(new Class<?>[] {BaseVM.class, DvmClass.class, com.github.unidbg.linux.android.dvm.DvmMethod.class,
                            variadic ? VaList.class : VarArg.class}, call.method().getParameterTypes());
                    return call.invoke(modeled);
                })
                .businessOwner("example/Callback", new AbstractJni() {
                    @Override public DvmObject<?> callStaticObjectMethod(BaseVM target, DvmClass type, String signature, VarArg args) {
                        if (signature.equals("example/Callback->owner()Ljava/lang/String;")) return ProxyDvmObject.createObject(target, "owner");
                        throw new UnsupportedOperationException(signature);
                    }
                    @Override public DvmObject<?> callStaticObjectMethodV(BaseVM target, DvmClass type, String signature, VaList args) {
                        return callStaticObjectMethod(target, type, signature, args);
                    }
                })
                .handle("example/Callback->exact()Ljava/lang/String;", call -> ProxyDvmObject.createObject(vm, "exact"))
                .observe(event -> {
                    assertSame(backend, Thread.currentThread());
                    assertSame(session, WorkerSession.current());
                    if (event.phase() == JniConfiguration.Phase.AFTER && event.call().method().getName().equals("getStaticObjectField")
                            && event.call().signature().equals("android/os/Build->MODEL:Ljava/lang/String;")) {
                        Files.writeString(session.outputDirectory().resolve("observed-model"), ((DvmObject<?>) event.result()).getValue().toString());
                    }
                });
        session.configureVm(vm, configuration);
        try (Arm64Jni jni = new Arm64Jni(emulator, vm)) {
            int context = jni.wrap(application);
            assertEquals(application.getPackageName(), jni.value(jni.objectCall(context, "getPackageName", "()Ljava/lang/String;")));
            int contentResolver = jni.objectCall(context, "getContentResolver", "()Landroid/content/ContentResolver;");
            int key = jni.wrap(KEY);
            assertEquals("seed-" + label, jni.value(jni.staticObjectCall("android/provider/Settings$Global", "getString", GET, contentResolver, jni.wrap("library_seed"))));
            int seeded = jni.objectCall(context, "getSharedPreferences", "(Ljava/lang/String;I)Landroid/content/SharedPreferences;", jni.wrap("seeded"), 0);
            assertEquals("seeded-" + label, jni.value(jni.objectCall(seeded, "getString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", jni.wrap("initial"), jni.wrap("missing"))));
            assertEquals(null, jni.value(jni.staticObjectCall("android/provider/Settings$Global", "getString", GET, contentResolver, key)));
            assertEquals(null, jni.value(jni.staticObjectCall("android/provider/Settings$Secure", "getString", GET, contentResolver, key)));
            int preferences = jni.objectCall(context, "getSharedPreferences", "(Ljava/lang/String;I)Landroid/content/SharedPreferences;", jni.wrap("jni-probe"), 0);
            assertEquals("unset", jni.value(jni.objectCall(preferences, "getString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", key, jni.wrap("unset"))));
            String global = "global-" + label;
            String secure = "secure-" + label;
            assertEquals(true, jni.staticBooleanCall("android/provider/Settings$Global", "putString", PUT, contentResolver, key, jni.wrap(global)));
            assertEquals(true, jni.staticBooleanCall("android/provider/Settings$Secure", "putString", PUT, contentResolver, key, jni.wrap(secure)));
            assertEquals(global, jni.value(jni.staticObjectCall("android/provider/Settings$Global", "getString", GET, contentResolver, key)));
            assertEquals(secure, jni.value(jni.staticObjectCall("android/provider/Settings$Secure", "getString", GET, contentResolver, key)));
            assertEquals(global, Settings.Global.getString(application.getContentResolver(), KEY));
            assertEquals(secure, Settings.Secure.getString(application.getContentResolver(), KEY));
            String model = session.profile().required("build").required("MODEL").asText();
            assertEquals(model, jni.staticObjectField("android/os/Build", "MODEL", "Ljava/lang/String;"));
            assertEquals("ZP11.260821.010", jni.staticObjectField("android/os/Build", "ID", "Ljava/lang/String;"));
            assertEquals("ONEPLUS A6010_41_210928", jni.staticObjectField("android/os/Build", "DISPLAY", "Ljava/lang/String;"));
            assertEquals("release-keys", jni.staticObjectField("android/os/Build", "TAGS", "Ljava/lang/String;"));
            assertEquals("user", jni.staticObjectField("android/os/Build", "TYPE", "Ljava/lang/String;"));
            assertEquals("google/tokay_beta/tokay:CANARY/ZP11.260821.010/16290768:user/release-keys",
                    jni.staticObjectField("android/os/Build", "FINGERPRINT", "Ljava/lang/String;"));
            assertEquals("CANARY", jni.staticObjectField("android/os/Build$VERSION", "RELEASE", "Ljava/lang/String;"));
            assertEquals("16290768", jni.staticObjectField("android/os/Build$VERSION", "INCREMENTAL", "Ljava/lang/String;"));
            assertEquals("2026-09-05", jni.staticObjectField("android/os/Build$VERSION", "SECURITY_PATCH", "Ljava/lang/String;"));
            assertEquals(30, android.os.Build.VERSION.SDK_INT);
            var properties = session.profile().required("nativeProperties").fields();
            while (properties.hasNext()) {
                var property = properties.next();
                assertEquals(property.getValue().textValue(), jni.value(jni.staticObjectCall("android/os/SystemProperties", "get",
                        "(Ljava/lang/String;)Ljava/lang/String;", jni.wrap(property.getKey()))));
            }
            assertEquals("30", jni.value(jni.staticObjectCall("android/os/SystemProperties", "get",
                    "(Ljava/lang/String;)Ljava/lang/String;", jni.wrap("ro.build.version.sdk"))));
            PackageInfo installed = new PackageInfo();
            installed.packageName = "org.example.installed";
            installed.versionName = "release-" + label;
            ShadowPackageManager packages = Shadow.extract(application.getPackageManager());
            packages.installPackage(installed);
            int manager = jni.objectCall(context, "getPackageManager", "()Landroid/content/pm/PackageManager;");
            int info = jni.objectCall(manager, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;", jni.wrap(installed.packageName), 0);
            assertEquals(installed.packageName, jni.objectField(info, "packageName", "Ljava/lang/String;"));
            assertEquals(installed.versionName, jni.objectField(info, "versionName", "Ljava/lang/String;"));
            int editor = jni.objectCall(preferences, "edit", "()Landroid/content/SharedPreferences$Editor;");
            int updated = jni.objectCall(editor, "putString", "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;", key, jni.wrap("preference-" + label));
            assertEquals(true, jni.booleanCall(updated, "commit", "()Z"));
            Context independent = application.createPackageContext(application.getPackageName(), 0);
            assertEquals(Map.of(KEY, "preference-" + label), independent.getSharedPreferences("jni-probe", 0).getAll());
            assertEquals(1234567890123L, jni.staticLongCall("java/lang/System", "currentTimeMillis", "()J"));
            assertEquals("java-owner", jni.value(jni.staticObjectCall(javaOwner, "value", "()Ljava/lang/String;")));
            assertEquals("host-owner", jni.value(jni.staticObjectCall(hostOwner, "value", "()Ljava/lang/String;")));
            assertEquals(17L, jni.staticLongCallOrdinary("example/Overload", "value", "()J"));
            assertEquals(23L, jni.staticLongCall("example/Overload", "value", "()J"));
            int hostObject = jni.wrap(captured);
            String capturedModel = session.profile().required("build").required("MODEL").asText();
            assertEquals(capturedModel, jni.value(jni.objectCall(hostObject, "getModel", "()Ljava/lang/String;")));
            assertEquals(capturedModel, Files.readString(session.outputDirectory().resolve("host-captured-model")));
            assertSame(RuntimeFixture.class.getClassLoader(), jni.value(jni.objectCall(hostObject, "getClassLoader", "()Ljava/lang/ClassLoader;")));
            int cache = jni.objectCall(hostObject, "getCacheDir", "()Ljava/io/File;");
            assertEquals(session.outputDirectory().resolve("host-cache").toString(), jni.value(jni.objectCall(cache, "getPath", "()Ljava/lang/String;")));
            AssertionError target = assertThrows(AssertionError.class, () -> jni.staticLongCall(javaOwner, "targetFailure", "()J"));
            assertSame(JavaCallback.FAILURE, target.getCause());
            com.github.unidbg.Module.emulateFunction(emulator, com.github.unidbg.pointer.UnidbgPointer.nativeValue(
                    vm.getJNIEnv().getPointer(0).getPointer(17L * Long.BYTES)), vm.getJNIEnv());
            var generator = java.security.KeyPairGenerator.getInstance("EC", "Conscrypt");
            generator.initialize(256);
            var publicKey = generator.generateKeyPair().getPublic();
            assertArrayEquals(publicKey.getEncoded(), (byte[]) jni.value(jni.objectCall(jni.wrap(publicKey), "getEncoded", "()[B")));
            assertEquals("owner", jni.value(jni.staticObjectCall("example/Callback", "owner", "()Ljava/lang/String;")));
            assertEquals("exact", jni.value(jni.staticObjectCall("example/Callback", "exact", "()Ljava/lang/String;")));
            NativeFilesProbe.verify(emulator, resolver, application, session, model, jni);
        }
        return JSON.createObjectNode().put("package", application.getPackageName()).put("model", session.profile().path("build").path("MODEL").asText())
                .put("pid", ProcessHandle.current().pid());
    }
}
