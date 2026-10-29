package io.github.aireposave.unidbg.runtime.worker;

import io.github.aireposave.unidbg.runtime.egl.DeviceEglNatives;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = RuntimeApplication.class,
        shadows = DeviceGlShadow.class)
public final class AndroidBootstrap {
    @Test
    public void executeSession() throws Exception {
        DeviceEglNatives.load();
        DeviceEgl.install();
        WorkerExecution.runSession(RuntimeEnvironment.getApplication());
    }
}
