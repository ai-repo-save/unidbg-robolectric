package io.github.aireposave.unidbg.runtime.spi.jni;

import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.HostJni;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassLoader;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmClass;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObjectVisitor;
import com.github.unidbg.linux.android.dvm.jni.ProxyJni;

public final class RuntimeClassFactory extends ProxyClassFactory {
    private final JniConfiguration configuration;

    public RuntimeClassFactory(ClassLoader androidLoader, JniConfiguration configuration) {
        super(androidLoader);
        this.configuration = configuration;

    }

    public HostJni jni() {
        return JniComposition.wrap(new ProxyJni(classLoader, visitor, null), configuration);
    }

    @Override
    public DvmClass createClass(BaseVM vm, String name, DvmClass parent, DvmClass[] interfaces) {
        return new RuntimeClass(vm, name, parent, interfaces, classLoader, visitor, configuration);
    }

    private static final class RuntimeClass extends ProxyDvmClass {
        RuntimeClass(BaseVM vm, String name, DvmClass parent, DvmClass[] interfaces,
                     ProxyClassLoader loader, ProxyDvmObjectVisitor visitor, JniConfiguration configuration) {
            super(vm, name, parent, interfaces, loader, visitor, null);
            setJni(JniComposition.wrap(new ProxyJni(loader, visitor, null), configuration));
        }
    }
}
