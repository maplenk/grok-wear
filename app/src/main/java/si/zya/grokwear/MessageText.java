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
    /** Display text plus bold ranges (start, end pairs), so key words and numbers stay bold on the watch. */
    static final class Styled {
        final String text;final int[] bold;
        Styled(String text,int[] bold){this.text=text;this.bold=bold;}
    }
    /** Like {@link #plain}, but **bold**, __bold__ and headings become bold ranges instead of disappearing. */
    static Styled styled(String value) {
        if (value == null || value.isEmpty()) return new Styled("", new int[0]);
        String text = value.replaceAll("(?m)^\\s*```[^\\n]*\\n?", "");
        text = text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+(.+)$", "**$1**")
            .replaceAll("(?s)__(.+?)__", "**$1**")
            .replaceAll("(?s)~~(.+?)~~", "$1")
            .replaceAll("`([^`\\n]+)`", "$1")
            .replaceAll("!?\\[([^\\]\\n]+)\\]\\([^\\n)]*\\)", "$1")
            .replaceAll("(?m)^\\s*>\\s?", "")
            .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
            .replaceAll("(?<![\\w*])\\*([^*\\n]+)\\*(?!\\w)", "$1")
            .replaceAll("(?<![\\w_])_([^_\\n]+)_(?!\\w)", "$1");
        StringBuilder out = new StringBuilder();java.util.ArrayList<Integer> ranges = new java.util.ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf("**", at), close = open < 0 ? -1 : text.indexOf("**", open + 2);
            // An unmatched or empty marker stays as written, like plain().
            if (open < 0 || close < 0 || close == open + 2) { out.append(text, at, text.length()); break; }
            out.append(text, at, open);int start = out.length();out.append(text, open + 2, close);
            ranges.add(start);ranges.add(out.length());at = close + 2;
        }
        String raw = out.toString();int lead = 0, end = raw.length();
        while (lead < end && Character.isWhitespace(raw.charAt(lead))) lead++;
        while (end > lead && Character.isWhitespace(raw.charAt(end - 1))) end--;
        String trimmed = raw.substring(lead, end);java.util.ArrayList<Integer> kept = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < ranges.size(); i += 2) {
            int a = Math.max(0, ranges.get(i) - lead), b = Math.min(trimmed.length(), ranges.get(i + 1) - lead);
            if (b > a) { kept.add(a);kept.add(b); }
        }
        int[] bold = new int[kept.size()];for (int i = 0; i < bold.length; i++) bold[i] = kept.get(i);
        return new Styled(trimmed, bold);
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
