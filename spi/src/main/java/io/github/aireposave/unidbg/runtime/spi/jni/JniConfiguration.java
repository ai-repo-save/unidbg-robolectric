package io.github.aireposave.unidbg.runtime.spi.jni;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

public final class JniConfiguration {
    private final Map<String, Handler> handlers = new LinkedHashMap<>();
    private final List<Observer> observers = new ArrayList<>();

    public JniConfiguration handle(String descriptor, Handler handler) {
        if (handlers.putIfAbsent(descriptor, Objects.requireNonNull(handler)) != null) {
            throw new IllegalArgumentException("Duplicate JNI descriptor: " + descriptor);
        }
        return this;
    }
    public JniConfiguration observe(Observer observer) {
        observers.add(Objects.requireNonNull(observer));
        return this;
    }
    public Handler handler(String descriptor) { return handlers.get(descriptor); }
    public List<Observer> observers() { return List.copyOf(observers); }

    @FunctionalInterface
    public interface Handler { Object invoke(JniCall call) throws Throwable; }
    @FunctionalInterface
    public interface Observer { void observe(Event event) throws Throwable; }
    public enum Phase { BEFORE, AFTER, FAILED }
    public record Event(Phase phase, JniCall call, Object result, Throwable failure) {}
}
