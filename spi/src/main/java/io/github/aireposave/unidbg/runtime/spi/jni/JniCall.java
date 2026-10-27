package io.github.aireposave.unidbg.runtime.spi.jni;

import com.github.unidbg.linux.android.dvm.Jni;
import java.lang.reflect.Method;

/** Backend-bound operation context for synchronous values and tail delegation. */
public record JniCall(Method method, String signature, Object[] arguments,
                      Jni defaultProvider, Continuation continuation) {
    public Object proceed() throws Throwable { return continuation.invoke(defaultProvider); }
    public Object invoke(Jni provider) throws Throwable { return continuation.invoke(provider); }
    @FunctionalInterface
    public interface Continuation { Object invoke(Jni provider) throws Throwable; }
}
