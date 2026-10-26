package io.github.aireposave.unidbg.runtime.worker;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

public final class RuntimeApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            var request = RequestData.read();
            var profile = request.required("profile");
            String name = ProfileValues.text(profile.required("package").required("name"), "package.name");
            if (!getPackageName().equals(name)) throw new IllegalStateException("Framework Application identity differs from profile: " + getPackageName());
            PackageInfo info = getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNATURES);
            PackageInventoryProjection.apply(this, request, info);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot configure runtime Application metadata", failure);
        }
    }
}
