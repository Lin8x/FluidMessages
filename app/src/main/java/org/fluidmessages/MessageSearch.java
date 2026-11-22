package org.fluidmessages;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class MessageSearch {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");
    private final String[] terms;
    private final String number;

    MessageSearch(String query) {
        var text = normalize(query);
        terms = text.isEmpty() ? new String[0] : text.split(" ");
        number = query.matches("[+0-9(). -]+") ? digits(query) : "";
    }

    boolean empty() { return terms.length == 0; }

    boolean matches(String... values) {
        if (empty()) return true;
        if (!number.isEmpty()) {
            for (String value : values) {
                if (value != null && value.matches("[+0-9(). -]+") && digits(value).contains(number)) return true;
            }
        }
        var text = normalize(String.join(" ", values));
        String[] words = null;
        for (String term : terms) {
            if (text.contains(term)) continue;
            if (term.length() < 4 || term.length() > 24 || term.chars().anyMatch(Character::isDigit)) return false;
            if (words == null) words = text.split(" ");
            boolean matched = false;
            for (String word : words) {
                if (close(term, word)) { matched = true; break; }
                if (word.length() > term.length() && close(term, word.substring(0, term.length()))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    static String digits(String s) { return NON_DIGITS.matcher(s).replaceAll(""); }

    private static String normalize(String value) {
        String plain = MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
        return SEPARATORS.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static boolean close(String a, String b) {
        if (a.length() > b.length()) return close(b, a);
        int difference = b.length() - a.length();
        if (difference > 1) return false;
        int index = 0;
        while (index < a.length() && a.charAt(index) == b.charAt(index)) index++;
        if (difference == 1) return a.regionMatches(index, b, index + 1, a.length() - index);
        if (index == a.length()) return true;
        if (a.regionMatches(index + 1, b, index + 1, a.length() - index - 1)) return true;
        return index + 1 < a.length() && a.charAt(index) == b.charAt(index + 1)
            && a.charAt(index + 1) == b.charAt(index)
            && a.regionMatches(index + 2, b, index + 2, a.length() - index - 2);
    }
}
