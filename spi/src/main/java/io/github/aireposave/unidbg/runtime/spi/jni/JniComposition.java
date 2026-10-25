package io.github.aireposave.unidbg.runtime.spi.jni;

import com.github.unidbg.linux.android.dvm.DvmField;
import com.github.unidbg.linux.android.dvm.DvmMethod;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.Jni;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.commons.lang3.ClassUtils;

final class JniComposition {
    static Jni wrap(Jni delegate, JniConfiguration configuration) {
        return (Jni) Proxy.newProxyInstance(Jni.class.getClassLoader(), new Class<?>[] {Jni.class}, (receiver, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> receiver == arguments[0];
                    case "hashCode" -> System.identityHashCode(receiver);
                    case "toString" -> "ComposedJni[" + delegate.getClass().getName() + "]";
                    default -> throw new IllegalStateException("Unexpected Object method: " + method);
                };
            }
            return invoke(delegate, configuration, method, arguments);
        });
    }

    private static Object invoke(Jni delegate, JniConfiguration configuration, Method method, Object[] arguments) throws Throwable {
        String signature = signature(arguments);
        JniCall call = new JniCall(method, signature, arguments, () -> {
            try { return method.invoke(delegate, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var observers = configuration.observers();
        for (var observer : observers) observer.observe(new JniConfiguration.Event(JniConfiguration.Phase.BEFORE, call, null, null));
        Object result;
        try {
            var handler = configuration.handler(signature);
            String ownerName = signature.substring(0, signature.indexOf("->"));
            var owner = configuration.ownerDelegate(ownerName);
            boolean proxy = configuration.proxyOwners().contains(ownerName);
            if (method.getName().equals("acceptMethod") || method.getName().equals("acceptField")) {
                result = handler != null || owner != null || ((proxy || frameworkMember(signature, arguments)) && (boolean) call.proceed());
            } else {
                if (handler != null) result = handler.invoke(call);
                else if (owner != null) result = call.invoke(owner);
                else if (proxy || frameworkMember(signature, arguments)) result = call.proceed();
                else throw new UnsupportedOperationException("Unregistered business JNI: " + signature);
            }
        } catch (Throwable failure) {
            for (var observer : observers) {
                try { observer.observe(new JniConfiguration.Event(JniConfiguration.Phase.FAILED, call, null, failure)); }
                catch (Throwable observerFailure) { if (observerFailure != failure) failure.addSuppressed(observerFailure); }
            }
            throw failure;
        }
        for (var observer : observers) observer.observe(new JniConfiguration.Event(JniConfiguration.Phase.AFTER, call, result, null));
        return result;
    }

    private static String signature(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof DvmMethod method) return method.getSignature();
            if (argument instanceof DvmField field) {
                return field.getDvmClass().getClassName() + "->" + field.getFieldName() + ":" + field.getFieldType();
            }
            if (argument instanceof String text) return text;
        }
        throw new IllegalArgumentException("JNI operation has no member descriptor");
    }

    private static boolean frameworkMember(String signature, Object[] arguments) {
        if (standard(signature)) return true;
        Class<?> host = null;
        for (Object argument : arguments) {
            DvmClass type = argument instanceof DvmClass clazz ? clazz
                    : argument instanceof DvmObject<?> object ? object.getObjectType() : null;
            if (type != null && type.getValue() instanceof Class<?> value) { host = value; break; }
        }
        String member = signature.substring(signature.indexOf("->") + 2);
        if (host == null || member.startsWith("<init>")) return false;
        for (Class<?> parent : ClassUtils.hierarchy(host, ClassUtils.Interfaces.INCLUDE)) {
            if (!standard(parent.getName().replace('.', '/') + "->")) continue;
            for (Method candidate : parent.getMethods()) {
                String descriptor = candidate.getName() + MethodType.methodType(candidate.getReturnType(), candidate.getParameterTypes()).descriptorString();
                if (descriptor.equals(member)) return true;
            }
            for (var candidate : parent.getFields()) {
                if ((candidate.getName() + ":" + candidate.getType().descriptorString()).equals(member)) return true;
            }
        }
        return false;
    }

    private static boolean standard(String signature) {
        return signature.startsWith("android/") || signature.startsWith("java/")
                || signature.startsWith("javax/") || signature.startsWith("dalvik/")
                || signature.startsWith("libcore/") || signature.startsWith("org/json/")
                || signature.startsWith("com/android/");
    }

}
