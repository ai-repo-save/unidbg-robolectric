package io.github.aireposave.unidbg.runtime.worker;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Base64;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class PackageInventoryProjection {
    private static final Logger LOG = LoggerFactory.getLogger(PackageInventoryProjection.class);

    static void apply(Application application, JsonNode request, PackageInfo host) {
        var previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("trace_id", request.required("traceId").textValue());
            MDC.put("session_id", request.required("sessionId").textValue());
            applyProfile(application, request.required("profile"), host);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    private static void applyProfile(Application application, JsonNode profile, PackageInfo host) {
        ShadowPackageManager packages = Shadow.extract(application.getPackageManager());
        host.applicationInfo = application.getApplicationInfo();
        install(application, packages, host, profile.required("package"), "profile.package");
        if (profile.has("packages")) {
            JsonNode inventory = profile.required("packages");
            for (int i = 0; i < inventory.size(); i++) {
                JsonNode entry = inventory.get(i);
                PackageInfo info = new PackageInfo();
                info.packageName = ProfileValues.text(entry.required("name"), "profile.packages[" + i + "].name");
                info.applicationInfo = new ApplicationInfo();
                info.applicationInfo.packageName = info.packageName;
                install(application, packages, info, entry, "profile.packages[" + i + "]");
            }
        }
    }

    private static void install(Application application, ShadowPackageManager packages, PackageInfo info, JsonNode profile, String field) {
        if (profile.has("versionName")) info.versionName = profile.required("versionName").isNull()
                ? null : ProfileValues.text(profile.required("versionName"), field + ".versionName");
        if (profile.has("versionCode")) info.setLongVersionCode(ProfileValues.longInteger(profile, "versionCode", 0));
        ApplicationInfo app = info.applicationInfo;
        if (profile.has("targetSdk")) app.targetSdkVersion = ProfileValues.integer(profile, "targetSdk", 0);
        if (profile.has("flags")) app.flags = ProfileValues.integer(profile, "flags", 0);
        if (profile.has("uid")) {
            int uid = ProfileValues.integer(profile, "uid", 0);
            if (uid < 0) throw new IllegalArgumentException(field + ".uid must be a nonnegative integer");
            app.uid = uid;
        }
        if (profile.has("nativeLibraryDir")) app.nativeLibraryDir = ProfileValues.text(profile.required("nativeLibraryDir"), field + ".nativeLibraryDir");
        if (profile.has("signatures")) {
            JsonNode signatures = profile.required("signatures");
            if (!signatures.isArray()) throw new IllegalArgumentException(field + ".signatures must be a JSON array");
            info.signatures = new Signature[signatures.size()];
            for (int i = 0; i < signatures.size(); i++) {
                info.signatures[i] = new Signature(Base64.getDecoder().decode(ProfileValues.text(signatures.get(i), field + ".signatures[" + i + "]")));
            }
        }
        permissions(info, profile, field);
        packages.installPackage(info);
        if (profile.has("installer")) application.getPackageManager().setInstallerPackageName(info.packageName,
                profile.required("installer").isNull() ? null : ProfileValues.text(profile.required("installer"), field + ".installer"));
        LOG.info("event=package_profile_installed package={} source={} signatures={} requestedPermissions={}", info.packageName, field,
                info.signatures == null ? null : info.signatures.length, info.requestedPermissions == null ? null : info.requestedPermissions.length);
    }

    private static void permissions(PackageInfo info, JsonNode profile, String field) {
        if (profile.has("requestedPermissions") != profile.has("requestedPermissionsFlags")) {
            throw new IllegalArgumentException(field + " requires requestedPermissions and requestedPermissionsFlags together");
        }
        if (!profile.has("requestedPermissions")) return;
        JsonNode names = profile.required("requestedPermissions");
        JsonNode flags = profile.required("requestedPermissionsFlags");
        if (names.isNull() && flags.isNull()) {
            info.requestedPermissions = null;
            info.requestedPermissionsFlags = null;
            return;
        }
        if (!names.isArray() || !flags.isArray() || names.size() != flags.size()) {
            throw new IllegalArgumentException(field + ".requestedPermissions and requestedPermissionsFlags must be parallel JSON arrays or both null");
        }
        info.requestedPermissions = new String[names.size()];
        info.requestedPermissionsFlags = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            info.requestedPermissions[i] = ProfileValues.text(names.get(i), field + ".requestedPermissions[" + i + "]");
            JsonNode flag = flags.get(i);
            if (!flag.isIntegralNumber() || !flag.canConvertToInt()) {
                throw new IllegalArgumentException(field + ".requestedPermissionsFlags[" + i + "] must be a 32-bit integer");
            }
            info.requestedPermissionsFlags[i] = flag.intValue();
        }
    }

    private PackageInventoryProjection() {}
}
