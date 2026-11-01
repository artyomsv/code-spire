package dev.codespire.runworker;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The agent's artifact as the publisher reported it: one {@code artifact} line, or ordered {@code artifact_part} lines
 * (harness reviews, step 4), checked against the unit's cap in UTF-8 bytes.
 *
 * <p>Parts exist because one outcome line is capped at 64 KiB and the runtime clips a longer one, so a 256 KiB review
 * answer cannot travel whole. Every part names its index and the count. A part with a bad index or count, or one sent
 * twice, breaks the text: joining around it would hand on an answer with a hole or a replaced finding that looks
 * whole. A total over the cap is over cap, never a shorter text — the paid run comes back marked rather than lost.
 */
final class HandoffArtifact {

    /** Far above any real artifact (256 KiB in parts of several KiB); bounds what a hostile count can make us hold. */
    static final int MAX_PARTS = 1024;

    static final String TOO_LARGE = "TOO_LARGE";

    static final String PART_INVALID = "PART_INVALID";

    static final String PART_DUPLICATE = "PART_DUPLICATE";

    static final String PART_MISSING = "PART_MISSING";

    private final int maxBytes;

    /** Records a refusal of the artifact file by its reason. */
    private final Consumer<String> refuse;

    private String single;

    private final TreeMap<Integer, String> parts = new TreeMap<>();

    private int count = -1;

    private long bytes;

    private boolean broken;

    private boolean overCap;

    HandoffArtifact(int maxBytes, Consumer<String> refuse) {
        this.maxBytes = maxBytes;
        this.refuse = refuse;
    }

    /** One {@code artifact} line: the last one wins, and a text over the cap is over cap, never truncated. */
    void single(JsonNode node) {
        String text = node.path("text").asText(null);
        if (text == null) {
            return;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            markOverCap();
            refuse.accept(TOO_LARGE);
            return;
        }
        single = text;
    }

    /** One {@code artifact_part} line. */
    void part(JsonNode node) {
        if (broken || overCap) {
            return;
        }
        int index = node.path("index").asInt(-1);
        int of = node.path("count").asInt(-1);
        String text = node.path("text").asText(null);
        if (text == null || of < 1 || of > MAX_PARTS || index < 0 || index >= of || count != -1 && of != count) {
            breakWith(PART_INVALID);
            return;
        }
        if (parts.containsKey(index)) {
            breakWith(PART_DUPLICATE);
            return;
        }
        count = of;
        bytes += text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            markOverCap();
            refuse.accept(TOO_LARGE);
            return;
        }
        parts.put(index, text);
    }

    /** The publisher refused the file as too large, or the parts' total passed the cap: the same fact. */
    void markOverCap() {
        overCap = true;
        parts.clear();
        single = null;
    }

    boolean overCap() {
        return overCap;
    }

    /** The whole text: every part present and joined by index, or the single line; empty otherwise. */
    Optional<String> text() {
        if (overCap) {
            return Optional.empty();
        }
        if (count == -1 && !broken) {
            return Optional.ofNullable(single);
        }
        if (broken || parts.size() != count) {
            return Optional.empty();
        }
        return Optional.of(String.join("", parts.values()));
    }

    /** Why parts that began arriving never made a text, when nothing else already said so. */
    Optional<String> missing() {
        boolean incomplete = count != -1 && !broken && !overCap && parts.size() != count;
        return incomplete ? Optional.of(PART_MISSING) : Optional.empty();
    }

    private void breakWith(String why) {
        broken = true;
        parts.clear();
        refuse.accept(why);
    }
}
