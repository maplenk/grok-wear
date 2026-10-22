package si.zya.grokwear;

/** Small, non-HTML display normalizer. Stored and model-facing messages are never changed. */
final class MessageText {
    private MessageText() {}
    static String plain(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = value.replaceAll("(?m)^\\s*```[^\\n]*\\n?", "");
        text = text.replaceAll("(?s)\\*\\*(.+?)\\*\\*", "$1")
            .replaceAll("(?s)__(.+?)__", "$1")
            .replaceAll("(?s)~~(.+?)~~", "$1")
            .replaceAll("`([^`\\n]+)`", "$1")
            .replaceAll("!?\\[([^\\]\\n]+)\\]\\([^\\n)]*\\)", "$1")
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
            .replaceAll("(?m)^\\s*>\\s?", "")
            .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
            .replaceAll("(?<![\\w*])\\*([^*\\n]+)\\*(?!\\w)", "$1")
            .replaceAll("(?<![\\w_])_([^_\\n]+)_(?!\\w)", "$1");
        return text.trim();
    }
    /** The sentence containing the given fraction of the text (0..1), for captions that follow speech. */
    static String sentenceAt(String value, double fraction) {
        if (value == null || value.isEmpty()) return "";
        int at = (int) Math.round(Math.max(0, Math.min(1, fraction)) * value.length());
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean boundary = (c == '.' || c == '!' || c == '?' || c == '\n') && (i + 1 == value.length() || Character.isWhitespace(value.charAt(i + 1)));
            if (!boundary) continue;
            int end = i + 1;
            if (at < end || end == value.length()) return value.substring(start, end).trim();
            start = end;
        }
        return value.substring(start).trim();
    }
}
