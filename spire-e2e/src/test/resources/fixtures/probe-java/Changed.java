package e2e.probe;

import e2e.probe.pricing.Pricer;

/** E2E probe: the file the merge request changes. Its import is what rung 1 resolves. */
public final class Changed {

    public long total(long tokens) {
        return Pricer.chargeFor(tokens);
    }
}
