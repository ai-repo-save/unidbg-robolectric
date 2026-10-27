package io.github.aireposave.unidbg.runtime.spi.jni;

import com.github.unidbg.linux.android.dvm.Jni;
import com.github.unidbg.linux.android.dvm.JniCallPlan;
import java.lang.reflect.Method;

/** Backend-bound operation context; continuations return the bottom-layer plan explicitly. */
public record JniCall(Method method, String signature, Object[] arguments,
                      Jni defaultProvider, Continuation continuation) {
    public JniCallPlan proceed() throws Throwable { return continuation.plan(defaultProvider); }
    public JniCallPlan invoke(Jni provider) throws Throwable { return continuation.plan(provider); }
    @FunctionalInterface
    public interface Continuation { JniCallPlan plan(Jni provider) throws Throwable; }
}
