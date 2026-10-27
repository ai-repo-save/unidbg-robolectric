package io.github.aireposave.unidbg.runtime.spi.jni;

import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmField;
import com.github.unidbg.linux.android.dvm.DvmMethod;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.HostJni;
import com.github.unidbg.linux.android.dvm.Jni;
import com.github.unidbg.linux.android.dvm.JniCallPlan;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.VarArg;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.commons.lang3.ClassUtils;

/** Configured routing is bound on the backend; only the selected host operation is offloaded. */
final class JniComposition {
    @FunctionalInterface
    private interface HostBinding { JniCallPlan bind(HostJni provider) throws Throwable; }

    static HostJni wrap(HostJni delegate, JniConfiguration configuration) {
        return (HostJni) Proxy.newProxyInstance(HostJni.class.getClassLoader(), new Class<?>[] {HostJni.class},
                (receiver, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> receiver == arguments[0];
                            case "hashCode" -> System.identityHashCode(receiver);
                            case "toString" -> "ComposedJni[" + delegate.getClass().getName() + "]";
                            default -> throw new IllegalStateException("Unexpected Object method: " + method);
                        };
                    }
                    if (method.getDeclaringClass() == HostJni.class) {
                        if (method.getName().equals("planException")) return reflected(delegate, method, arguments);
                        return bind(delegate, configuration, method.getName(), arguments);
                    }
                    return metadata(delegate, configuration, method, arguments);
                });
    }

    private static Object reflected(Jni provider, Method operation, Object[] arguments) throws Throwable {
        try { return operation.invoke(provider, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static JniCall call(HostJni delegate, Method operation, String signature, Object[] arguments, HostBinding binding) {
        return new JniCall(operation, signature, arguments, delegate, provider -> provider instanceof HostJni host
                ? binding.bind(host) : JniCallPlan.backend(provider));
    }

    private static JniCallPlan bind(HostJni delegate, JniConfiguration configuration, String name, Object[] args) throws Exception {
        DvmClass type = (DvmClass) args[0];
        JniCall call;
        switch (name) {
            case "planMethod" -> {
                DvmObject<?> receiver = (DvmObject<?>) args[1];
                DvmMethod member = (DvmMethod) args[2];
                VarArg values = (VarArg) args[3];
                boolean stat = (Boolean) args[4];
                String descriptor = member.getArgs();
                String result = kind(descriptor.charAt(descriptor.lastIndexOf(')') + 1));
                boolean variadic = values instanceof VaList;
                Method operation = Jni.class.getMethod("call" + (stat ? "Static" : "") + result + "Method" + (variadic ? "V" : ""),
                        BaseVM.class, stat ? DvmClass.class : DvmObject.class, DvmMethod.class, variadic ? VaList.class : VarArg.class);
                call = call(delegate, operation, member.getSignature(), new Object[] {type.vm, stat ? type : receiver, member, values},
                        provider -> provider.planMethod(type, receiver, member, values, stat));
            }
            case "planConstructor" -> {
                DvmMethod member = (DvmMethod) args[1];
                VarArg values = (VarArg) args[2];
                boolean variadic = values instanceof VaList;
                Method operation = Jni.class.getMethod("newObject" + (variadic ? "V" : ""), BaseVM.class, DvmClass.class,
                        DvmMethod.class, variadic ? VaList.class : VarArg.class);
                call = call(delegate, operation, member.getSignature(), new Object[] {type.vm, type, member, values},
                        provider -> provider.planConstructor(type, member, values));
            }
            case "planAllocation" -> {
                String signature = type.getClassName() + "->allocObject";
                Method operation = Jni.class.getMethod("allocObject", BaseVM.class, DvmClass.class, String.class);
                call = call(delegate, operation, signature, new Object[] {type.vm, type, signature}, provider -> provider.planAllocation(type));
            }
            case "planField" -> {
                DvmObject<?> receiver = (DvmObject<?>) args[1];
                DvmField member = (DvmField) args[2];
                boolean stat = (Boolean) args[3], write = (Boolean) args[4];
                Object value = args[5];
                char code = member.getFieldType().charAt(0);
                Class<?> target = stat ? DvmClass.class : DvmObject.class;
                String operationName = (write ? "set" : "get") + (stat ? "Static" : "") + kind(code) + "Field";
                Method operation = write
                        ? Jni.class.getMethod(operationName, BaseVM.class, target, DvmField.class, fieldType(code))
                        : Jni.class.getMethod(operationName, BaseVM.class, target, DvmField.class);
                Object[] original = write ? new Object[] {type.vm, stat ? type : receiver, member, value}
                        : new Object[] {type.vm, stat ? type : receiver, member};
                String signature = member.getDvmClass().getClassName() + "->" + member.getFieldName() + ":" + member.getFieldType();
                call = call(delegate, operation, signature, original, provider -> provider.planField(type, receiver, member, stat, write, value));
            }
            default -> throw new IllegalArgumentException("Unknown host binding operation: " + name);
        }
        try { before(configuration, call); }
        catch (Throwable failure) { return JniCallPlan.failure(failure, false); }
        JniCallPlan plan;
        try {
            var handler = configuration.handler(call.signature());
            Jni owner = configuration.ownerDelegate(owner(call.signature()));
            if (handler != null) plan = handler.plan(call);
            else if (owner != null) plan = call.invoke(owner);
            else if (configuration.proxyOwners().contains(owner(call.signature())) || frameworkMember(call.signature(), call.arguments())) plan = call.proceed();
            else throw new UnsupportedOperationException("Unregistered business JNI: " + call.signature());
        } catch (Throwable failure) {
            failed(configuration, call, failure, false);
            return JniCallPlan.failure(failure, false);
        }
        return plan.observe(new JniCallPlan.Observer() {
            @Override public void completed(Object normalized) throws Throwable { after(configuration, call, normalized); }
            @Override public void failed(Throwable original, boolean targetThrown) { JniComposition.failed(configuration, call, original, targetThrown); }
        });
    }

    private static Object metadata(HostJni delegate, JniConfiguration configuration, Method operation, Object[] arguments) throws Throwable {
        String signature = signature(arguments);
        JniCall call = new JniCall(operation, signature, arguments, delegate,
                provider -> JniCallPlan.backend(provider));
        before(configuration, call);
        Object result;
        try {
            Jni owner = configuration.ownerDelegate(owner(signature));
            boolean accepted = configuration.handler(signature) != null || owner != null;
            boolean proxy = configuration.proxyOwners().contains(owner(signature)) || frameworkMember(signature, arguments);
            if (operation.getName().equals("acceptMethod") || operation.getName().equals("acceptField")) {
                result = accepted || (proxy && (boolean) reflected(delegate, operation, arguments));
            } else if (owner != null) result = reflected(owner, operation, arguments);
            else if (proxy) result = reflected(delegate, operation, arguments);
            else throw new UnsupportedOperationException("Unregistered business JNI metadata: " + signature);
        } catch (Throwable failure) {
            failed(configuration, call, failure, false);
            throw failure;
        }
        after(configuration, call, result);
        return result;
    }

    private static void before(JniConfiguration configuration, JniCall call) throws Throwable {
        for (var observer : configuration.observers()) observer.observe(new JniConfiguration.Event(JniConfiguration.Phase.BEFORE, call, null, null, false));
    }
    private static void after(JniConfiguration configuration, JniCall call, Object result) throws Throwable {
        for (var observer : configuration.observers()) observer.observe(new JniConfiguration.Event(JniConfiguration.Phase.AFTER, call, result, null, false));
    }
    private static void failed(JniConfiguration configuration, JniCall call, Throwable original, boolean targetThrown) {
        for (var observer : configuration.observers()) {
            try { observer.observe(new JniConfiguration.Event(JniConfiguration.Phase.FAILED, call, null, original, targetThrown)); }
            catch (Throwable failure) { if (failure != original) original.addSuppressed(failure); }
        }
    }
    private static String owner(String signature) { return signature.substring(0, signature.indexOf("->")); }
    private static String kind(char code) {
        return switch (code) {
            case 'V' -> "Void"; case 'Z' -> "Boolean"; case 'B' -> "Byte"; case 'C' -> "Char";
            case 'S' -> "Short"; case 'I' -> "Int"; case 'J' -> "Long"; case 'F' -> "Float"; case 'D' -> "Double";
            case 'L', '[' -> "Object"; default -> throw new IllegalArgumentException("JNI value type=" + code);
        };
    }
    private static Class<?> fieldType(char code) {
        return switch (code) {
            case 'Z' -> boolean.class; case 'B' -> byte.class; case 'C' -> char.class; case 'S' -> short.class;
            case 'I' -> int.class; case 'J' -> long.class; case 'F' -> float.class; case 'D' -> double.class;
            case 'L', '[' -> DvmObject.class; default -> throw new IllegalArgumentException("JNI field type=" + code);
        };
    }
    private static String signature(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof DvmMethod method) return method.getSignature();
            if (argument instanceof DvmField field) return field.getDvmClass().getClassName() + "->" + field.getFieldName() + ":" + field.getFieldType();
            if (argument instanceof String text) return text;
        }
        throw new IllegalArgumentException("JNI operation has no member descriptor");
    }
    private static boolean frameworkMember(String signature, Object[] arguments) {
        if (standard(signature)) return true;
        Class<?> host = null;
        for (Object argument : arguments) {
            DvmClass type = argument instanceof DvmClass clazz ? clazz : argument instanceof DvmObject<?> object ? object.getObjectType() : null;
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
            for (var candidate : parent.getFields()) if ((candidate.getName() + ":" + candidate.getType().descriptorString()).equals(member)) return true;
        }
        return false;
    }
    private static boolean standard(String signature) {
        return signature.startsWith("android/") || signature.startsWith("java/") || signature.startsWith("javax/")
                || signature.startsWith("dalvik/") || signature.startsWith("libcore/") || signature.startsWith("org/json/")
                || signature.startsWith("com/android/");
    }
}
