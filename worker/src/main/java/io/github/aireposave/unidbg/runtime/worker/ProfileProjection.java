package io.github.aireposave.unidbg.runtime.worker;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowSystemProperties;

final class ProfileProjection {
    static Map<String, String> nativeProperties(JsonNode profile, boolean android) {
        if (!profile.isObject()) throw new IllegalArgumentException("profile must be a JSON object");
        for (String field : new String[] {"build", "nativeProperties", "settings", "preferences"}) {
            if (profile.has(field) && !profile.get(field).isObject()) throw new IllegalArgumentException("profile." + field + " must be a JSON object");
        }
        Map<String, String> properties = new LinkedHashMap<>();
        profile.path("nativeProperties").fields().forEachRemaining(entry -> properties.put(entry.getKey(), ProfileValues.text(entry.getValue(), entry.getKey())));
        if (android) same(properties, "ro.build.version.sdk", "30");
        profile.path("build").fields().forEachRemaining(entry -> {
            String property = switch (entry.getKey()) {
                case "MODEL" -> "ro.product.model";
                case "MANUFACTURER" -> "ro.product.manufacturer";
                case "BRAND" -> "ro.product.brand";
                case "DEVICE" -> "ro.product.device";
                case "PRODUCT" -> "ro.product.name";
                case "BOARD" -> "ro.product.board";
                case "HARDWARE" -> "ro.hardware";
                case "FINGERPRINT" -> "ro.build.fingerprint";
                case "SERIAL" -> "ro.serialno";
                default -> throw new IllegalArgumentException("Unsupported Build profile field: " + entry.getKey());
            };
            same(properties, property, ProfileValues.text(entry.getValue(), entry.getKey()));
        });
        return java.util.Collections.unmodifiableMap(properties);
    }

    private static void same(Map<String, String> properties, String key, String value) {
        String previous = properties.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("Conflicting profile fact: " + key);
    }

    static void apply(Application application, JsonNode profile, Map<String, String> properties) {
        profile.path("build").fields().forEachRemaining(entry -> {
            String value = ProfileValues.text(entry.getValue(), entry.getKey());
            switch (entry.getKey()) {
                case "MODEL" -> ShadowBuild.setModel(value);
                case "MANUFACTURER" -> ShadowBuild.setManufacturer(value);
                case "BRAND" -> ShadowBuild.setBrand(value);
                case "DEVICE" -> ShadowBuild.setDevice(value);
                case "PRODUCT" -> ShadowBuild.setProduct(value);
                case "BOARD" -> ShadowBuild.setBoard(value);
                case "HARDWARE" -> ShadowBuild.setHardware(value);
                case "FINGERPRINT" -> ShadowBuild.setFingerprint(value);
                case "SERIAL" -> ShadowBuild.setSerial(value);
                default -> throw new IllegalArgumentException("Unsupported Build profile field: " + entry.getKey());
            }
        });
        properties.forEach(ShadowSystemProperties::override);
        profile.path("settings").fields().forEachRemaining(namespace -> {
            if (!namespace.getValue().isObject()) throw new IllegalArgumentException("Settings namespace must be a JSON object: " + namespace.getKey());
            namespace.getValue().fields().forEachRemaining(entry -> {
                String value = entry.getValue().isNull() ? null : ProfileValues.text(entry.getValue(), entry.getKey());
                boolean written = switch (namespace.getKey()) {
                    case "global" -> Settings.Global.putString(application.getContentResolver(), entry.getKey(), value);
                    case "secure" -> Settings.Secure.putString(application.getContentResolver(), entry.getKey(), value);
                    case "system" -> Settings.System.putString(application.getContentResolver(), entry.getKey(), value);
                    default -> throw new IllegalArgumentException("Unsupported Settings namespace: " + namespace.getKey());
                };
                if (!written) throw new IllegalStateException("Setting rejected: " + namespace.getKey() + "/" + entry.getKey());
            });
        });
        profile.path("preferences").fields().forEachRemaining(file -> {
            if (!file.getValue().isObject()) throw new IllegalArgumentException("Preferences file must be a JSON object: " + file.getKey());
            SharedPreferences.Editor editor = application.getSharedPreferences(file.getKey(), Context.MODE_PRIVATE).edit();
            file.getValue().fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual()) editor.putString(entry.getKey(), value.textValue());
                else if (value.isBoolean()) editor.putBoolean(entry.getKey(), value.booleanValue());
                else if (value.isInt()) editor.putInt(entry.getKey(), value.intValue());
                else if (value.isLong()) editor.putLong(entry.getKey(), value.longValue());
                else throw new IllegalArgumentException("Unsupported preference value: " + entry.getKey());
            });
            if (!editor.commit()) throw new IllegalStateException("Preference commit rejected: " + file.getKey());
        });
        DeviceServicesProjection.apply(application, profile);
    }
    private ProfileProjection() {}
}
