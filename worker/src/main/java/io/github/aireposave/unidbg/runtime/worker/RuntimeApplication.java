package io.github.aireposave.unidbg.runtime.worker;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.util.Base64;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

public final class RuntimeApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            var profile = RequestData.read().required("profile").required("package");
            String name = ProfileValues.text(profile.required("name"), "package.name");
            if (!getPackageName().equals(name)) throw new IllegalStateException("Framework Application identity differs from profile: " + getPackageName());
            PackageInfo info = getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNATURES);
            info.versionName = profile.hasNonNull("versionName") ? ProfileValues.text(profile.required("versionName"), "package.versionName") : null;
            info.setLongVersionCode(ProfileValues.longInteger(profile, "versionCode", 0));
            ApplicationInfo app = getApplicationInfo();
            app.targetSdkVersion = ProfileValues.integer(profile, "targetSdk", 30);
            if (profile.has("flags")) app.flags = ProfileValues.integer(profile, "flags", 0);
            if (profile.has("nativeLibraryDir")) app.nativeLibraryDir = ProfileValues.text(profile.required("nativeLibraryDir"), "package.nativeLibraryDir");
            if (profile.has("apk")) {
                app.sourceDir = ProfileValues.text(profile.required("apk"), "package.apk");
                app.publicSourceDir = app.sourceDir;
            }
            info.applicationInfo = app;
            if (profile.has("signatures")) {
                var signatures = profile.required("signatures");
                if (!signatures.isArray()) throw new IllegalArgumentException("package.signatures must be a JSON array");
                info.signatures = new Signature[signatures.size()];
                for (int i = 0; i < signatures.size(); i++) info.signatures[i] = new Signature(Base64.getDecoder().decode(ProfileValues.text(signatures.get(i), "package.signatures[]")));
            }
            ShadowPackageManager packages = Shadow.extract(getPackageManager());
            packages.installPackage(info);
            if (profile.has("installer")) getPackageManager().setInstallerPackageName(name, ProfileValues.text(profile.required("installer"), "package.installer"));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot configure runtime Application metadata", failure);
        }
    }
}
