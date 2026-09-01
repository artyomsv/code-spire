package e2e.probe;

/** E2E probe: an existing caller. Rung 2 must be able to name this file. */
public final class Caller {

    public long invoke() {
        return new Changed().total(10L);
    }
}
