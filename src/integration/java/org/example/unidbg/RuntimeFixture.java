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
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.VarArg;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import io.github.aireposave.unidbg.runtime.spi.WorkerEntrypoint;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import io.github.aireposave.unidbg.runtime.spi.jni.JniConfiguration;
import java.io.BufferedReader;
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
            case "cooperative" -> {
                BlockingChild.signalReady(session.outputDirectory().resolve("ready"));
                while (true) { session.checkCancelled(); Thread.sleep(10); }
            }
            case "blocked" -> {
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp", arguments.required("childClasspath").asText(), BlockingChild.class.getName(),
                        session.outputDirectory().resolve("ready").toString()).start();
                Files.writeString(session.outputDirectory().resolve("child.pid"), Long.toString(child.pid()));
                Thread.sleep(Long.MAX_VALUE);
                throw new AssertionError("Blocked fixture unexpectedly returned");
            }
            default -> throw new IllegalArgumentException(operation);
        };
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

    public static final class JavaCallback {
        public static String value() { return "java-owner"; }
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
        AndroidEmulator emulator = session.createEmulator(() -> AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false)).build());
        AndroidResolver resolver = new AndroidResolver(23);
        emulator.getMemory().setLibraryResolver(resolver);
        var vm = emulator.createDalvikVM();
        JniConfiguration configuration = new JniConfiguration()
                .handle("java/lang/System->currentTimeMillis()J", call -> 1234567890123L)
                .proxyOwner(JavaCallback.class.getName().replace('.', '/'))
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
            assertEquals("java-owner", jni.value(jni.staticObjectCall(JavaCallback.class.getName().replace('.', '/'), "value", "()Ljava/lang/String;")));
            var generator = java.security.KeyPairGenerator.getInstance("EC", "Conscrypt");
            generator.initialize(256);
            var publicKey = generator.generateKeyPair().getPublic();
            assertArrayEquals(publicKey.getEncoded(), (byte[]) jni.value(jni.objectCall(jni.wrap(publicKey), "getEncoded", "()[B")));
            assertEquals("owner", jni.value(jni.staticObjectCall("example/Callback", "owner", "()Ljava/lang/String;")));
            assertEquals("exact", jni.value(jni.staticObjectCall("example/Callback", "exact", "()Ljava/lang/String;")));
            NativeFilesProbe.verify(emulator, resolver, application, session, model);
        }
        return JSON.createObjectNode().put("package", application.getPackageName()).put("model", session.profile().path("build").path("MODEL").asText())
                .put("pid", ProcessHandle.current().pid());
    }
}
