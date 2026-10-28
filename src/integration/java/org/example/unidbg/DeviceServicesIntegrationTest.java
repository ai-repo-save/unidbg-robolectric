package org.example.unidbg;

import android.content.Context;
import android.content.pm.FeatureInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aireposave.unidbg.runtime.client.RuntimeVersion;
import io.github.aireposave.unidbg.runtime.spi.WorkerEntrypoint;
import io.github.aireposave.unidbg.runtime.spi.WorkerSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DeviceServicesIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void profileReachesFrameworkServicesBeforeEntrypoint() throws Exception {
        Path runtime = Path.of(System.getProperty("integration.runtime")).toAbsolutePath();
        Path output = Path.of(System.getProperty("integration.evidenceDir")).toAbsolutePath();
        Files.createDirectories(output);
        var request = JSON.createObjectNode();
        request.put("protocol", RuntimeVersion.PROTOCOL);
        request.put("version", RuntimeVersion.VERSION);
        request.put("sessionId", UUID.randomUUID().toString());
        request.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        request.put("mode", "ANDROID");
        request.put("entrypoint", Fixture.class.getName());
        request.put("runtimeDirectory", runtime.toString());
        request.put("outputDirectory", output.toString());
        request.putArray("classpath");
        request.putObject("arguments");
        request.set("profile", JSON.readTree("""
                {
                  "package": {"name": "org.example.services", "targetSdk": 30},
                  "services": {
                    "systemFeatures": {"org.example.feature.present": true, "org.example.feature.absent": false,
                                       "android.hardware.vulkan.level": false},
                    "systemAvailableFeatures": [
                      {"name": "android.hardware.audio.output"},
                      {"name": "android.hardware.vulkan.level", "version": 1},
                      {"name": null, "reqGlEsVersion": 196610}
                    ],
                    "systemSharedLibraryNames": ["android.test.base", "org.apache.http.legacy"],
                    "display": {
                      "name": "Configured display", "flags": 3, "type": 1, "state": 3,
                      "width": 700, "height": 900, "realWidth": 720, "realHeight": 960,
                      "densityDpi": 320, "xdpi": 321.5, "ydpi": 322.5, "rotation": 1,
                      "mode": {"physicalWidth": 800, "physicalHeight": 1000, "refreshRate": 75.0}
                    },
                    "sensors": [
                      {"type": 1, "name": "Configured accelerometer", "wakeUp": false,
                       "maximumRange": 32.5, "minDelay": 100, "maxDelay": 1000,
                       "fifoMaxEventCount": 12, "fifoReservedEventCount": 3},
                      {"type": 4, "name": "Configured gyroscope", "wakeUp": true},
                      {"type": 10, "name": "Configured linear acceleration"}
                    ]
                  }
                }
                """));
        Path file = output.resolve("request.json");
        Files.writeString(file, JSON.writeValueAsString(request));
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        command.addAll(Files.readAllLines(runtime.resolve("jvm-args.txt")));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("io.github.aireposave.unidbg.runtime.worker.WorkerMain");
        command.add("--request");
        command.add(file.toString());
        Process worker = new ProcessBuilder(command)
                .redirectOutput(output.resolve("launcher.stdout").toFile())
                .redirectError(output.resolve("launcher.stderr").toFile()).start();
        try {
            worker.getOutputStream().close();
            assertEquals("Worker completion deadline", true, worker.waitFor(90, TimeUnit.SECONDS));
            assertEquals(Files.readString(output.resolve("launcher.stderr")), 0, worker.exitValue());
        } finally {
            if (worker.isAlive()) {
                worker.destroyForcibly();
                assertEquals("Worker cleanup deadline", true, worker.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    public static final class Fixture implements WorkerEntrypoint {
        @Override
        public JsonNode run(WorkerSession session, JsonNode arguments) {
            var application = session.application();
            assertEquals(true, application.getPackageManager().hasSystemFeature("org.example.feature.present"));
            assertEquals(false, application.getPackageManager().hasSystemFeature("org.example.feature.absent"));
            FeatureInfo[] features = application.getPackageManager().getSystemAvailableFeatures();
            assertArrayEquals(new String[] {"android.hardware.audio.output", "android.hardware.vulkan.level", null},
                    Arrays.stream(features).map(feature -> feature.name).toArray(String[]::new));
            assertArrayEquals(new int[] {0, 1, 0}, Arrays.stream(features).mapToInt(feature -> feature.version).toArray());
            assertArrayEquals(new int[] {0, 0, 196610}, Arrays.stream(features).mapToInt(feature -> feature.reqGlEsVersion).toArray());
            assertEquals(true, application.getPackageManager().hasSystemFeature("android.hardware.audio.output"));
            assertEquals(true, application.getPackageManager().hasSystemFeature("android.hardware.vulkan.level"));
            assertArrayEquals(new String[] {"android.test.base", "org.apache.http.legacy"},
                    application.getPackageManager().getSystemSharedLibraryNames());

            DisplayManager displays = (DisplayManager) application.getSystemService(Context.DISPLAY_SERVICE);
            Display display = java.util.Arrays.stream(displays.getDisplays())
                    .filter(item -> item.getDisplayId() == Display.DEFAULT_DISPLAY).findFirst().orElseThrow();
            assertEquals("Configured display", display.getName());
            assertEquals(3, display.getFlags());
            assertEquals(Display.TYPE_INTERNAL, display.getType());
            assertEquals(Display.STATE_DOZE, display.getState());
            assertEquals(1, display.getRotation());
            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            assertEquals(700, metrics.widthPixels);
            assertEquals(900, metrics.heightPixels);
            assertEquals(320, metrics.densityDpi);
            assertEquals(321.5f, metrics.xdpi, 0f);
            assertEquals(322.5f, metrics.ydpi, 0f);
            display.getRealMetrics(metrics);
            assertEquals(720, metrics.widthPixels);
            assertEquals(960, metrics.heightPixels);
            assertEquals(800, display.getMode().getPhysicalWidth());
            assertEquals(1000, display.getMode().getPhysicalHeight());
            assertEquals(75f, display.getMode().getRefreshRate(), 0f);

            SensorManager sensors = (SensorManager) application.getSystemService(Context.SENSOR_SERVICE);
            Sensor accelerometer = sensors.getDefaultSensor(1);
            assertEquals(1, accelerometer.getType());
            assertEquals("Configured accelerometer", accelerometer.getName());
            assertEquals(false, accelerometer.isWakeUpSensor());
            assertEquals(32.5f, accelerometer.getMaximumRange(), 0f);
            assertEquals(100, accelerometer.getMinDelay());
            assertEquals(1000, accelerometer.getMaxDelay());
            assertEquals(12, accelerometer.getFifoMaxEventCount());
            assertEquals(3, accelerometer.getFifoReservedEventCount());
            Sensor gyroscope = sensors.getDefaultSensor(4);
            assertEquals(4, gyroscope.getType());
            assertEquals("Configured gyroscope", gyroscope.getName());
            assertEquals(true, gyroscope.isWakeUpSensor());
            Sensor linear = sensors.getDefaultSensor(10);
            assertEquals(10, linear.getType());
            assertEquals("Configured linear acceleration", linear.getName());
            assertEquals(false, linear.isWakeUpSensor());
            assertEquals(null, sensors.getDefaultSensor(2));
            assertEquals(null, sensors.getDefaultSensor(11));
            return JSON.createObjectNode();
        }
    }
}
