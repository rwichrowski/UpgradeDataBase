package com.diligentia.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Porownanie dwoch zestawow obiektow po znormalizowanej tresci.
 * Normalizacja usuwa szum niebedacy roznica: tekst przed naglowkiem, CREATE/ALTER/CREATE OR ALTER,
 * nawiasy w nazwie, konce linii i spacje na koncu linii.
 */
public final class ModuleComparer {

    // region Pola i konstruktor

    public enum Status {
        DIFFERENT("ROZNI SIE"),
        ONLY_LEFT("TYLKO LEWA"),
        ONLY_RIGHT("TYLKO PRAWA"),
        ENCRYPTED("SZYFROWANY"),
        SAME("IDENTYCZNE");

        private final String label;

        Status(String label) { this.label = label; }

        public String getLabel() { return label; }
    }

    /** Wynik porownania jednego obiektu; leftText/rightText to tresc po normalizacji (null gdy brak strony). */
    public static final class Entry {
        public final String key;
        public final Status status;
        public final SqlModule left;
        public final SqlModule right;
        public final String leftText;
        public final String rightText;

        Entry(String key, Status status, SqlModule left, SqlModule right, String leftText, String rightText) {
            this.key = key;
            this.status = status;
            this.left = left;
            this.right = right;
            this.leftText = leftText;
            this.rightText = rightText;
        }

        public SqlModule any() { return left != null ? left : right; }
    }

    private final boolean ignoreWhitespace;
    private final boolean ignoreCase;

    public ModuleComparer(boolean ignoreWhitespace, boolean ignoreCase) {
        this.ignoreWhitespace = ignoreWhitespace;
        this.ignoreCase = ignoreCase;
    }

    // endregion

    // region Porownanie

    public List<Entry> compare(List<SqlModule> leftModules, List<SqlModule> rightModules, List<String> warnings) {
        Map<String, SqlModule> left = index(leftModules, "lewej", warnings);
        Map<String, SqlModule> right = index(rightModules, "prawej", warnings);

        TreeSet<String> keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());

        List<Entry> entries = new ArrayList<>();
        for (String key : keys) {
            SqlModule l = left.get(key);
            SqlModule r = right.get(key);
            String lText = l == null || l.isEncrypted() ? null : normalize(l);
            String rText = r == null || r.isEncrypted() ? null : normalize(r);

            Status status;
            if (r == null) status = Status.ONLY_LEFT;
            else if (l == null) status = Status.ONLY_RIGHT;
            else if (l.isEncrypted() || r.isEncrypted()) status = Status.ENCRYPTED;
            else if (l.getType().equals(r.getType()) && comparisonForm(body(lText)).equals(comparisonForm(body(rText)))) status = Status.SAME;
            else status = Status.DIFFERENT;

            entries.add(new Entry(key, status, l, r, lText, rText));
        }

        entries.sort(Comparator.comparing((Entry e) -> e.status).thenComparing(e -> e.key));
        return entries;
    }

    // endregion

    // region Normalizacja

    /** Tresc do porownania i do WinMerge: naglowek "CREATE TYP [schemat].[nazwa]" + reszta bez szumu. */
    public static String normalize(SqlModule module) {
        String text = module.getDefinition();
        SqlTextParser.Header header = SqlTextParser.findHeader(text);
        if (header != null) {
            text = "CREATE " + module.getType() + " [" + module.getSchema() + "].[" + module.getName() + "]" + text.substring(header.end);
        }

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(stripTrailing(line)).append('\n');
        }
        return sb.toString().strip() + "\n";
    }

    // endregion

    // region Pomocnicze

    /** Tresc bez pierwszej linii naglowka - nazwa obiektu jest juz porownana kluczem (bez wielkosci liter). */
    private static String body(String normalized) {
        SqlTextParser.Header header = SqlTextParser.findHeader(normalized);
        return header == null ? normalized : normalized.substring(header.end);
    }

    private String comparisonForm(String text) {
        String result = text;
        if (ignoreWhitespace) result = result.replaceAll("\\s+", " ").trim();
        if (ignoreCase) result = result.toLowerCase(Locale.ROOT);
        return result;
    }

    /** Indeks po schemat.nazwa; przy duplikatach wygrywa ostatni (np. kolejne ALTER w pliku zmian). */
    private static Map<String, SqlModule> index(List<SqlModule> modules, String sideName, List<String> warnings) {
        Map<String, SqlModule> map = new LinkedHashMap<>();
        for (SqlModule module : modules) {
            SqlModule previous = map.put(module.getKey(), module);
            if (previous != null) {
                warnings.add("Duplikat po " + sideName + " stronie: " + module.getDisplayName() + " w " + previous.getOrigin() + " i " + module.getOrigin() + " (biore ostatni)");
            }
        }
        return map;
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    // endregion
}
