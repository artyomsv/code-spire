package dev.codespire.publisher;

/** The one string escape the publisher commands' single-line JSON needs. */
final class Json {

    private Json() {
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A positive integer flag value; anything else is a bad argument. */
    static int positive(String flag, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " is not a number: " + value, e);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(flag + " must be at least 1");
        }
        return parsed;
    }
}
