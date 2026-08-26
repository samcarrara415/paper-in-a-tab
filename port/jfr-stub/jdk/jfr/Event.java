package jdk.jfr;

/** Minimal JFR facade for runtimes without the module (CheerpJ). */
public class Event {
    protected Event() {}
    public void begin() {}
    public void end() {}
    public void commit() {}
    public final boolean isEnabled() { return false; }
    public final boolean shouldCommit() { return false; }
    public final void set(int index, Object value) {}
}
