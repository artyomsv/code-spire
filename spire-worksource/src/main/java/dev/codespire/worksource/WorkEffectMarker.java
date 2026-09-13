package dev.codespire.worksource;

/** An opaque effect identity cannot inject tracker markup or forge another marker. */
public final class WorkEffectMarker {
    private WorkEffectMarker() {}
    public static String of(String effectId) {
        if (effectId == null || !effectId.matches("[A-Za-z0-9_-]{1,100}"))
            throw new WorkSourceException("Invalid tracker effect identity");
        return "<!-- work-effect:" + effectId + " -->";
    }
    public static String body(String text, String effectId) {
        if (text == null || text.isBlank()) throw new WorkSourceException("A tracker comment is required");
        return text + "\n\n" + of(effectId);
    }
}
