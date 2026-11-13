package com.diligentia.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analiza tekstu T-SQL bez polaczenia z baza: podzial na batche po GO i odnajdywanie naglowka CREATE/ALTER.
 * Komentarze i literaly tekstowe sa maskowane, wiec CREATE wewnatrz nich nie jest brany pod uwage.
 */
public final class SqlTextParser {

    // region Pola i konstruktor

    private static final String IDENT = "(?:\\[(?:[^\\]]|\\]\\])+\\]|\"[^\"]+\"|[\\p{L}\\p{N}_@#$]+)";

    private static final Pattern HEADER = Pattern.compile("(?i)\\b(CREATE\\s+OR\\s+ALTER|CREATE|ALTER)\\s+(PROCEDURE|PROC|FUNCTION|TRIGGER|VIEW)\\s+(?:(" + IDENT + ")\\s*\\.\\s*)?(" + IDENT + ")");

    private static final Pattern GO_LINE = Pattern.compile("(?im)^[ \\t]*GO(?:[ \\t]+\\d+)?[ \\t]*;?[ \\t]*$");

    private SqlTextParser() { }

    /** Naglowek obiektu: pozycja w tekscie, typ i nazwa. */
    public static final class Header {
        public final int start;
        public final int end;
        public final String type;
        public final String schema;
        public final String name;

        Header(int start, int end, String type, String schema, String name) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.schema = schema;
            this.name = name;
        }
    }

    // endregion

    // region Analiza

    /** Pierwszy naglowek CREATE/ALTER [OR ALTER] PROC|FUNCTION|TRIGGER|VIEW poza komentarzami i literalami; null gdy brak. */
    public static Header findHeader(String sql) {
        Matcher m = HEADER.matcher(mask(sql));
        if (!m.find()) return null;

        String keyword = m.group(2).toUpperCase(Locale.ROOT);
        String type = keyword.startsWith("PROC") ? "PROCEDURE" : keyword;
        String schema = m.group(3) == null ? "dbo" : unquote(m.group(3));
        return new Header(m.start(), m.end(), type, schema, unquote(m.group(4)));
    }

    /** Dzieli skrypt na batche po liniach GO (GO w komentarzu lub literale nie dzieli). */
    public static List<String> splitBatches(String sql) {
        List<String> batches = new ArrayList<>();
        Matcher m = GO_LINE.matcher(mask(sql));
        int from = 0;
        while (m.find()) {
            batches.add(sql.substring(from, m.start()));
            from = m.end();
        }
        batches.add(sql.substring(from));
        return batches;
    }

    /** Zamienia tresc komentarzy i literalow '...' na spacje, zachowujac dlugosc i znaki konca linii. */
    public static String mask(String sql) {
        char[] out = sql.toCharArray();
        int n = out.length;
        int i = 0;

        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            if (c == '-' && next == '-') {
                while (i < n && sql.charAt(i) != '\n') blank(out, i++);
            } else if (c == '/' && next == '*') {
                int depth = 0;
                while (i < n) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        blank(out, i++);
                        blank(out, i++);
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        blank(out, i++);
                        blank(out, i++);
                        if (depth == 0) break;
                    } else {
                        blank(out, i++);
                    }
                }
            } else if (c == '\'') {
                blank(out, i++);
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            blank(out, i++);
                            blank(out, i++);
                            continue;
                        }
                        blank(out, i++);
                        break;
                    }
                    blank(out, i++);
                }
            } else if (c == '[') {
                i = skipQuoted(sql, i, ']');
            } else if (c == '"') {
                i = skipQuoted(sql, i, '"');
            } else {
                i++;
            }
        }
        return new String(out);
    }

    // endregion

    // region Pomocnicze

    static String unquote(String identifier) {
        if (identifier.startsWith("[") && identifier.endsWith("]")) return identifier.substring(1, identifier.length() - 1).replace("]]", "]");
        if (identifier.startsWith("\"") && identifier.endsWith("\"")) return identifier.substring(1, identifier.length() - 1);
        return identifier;
    }

    private static int skipQuoted(String sql, int start, char close) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == close) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static void blank(char[] out, int i) {
        if (out[i] != '\n' && out[i] != '\r') out[i] = ' ';
    }

    // endregion
}
