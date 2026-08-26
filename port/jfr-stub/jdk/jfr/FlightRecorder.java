package jdk.jfr;

import java.util.Collections;
import java.util.List;

public final class FlightRecorder {
    private FlightRecorder() {}
    public static boolean isAvailable() { return false; }
    public static boolean isInitialized() { return false; }
    public static FlightRecorder getFlightRecorder() { throw new IllegalStateException("flight recorder unavailable"); }
    public static void addPeriodicEvent(Class<? extends Event> eventClass, Runnable hook) {}
    public static boolean removePeriodicEvent(Runnable hook) { return false; }
    public static void addListener(Object listener) {}
    public static boolean removeListener(Object listener) { return false; }
    public List<Object> getRecordings() { return Collections.emptyList(); }
}
