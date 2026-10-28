package io.github.aireposave.unidbg.runtime.worker;

import com.fasterxml.jackson.databind.JsonNode;
import org.robolectric.shadows.ShadowBuild;

final class BuildProjection {
    static void apply(JsonNode build) {
        build.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            if (field.equals("VERSION")) {
                version(entry.getValue());
                return;
            }
            String value = ProfileValues.text(entry.getValue(), "build." + field);
            switch (field) {
                case "MODEL" -> ShadowBuild.setModel(value);
                case "MANUFACTURER" -> ShadowBuild.setManufacturer(value);
                case "BRAND" -> ShadowBuild.setBrand(value);
                case "DEVICE" -> ShadowBuild.setDevice(value);
                case "PRODUCT" -> ShadowBuild.setProduct(value);
                case "BOARD" -> ShadowBuild.setBoard(value);
                case "HARDWARE" -> ShadowBuild.setHardware(value);
                case "FINGERPRINT" -> ShadowBuild.setFingerprint(value);
                case "SERIAL" -> ShadowBuild.setSerial(value);
                case "ID" -> ShadowBuild.setId(value);
                case "DISPLAY" -> ShadowBuild.setDisplay(value);
                case "TAGS" -> ShadowBuild.setTags(value);
                case "TYPE" -> ShadowBuild.setType(value);
                default -> throw new IllegalArgumentException("Unsupported Build profile field: " + field);
            }
        });
    }

    private static void version(JsonNode version) {
        if (!version.isObject()) throw new IllegalArgumentException("build.VERSION must be a JSON object");
        version.fields().forEachRemaining(entry -> {
            String value = ProfileValues.text(entry.getValue(), "build.VERSION." + entry.getKey());
            switch (entry.getKey()) {
                case "RELEASE" -> ShadowBuild.setVersionRelease(value);
                case "INCREMENTAL" -> ShadowBuild.setVersionIncremental(value);
                case "SECURITY_PATCH" -> ShadowBuild.setVersionSecurityPatch(value);
                default -> throw new IllegalArgumentException("Unsupported Build.VERSION profile field: " + entry.getKey());
            }
        });
    }

    private BuildProjection() {}
}
