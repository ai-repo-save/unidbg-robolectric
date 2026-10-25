package io.github.aireposave.unidbg.runtime.worker;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = RuntimeApplication.class)
public final class AndroidBootstrap {
    @Test
    public void executeSession() throws Exception {
        WorkerExecution.runSession(RuntimeEnvironment.getApplication());
    }
}
