package org.example.unidbg;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import io.github.aireposave.unidbg.runtime.client.RunRequest;
import io.github.aireposave.unidbg.runtime.client.RunResult;
import io.github.aireposave.unidbg.runtime.client.RuntimeClient;
import io.github.aireposave.unidbg.runtime.spi.WorkerEntrypoint;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import io.github.aireposave.unidbg.runtime.spi.jni.JniConfiguration;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PackageProfileIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void packageManagerMatchesDeviceOracleThroughJni() throws Exception {
        Path runtime = Path.of(System.getProperty("integration.runtime")).toAbsolutePath();
        Path evidence = Path.of(System.getProperty("integration.evidenceDir")).toAbsolutePath();
        JsonNode profile = JSON.readTree(Path.of(System.getProperty("integration.packageProfile")).toFile());
        JsonNode oracle = JSON.readTree(Path.of(System.getProperty("integration.packageOracle")).toFile());
        var request = new RunRequest(runtime, Fixture.class.getName(),
                Arrays.stream(System.getProperty("integration.fixtureClasspath").split(File.pathSeparator)).map(Path::of).toList(),
                oracle, RunRequest.Mode.ANDROID, profile, Path.of(System.getProperty("user.dir")), evidence,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Map.of(), UUID.randomUUID().toString().replace("-", ""));
        try (var handle = new RuntimeClient().start(request)) {
            RunResult result = handle.completion().get(90, TimeUnit.SECONDS);
            assertEquals(result.failure().toString(), RunResult.Status.COMPLETED, result.status());
            assertEquals(oracle.required("packages"), result.payload());
        }
    }

    public static final class Fixture implements WorkerEntrypoint {
        @Override
        public JsonNode run(WorkerSession session, JsonNode arguments) throws Exception {
            var emulator = session.createEmulator(() -> AndroidEmulatorBuilder.for64Bit()
                    .addBackendFactory(new Unicorn2Factory(false)).build());
            var vm = emulator.createDalvikVM();
            session.configureVm(vm, new JniConfiguration());
            var observed = JSON.createObjectNode();
            try (var jni = new Arm64Jni(emulator, vm)) {
                int context = jni.wrap(session.application());
                int manager = jni.objectCall(context, "getPackageManager", "()Landroid/content/pm/PackageManager;");
                var entries = arguments.required("packages").fields();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    String name = entry.getKey();
                    boolean permissions = entry.getValue().has("requestedPermissions");
                    int flags = PackageManager.GET_SIGNATURES | (permissions ? PackageManager.GET_PERMISSIONS : 0);
                    int reference = jni.objectCall(manager, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;",
                            jni.wrap(name), flags);
                    PackageInfo info = (PackageInfo) jni.value(reference);
                    var snapshot = observed.putObject(name);
                    snapshot.put("name", (String) jni.objectField(reference, "packageName", "Ljava/lang/String;"));
                    snapshot.put("versionName", (String) jni.objectField(reference, "versionName", "Ljava/lang/String;"));
                    snapshot.put("versionCode", info.getLongVersionCode());
                    snapshot.put("uid", info.applicationInfo.uid);
                    snapshot.put("targetSdk", info.applicationInfo.targetSdkVersion);
                    var signatures = snapshot.putArray("signatures");
                    var signers = (DvmObject<?>[]) jni.objectField(reference, "signatures", "[Landroid/content/pm/Signature;");
                    for (var signer : signers) {
                        int der = jni.objectCall(jni.wrap(signer), "toByteArray", "()[B");
                        signatures.add(Base64.getEncoder().encodeToString((byte[]) jni.value(der)));
                    }
                    if (permissions) {
                        var names = (DvmObject<?>[]) jni.objectField(reference, "requestedPermissions", "[Ljava/lang/String;");
                        if (names == null) snapshot.putNull("requestedPermissions");
                        else {
                            var array = snapshot.putArray("requestedPermissions");
                            for (var permission : names) array.add((String) ProxyDvmObject.unpack(permission));
                        }
                        snapshot.set("requestedPermissionsFlags", JSON.valueToTree(
                                jni.objectField(reference, "requestedPermissionsFlags", "[I")));
                    }
                }
            }
            Files.writeString(session.outputDirectory().resolve("pm-snapshot.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(observed));
            return observed;
        }
    }
}
