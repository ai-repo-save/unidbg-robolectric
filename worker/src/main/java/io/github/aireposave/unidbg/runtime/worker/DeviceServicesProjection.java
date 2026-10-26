package io.github.aireposave.unidbg.runtime.worker;

import android.app.Application;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.SensorBuilder;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSensorManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DeviceServicesProjection {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceServicesProjection.class);

    static void apply(Application application, JsonNode profile) {
        if (!profile.has("services")) return;
        JsonNode services = profile.get("services");
        object(services, "services", Set.of("systemFeatures", "display", "sensors"));
        if (services.has("systemFeatures")) systemFeatures(application, services.get("systemFeatures"));
        if (services.has("display")) display(application, services.get("display"));
        if (services.has("sensors")) sensors(application, services.get("sensors"));
    }

    private static void systemFeatures(Application application, JsonNode features) {
        if (!features.isObject()) throw new IllegalArgumentException("services.systemFeatures must be a JSON object");
        ShadowPackageManager packages = Shadow.extract(application.getPackageManager());
        features.fields().forEachRemaining(entry -> packages.setSystemFeature(entry.getKey(),
                bool(entry.getValue(), "services.systemFeatures." + entry.getKey())));
        LOG.info("event=system_features_projected count={}", features.size());
    }

    private static void display(Application application, JsonNode configuration) {
        object(configuration, "services.display", Set.of("name", "flags", "type", "state", "width", "height",
                "realWidth", "realHeight", "densityDpi", "xdpi", "ydpi", "rotation", "mode"));
        DisplayManager manager = (DisplayManager) application.getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager.getDisplay(Display.DEFAULT_DISPLAY);
        ShadowDisplay shadow = Shadow.extract(display);
        configuration.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            String path = "services.display." + field;
            switch (field) {
                case "name" -> shadow.setName(ProfileValues.text(value, path));
                case "flags" -> shadow.setFlags(integer(value, path));
                case "type" -> shadow.setType(integer(value, path));
                case "state" -> shadow.setState(integer(value, path));
                case "width" -> shadow.setWidth(integer(value, path));
                case "height" -> shadow.setHeight(integer(value, path));
                case "realWidth" -> shadow.setRealWidth(integer(value, path));
                case "realHeight" -> shadow.setRealHeight(integer(value, path));
                case "densityDpi" -> shadow.setDensityDpi(integer(value, path));
                case "xdpi" -> shadow.setXdpi(decimal(value, path));
                case "ydpi" -> shadow.setYdpi(decimal(value, path));
                case "rotation" -> shadow.setRotation(integer(value, path));
                case "mode" -> mode(display, value);
                default -> throw new IllegalArgumentException("Unsupported profile field: " + path);
            }
        });
        LOG.info("event=display_profile_projected displayId={}", display.getDisplayId());
    }

    private static void mode(Display display, JsonNode configuration) {
        object(configuration, "services.display.mode", Set.of("physicalWidth", "physicalHeight", "refreshRate"));
        Display.Mode mode = ShadowDisplayManager.ModeBuilder.modeBuilder(display.getMode().getModeId())
                .setWidth(integer(configuration.required("physicalWidth"), "services.display.mode.physicalWidth"))
                .setHeight(integer(configuration.required("physicalHeight"), "services.display.mode.physicalHeight"))
                .setRefreshRate(decimal(configuration.required("refreshRate"), "services.display.mode.refreshRate"))
                .build();
        ShadowDisplayManager.setSupportedModes(display.getDisplayId(), mode);
    }

    private static void sensors(Application application, JsonNode configurations) {
        if (!configurations.isArray()) throw new IllegalArgumentException("services.sensors must be a JSON array");
        List<Sensor> sensors = new ArrayList<>();
        Set<Integer> types = new HashSet<>();
        for (int index = 0; index < configurations.size(); index++) {
            JsonNode configuration = configurations.get(index);
            String path = "services.sensors[" + index + "]";
            object(configuration, path, Set.of("type", "name", "wakeUp", "maximumRange", "minDelay",
                    "maxDelay", "fifoMaxEventCount", "fifoReservedEventCount"));
            int type = integer(configuration.required("type"), path + ".type");
            if (!types.add(type)) throw new IllegalArgumentException("Duplicate default sensor type: " + type);
            SensorBuilder builder = SensorBuilder.newBuilder().setType(type);
            configuration.fields().forEachRemaining(entry -> {
                String field = entry.getKey();
                JsonNode value = entry.getValue();
                String fieldPath = path + "." + field;
                switch (field) {
                    case "type" -> { }
                    case "name" -> builder.setName(ProfileValues.text(value, fieldPath));
                    case "wakeUp" -> builder.setWakeUpFlag(bool(value, fieldPath));
                    case "maximumRange" -> builder.setMaximumRange(decimal(value, fieldPath));
                    case "minDelay" -> builder.setMinDelay(integer(value, fieldPath));
                    case "maxDelay" -> builder.setMaxDelay(integer(value, fieldPath));
                    case "fifoMaxEventCount" -> builder.setFifoMaxEventCount(integer(value, fieldPath));
                    case "fifoReservedEventCount" -> builder.setFifoReservedEventCount(integer(value, fieldPath));
                    default -> throw new IllegalArgumentException("Unsupported profile field: " + fieldPath);
                }
            });
            sensors.add(builder.build());
        }
        SensorManager manager = (SensorManager) application.getSystemService(Context.SENSOR_SERVICE);
        ShadowSensorManager shadow = Shadow.extract(manager);
        for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) shadow.removeSensor(sensor);
        sensors.forEach(shadow::addSensor);
        LOG.info("event=default_sensors_projected count={}", sensors.size());
    }

    private static void object(JsonNode value, String path, Set<String> fields) {
        if (!value.isObject()) throw new IllegalArgumentException(path + " must be a JSON object");
        value.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new IllegalArgumentException("Unsupported profile field: " + path + "." + field);
        });
    }

    private static int integer(JsonNode value, String path) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException(path + " must be a 32-bit integer");
        return value.intValue();
    }

    private static float decimal(JsonNode value, String path) {
        if (!value.isNumber() || !Float.isFinite(value.floatValue())) throw new IllegalArgumentException(path + " must be a finite float");
        return value.floatValue();
    }

    private static boolean bool(JsonNode value, String path) {
        if (!value.isBoolean()) throw new IllegalArgumentException(path + " must be a JSON boolean");
        return value.booleanValue();
    }

    private DeviceServicesProjection() {}
}
