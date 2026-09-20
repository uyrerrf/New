package com.fason.app.features.camera;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

public final class DummyLifecycleOwner implements LifecycleOwner {
    private static DummyLifecycleOwner instance;
    private final LifecycleRegistry registry;

    private DummyLifecycleOwner() {
        registry = new LifecycleRegistry(this);
        registry.setCurrentState(Lifecycle.State.RESUMED);
    }

    public static synchronized DummyLifecycleOwner get() {
        if (instance == null) {
            instance = new DummyLifecycleOwner();
        }
        return instance;
    }

    public void pause() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
    }

    public void resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    public static synchronized void reset() {
        if (instance != null) {
            instance.pause();
        }
        instance = null;
    }

    @Override
    public Lifecycle getLifecycle() {
        return registry;
    }
}
