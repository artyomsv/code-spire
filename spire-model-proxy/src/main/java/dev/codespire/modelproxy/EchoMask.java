package dev.codespire.modelproxy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Takes the credential out of a body as it streams to the agent: every occurrence of the secret, and of a seat's
 * account id, becomes {@link #WITHHELD}, however the vendor's chunks split it.
 *
 * <p>Bytes are passed on as soon as they cannot begin an echo. Only a chunk's tail that is the start of one is held,
 * at most the longest value's length less one, until the next chunk says what it was; a stream that pauses mid-sentence
 * still reaches the agent up to the pause.
 *
 * <p>It works on bytes and matches the values exactly as the vendor received them. Neither a key, a seat's token (a
 * JWT) nor an account id holds a character JSON would escape, so an echo inside a JSON string is the same bytes. A
 * fragment of a value (a key's last four characters, as OpenAI quotes a refused one) is not one; the 401 and 403
 * bodies that carry such a fragment are replaced whole ({@link ResponseRelay}).
 *
 * <p>This is not {@code spire-secrets}' scrubber, and is not meant to be: that one cleans finished text for a log,
 * this one cleans a live byte stream across chunk boundaries for a network peer, in a module with no dependency.
 */
final class EchoMask {

    /** What the agent reads where the credential was. */
    static final String WITHHELD = "spire-model-withheld";

    private static final byte[] WITHHELD_BYTES = WITHHELD.getBytes(StandardCharsets.UTF_8);

    private final List<byte[]> values;
    private byte[] held = new byte[0];

    EchoMask(List<byte[]> values) {
        this.values = List.copyOf(values);
        if (this.values.stream().anyMatch(value -> value.length == 0)) {
            throw new IllegalArgumentException("an empty value would match everywhere");
        }
    }

    /** What of {@code chunk[0, length)}, after what was held, may go to the agent now. */
    byte[] next(byte[] chunk, int length) {
        byte[] pending = Arrays.copyOf(held, held.length + length);
        System.arraycopy(chunk, 0, pending, held.length, length);
        return mask(pending, at -> beginsAnEcho(pending, at));
    }

    /** The end of the body: what was held goes too, an echo in it masked. */
    byte[] finish() {
        return mask(held, at -> false);
    }

    /**
     * Masks {@code pending} from its start, stopping where {@code holdFrom} says the rest may still become an echo;
     * what is left is held for the next call.
     */
    private byte[] mask(byte[] pending, IntPredicate holdFrom) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pending.length);
        int at = 0;
        while (at < pending.length) {
            int echo = echoAt(pending, at);
            if (echo > 0) {
                out.writeBytes(WITHHELD_BYTES);
                at += echo;
            } else if (holdFrom.test(at)) {
                break;
            } else {
                out.write(pending[at++]);
            }
        }
        held = Arrays.copyOfRange(pending, at, pending.length);
        return out.toByteArray();
    }

    /** The length of the value that occurs whole at {@code at}, or 0. */
    private int echoAt(byte[] bytes, int at) {
        for (byte[] value : values) {
            int end = at + value.length;
            if (end <= bytes.length && Arrays.equals(bytes, at, end, value, 0, value.length)) {
                return value.length;
            }
        }
        return 0;
    }

    /** Whether everything from {@code at} to the end is the start of a value, which the next chunk may complete. */
    private boolean beginsAnEcho(byte[] bytes, int at) {
        int remaining = bytes.length - at;
        for (byte[] value : values) {
            if (remaining < value.length && Arrays.equals(bytes, at, bytes.length, value, 0, remaining)) {
                return true;
            }
        }
        return false;
    }
}
