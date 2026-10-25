package org.example.unidbg;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class AndroidSession {
    private static final Logger LOG = LoggerFactory.getLogger(AndroidSession.class);
    private static final String KEY = "unidbg_probe";
    private static final String PREFERENCES = "jni-probe";
    private static final String PACKAGE = "org.example.installed";
    private static final String GET_SETTING = "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;";
    private static final String PUT_SETTING = "(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z";

    @Test
    public void androidObjectsThroughArm64Jni() throws Exception {
        String session = System.getProperty("integration.session");
        MDC.put("run_id", System.getProperty("integration.runId"));
        MDC.put("session_id", session);
        Application application = RuntimeEnvironment.getApplication();
        LOG.info("event=session_started api={} applicationLoader={}", RuntimeEnvironment.getApiLevel(),
                application.getClass().getClassLoader());
        try (Arm64Jni jni = new Arm64Jni(application.getClass().getClassLoader())) {
            int context = jni.wrap(application);
            int resolver = jni.objectCall(context, "getContentResolver", "()Landroid/content/ContentResolver;");
            int key = jni.wrap(KEY);
            int preferences = jni.objectCall(context, "getSharedPreferences",
                    "(Ljava/lang/String;I)Landroid/content/SharedPreferences;", jni.wrap(PREFERENCES), Context.MODE_PRIVATE);

            assertEquals(null, jni.value(jni.staticObjectCall("android/provider/Settings$Global", "getString", GET_SETTING, resolver, key)));
            assertEquals(null, jni.value(jni.staticObjectCall("android/provider/Settings$Secure", "getString", GET_SETTING, resolver, key)));
            assertEquals("unset", jni.value(jni.objectCall(preferences, "getString",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", key, jni.wrap("unset"))));
            LOG.info("event=session_initial_state_verified global=null secure=null preference=unset");

            String global = "global-" + session;
            String secure = "secure-" + session;
            assertEquals(true, jni.staticBooleanCall("android/provider/Settings$Global", "putString", PUT_SETTING, resolver, key, jni.wrap(global)));
            assertEquals(true, jni.staticBooleanCall("android/provider/Settings$Secure", "putString", PUT_SETTING, resolver, key, jni.wrap(secure)));
            assertEquals(global, jni.value(jni.staticObjectCall("android/provider/Settings$Global", "getString", GET_SETTING, resolver, key)));
            assertEquals(secure, jni.value(jni.staticObjectCall("android/provider/Settings$Secure", "getString", GET_SETTING, resolver, key)));
            assertEquals(global, Settings.Global.getString(application.getContentResolver(), KEY));
            assertEquals(secure, Settings.Secure.getString(application.getContentResolver(), KEY));
            LOG.info("event=settings_verified global={} secure={}", global, secure);

            String model = "JNI-model-" + session;
            ShadowBuild.setModel(model);
            assertEquals(model, jni.staticObjectField("android/os/Build", "MODEL", "Ljava/lang/String;"));
            LOG.info("event=build_verified model={}", model);

            PackageInfo installed = new PackageInfo();
            installed.packageName = PACKAGE;
            installed.versionName = "release-" + session;
            ShadowPackageManager shadowManager = Shadow.extract(application.getPackageManager());
            shadowManager.installPackage(installed);
            int manager = jni.objectCall(context, "getPackageManager", "()Landroid/content/pm/PackageManager;");
            int info = jni.objectCall(manager, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;", jni.wrap(PACKAGE), 0);
            assertEquals(PACKAGE, jni.objectField(info, "packageName", "Ljava/lang/String;"));
            assertEquals(installed.versionName, jni.objectField(info, "versionName", "Ljava/lang/String;"));
            LOG.info("event=package_verified package={} version={}", PACKAGE, installed.versionName);

            int editor = jni.objectCall(preferences, "edit", "()Landroid/content/SharedPreferences$Editor;");
            String preference = "preference-" + session;
            int updated = jni.objectCall(editor, "putString",
                    "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;", key, jni.wrap(preference));
            assertEquals(true, jni.booleanCall(updated, "commit", "()Z"));
            Context independentContext = application.createPackageContext(application.getPackageName(), 0);
            SharedPreferences hostPreferences = independentContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            assertEquals(Map.of(KEY, preference), hostPreferences.getAll());
            assertEquals(preference, jni.value(jni.objectCall(preferences, "getString",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", key, jni.wrap("unset"))));
            LOG.info("event=preferences_verified values={}", hostPreferences.getAll());
            preserveDataDirectory(application.getDataDir().toPath(), session);
            logNativeMappings();
            LOG.info("event=session_completed");
        } catch (Exception | AssertionError failure) {
            LOG.error("event=session_failed", failure);
            throw failure;
        } finally {
            LOG.info("event=session_closed");
            MDC.clear();
        }
    }

    private static void preserveDataDirectory(Path dataDirectory, String session) throws Exception {
        Path destination = Path.of(System.getProperty("integration.evidenceDir"), session, "application-data");
        try (Stream<Path> files = Files.walk(dataDirectory)) {
            List<Path> entries = files.filter(Files::isRegularFile).sorted().toList();
            for (Path file : entries) {
                Path relative = dataDirectory.relativize(file);
                Path copy = destination.resolve(relative);
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy)));
                LOG.info("event=data_file_preserved source={} evidence={} bytes={} sha256={}",
                        file, copy, Files.size(copy), hash);
            }
            LOG.info("event=data_directory_preserved source={} evidence={} files={}",
                    dataDirectory, destination, entries.size());
        }
    }

    private static void logNativeMappings() throws Exception {
        List<String> mappings = Files.readAllLines(Path.of("/proc/self/maps"));
        LOG.info("event=native_mappings_observed unicorn={} robolectric={}",
                mappings.stream().filter(mapping -> mapping.contains("libunicorn")).toList(),
                mappings.stream().filter(mapping -> mapping.contains("robolectric-nativeruntime")).toList());
    }
}
