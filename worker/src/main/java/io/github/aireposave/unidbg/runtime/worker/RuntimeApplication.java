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
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            PackageInventoryProjection.apply(this, request, info);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot configure runtime Application metadata", failure);
        }
    }
}
