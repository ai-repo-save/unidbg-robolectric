package org.example.unidbg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import io.github.aireposave.unidbg.runtime.spi.jni.JniConfiguration;
import io.github.aireposave.unidbg.runtime.client.RunRequest;
import io.github.aireposave.unidbg.runtime.client.RunResult;
import io.github.aireposave.unidbg.runtime.client.RuntimeClient;
import io.github.aireposave.unidbg.runtime.spi.WorkerEntrypoint;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import net.dongliu.apk.parser.ApkFile;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class ApkStartupIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test public void startsWorkerAndPersistsWitness() throws Exception {
        Path runtime = Path.of(System.getProperty("integration.runtime")).toAbsolutePath();
        Path evidence = Path.of(System.getProperty("integration.evidenceDir")).toAbsolutePath();
        Path apk = Path.of(System.getProperty("integration.apk")).toAbsolutePath();
        String nonce = UUID.randomUUID().toString();
        var profile = JSON.createObjectNode().put("androidSdk", 30);
        String guestSource = "/data/app/runtime-source-" + nonce + "/base.apk";
        profile.putObject("package").put("apk", apk.toString()).put("guestSourceDir", guestSource);
        String assetEntry;
        String resourceEntry;
        try (var archive = new ZipFile(apk.toFile())) {
            assetEntry = archive.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith("assets/"))
                    .map(entry -> entry.getName()).sorted().findFirst().orElseThrow();
            resourceEntry = archive.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith("res/"))
                    .map(entry -> entry.getName()).sorted().findFirst().orElseThrow();
        }
        var request = new RunRequest(runtime, Fixture.class.getName(),
                Arrays.stream(System.getProperty("integration.fixtureClasspath").split(File.pathSeparator)).map(Path::of).toList(),
                JSON.createObjectNode().put("nonce", nonce).put("asset", assetEntry.substring("assets/".length()))
                        .put("resource", resourceEntry), RunRequest.Mode.ANDROID, profile,
                Path.of(System.getProperty("user.dir")), evidence, Duration.ofSeconds(1), Duration.ofSeconds(5), Map.of(),
                UUID.randomUUID().toString().replace("-", ""));
        try (var handle = new RuntimeClient().start(request)) {
            RunResult result = handle.completion().get(90, TimeUnit.SECONDS);
            assertEquals(result.failure().toString(), RunResult.Status.COMPLETED, result.status());
        }
        assertEquals(nonce, Files.readString(evidence.resolve("startup.bin")));
        JsonNode observed = JSON.readTree(evidence.resolve("startup.json").toFile());
        verifyParserAssembly(runtime, observed);
        var expectedPaths = JSON.createArrayNode();
        for (int index = 0; index < 6; index++) expectedPaths.add(guestSource);
        assertEquals(expectedPaths, observed.required("java_source_paths"));
        assertEquals(expectedPaths, observed.required("jni_source_paths"));
        assertEquals(guestSource, observed.required("apk_asset_path").textValue());
        assertEquals(guestSource, observed.required("native_open_path").textValue());
        try (var archive = new ZipFile(apk.toFile())) {
            try (var input = archive.getInputStream(archive.getEntry(assetEntry))) {
                assertArrayEquals(input.readAllBytes(), Files.readAllBytes(evidence.resolve("asset.bin")));
            }
            try (var input = archive.getInputStream(archive.getEntry(resourceEntry))) {
                assertArrayEquals(input.readAllBytes(), Files.readAllBytes(evidence.resolve("resource.bin")));
            }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(apk)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        assertArrayEquals(digest.digest(), Files.readAllBytes(evidence.resolve("native-apk.sha256")));
    }

    private static void verifyParserAssembly(Path runtime, JsonNode observed) throws Exception {
        String source = "jar:" + runtime.resolve("lib/apk-parser-2.6.11.jar").toUri().toURL()
                + "!/net/dongliu/apk/parser/ApkFile.class";
        assertEquals(source, observed.required("parser_class_resource").asText());
        var providers = new ArrayList<Path>();
        try (var files = Files.list(runtime.resolve("lib"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                try (var jar = new JarFile(file.toFile())) {
                    if (jar.getJarEntry("net/dongliu/apk/parser/ApkFile.class") != null) providers.add(file);
                }
            }
        }
        assertEquals(List.of(runtime.resolve("lib/apk-parser-2.6.11.jar")), providers);
    }

    private static void nativeWitness(WorkerSession session, com.fasterxml.jackson.databind.node.ObjectNode observed) throws Exception {
        AndroidEmulator emulator = session.createEmulator(() -> AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false)).build());
        AndroidResolver resolver = new AndroidResolver(23);
        emulator.getMemory().setLibraryResolver(resolver);
        var vm = session.createVm(emulator, null, new JniConfiguration());
        String source;
        try (var jni = new Arm64Jni(emulator, vm)) {
            int application = jni.wrap(session.application());
            int info = jni.objectCall(application, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
            int manager = jni.objectCall(application, "getPackageManager", "()Landroid/content/pm/PackageManager;");
            int installed = jni.objectCall(manager, "getApplicationInfo", "(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;",
                    jni.wrap(session.application().getPackageName()), 0);
            source = (String) jni.value(jni.objectCall(application, "getPackageCodePath", "()Ljava/lang/String;"));
            observed.putArray("jni_source_paths").add(source)
                    .add((String) jni.value(jni.objectCall(application, "getPackageResourcePath", "()Ljava/lang/String;")))
                    .add((String) jni.objectField(info, "sourceDir", "Ljava/lang/String;"))
                    .add((String) jni.objectField(info, "publicSourceDir", "Ljava/lang/String;"))
                    .add((String) jni.objectField(installed, "sourceDir", "Ljava/lang/String;"))
                    .add((String) jni.objectField(installed, "publicSourceDir", "Ljava/lang/String;"));
        }
        observed.put("native_open_path", source);
        var libc = emulator.getMemory().load(resolver.resolveLibrary(emulator, "libc.so"), false);
        var memory = emulator.getMemory().malloc(65536, true);
        try {
            int fd = libc.findSymbolByName("open").call(emulator, source, IOConstants.O_RDONLY).intValue();
            if (fd < 0) throw new java.io.IOException("Native APK open failed: " + source);
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                int count;
                while ((count = libc.findSymbolByName("read").call(emulator, fd, memory.getPointer(), 65536).intValue()) != 0) {
                    if (count < 0) throw new java.io.IOException("Native APK read failed: " + source);
                    digest.update(memory.getPointer().getByteArray(0, count));
                }
                Files.write(session.outputDirectory().resolve("native-apk.sha256"), digest.digest(), StandardOpenOption.CREATE_NEW);
            } finally {
                assertEquals(0, libc.findSymbolByName("close").call(emulator, fd).intValue());
            }
        } finally {
            memory.free();
        }
    }

    public static final class Fixture implements WorkerEntrypoint {
        @Override public JsonNode run(WorkerSession session, JsonNode arguments) throws Exception {
            var observed = JSON.createObjectNode();
            var application = session.application();
            observed.put("application_package", application.getPackageName());
            var installed = application.getPackageManager().getApplicationInfo(application.getPackageName(), 0);
            var javaPaths = observed.putArray("java_source_paths");
            javaPaths.add(application.getPackageCodePath()).add(application.getPackageResourcePath())
                    .add(application.getApplicationInfo().sourceDir).add(application.getApplicationInfo().publicSourceDir)
                    .add(installed.sourceDir).add(installed.publicSourceDir);
            try (var asset = application.getAssets().open(arguments.required("asset").textValue())) {
                Files.copy(asset, session.outputDirectory().resolve("asset.bin"));
            }
            try (var resource = application.getAssets().openNonAsset(arguments.required("resource").textValue())) {
                Files.copy(resource, session.outputDirectory().resolve("resource.bin"));
            }
            String source = application.getApplicationInfo().sourceDir;
            var packageAssets = Arrays.stream(application.getAssets().getApkAssets())
                    .filter(assets -> source.equals(assets.getAssetPath())).findFirst().orElseThrow();
            observed.put("apk_asset_path", packageAssets.getAssetPath());
            nativeWitness(session, observed);
            observed.put("parser_class_resource", ApkFile.class.getResource("ApkFile.class").toString());
            var resources = observed.putArray("parser_resources");
            for (var resource : Collections.list(getClass().getClassLoader().getResources("net/dongliu/apk/parser/ApkFile.class"))) {
                resources.add(resource.toString());
            }
            Files.writeString(session.outputDirectory().resolve("startup.json"), JSON.writeValueAsString(observed), StandardOpenOption.CREATE_NEW);
            Files.writeString(session.outputDirectory().resolve("startup.bin"), arguments.required("nonce").asText(), StandardOpenOption.CREATE_NEW);
            return observed;
        }
    }
}
