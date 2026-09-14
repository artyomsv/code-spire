package dev.codespire.worksource;

import java.util.List;
import java.util.Map;

/** A bound adapter verifies its provider signature before returning normalized control facts. */
public interface WorkSourceIngress {
    List<WorkSourceSignal> translate(Map<String, String> headers, byte[] body, String configuredOrigin);
}
