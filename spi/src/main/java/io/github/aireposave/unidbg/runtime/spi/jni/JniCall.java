package io.github.aireposave.unidbg.runtime.spi.jni;

import com.github.unidbg.linux.android.dvm.Jni;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public record JniCall(Method method, String signature, Object[] arguments, Continuation continuation) {
    public Object proceed() throws Throwable { return continuation.invoke(); }
    public Object invoke(Jni delegate) throws Throwable {
        try { return method.invoke(delegate, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    @FunctionalInterface
    public interface Continuation { Object invoke() throws Throwable; }
}
