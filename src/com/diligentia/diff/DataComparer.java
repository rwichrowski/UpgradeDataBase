package com.diligentia.diff;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Porownanie danych jednej tabeli z dwoch baz: wiersze dopasowane po kluczu naturalnym, porownanie kolumna po kolumnie.
 * Tabela z kolumna grupujaca (Dict) daje osobny zakres na kazda grupe (slownik).
 */
public final class DataComparer {

    // region Pola i konstruktor

    /** Porzadek tekstow z liczbami: "2" przed "10", NULL na koncu. */
    public static final Comparator<String> NATURAL_ORDER = DataComparer::compareValues;

    private static final Pattern NUMBER = Pattern.compile("\\s*-?\\d+(\\.\\d+)?\\s*");

    private final boolean ignoreWhitespace;
    private final boolean ignoreCase;

    public DataComparer(boolean ignoreWhitespace, boolean ignoreCase) {
        this.ignoreWhitespace = ignoreWhitespace;
        this.ignoreCase = ignoreCase;
    }

    /** Jeden wiersz wyniku; left/right w kolejnosci kolumn zakresu (null gdy brak strony), differs tylko dla DIFFERENT. */
    public static final class RowDiff {
        public final ModuleComparer.Status status;
        public final String[] keyParts;
        public final String[] left;
        public final String[] right;
        public final boolean[] differs;

        RowDiff(ModuleComparer.Status status, String[] keyParts, String[] left, String[] right, boolean[] differs) {
            this.status = status;
            this.keyParts = keyParts;
            this.left = left;
            this.right = right;
            this.differs = differs;
        }

        public String[] any() { return left != null ? left : right; }

        public String displayKey() { return String.join(" | ", formatAll(keyParts)); }
    }

    /** Zakres porownania: cala tabela albo jedna grupa (slownik); note opisuje, czemu zakres jest pusty lub niepelny. */
    public static final class Scope {
        public final DataTableSpec spec;
        public final String table;
        public final String group;
        public final String caption;
        public final List<String> columns;
        /** Rodzaj kazdej kolumny, w kolejnosci columns. */
        public final List<DataSources.ColumnKind> kinds;
        public final int[] keyColumnIndexes;
        /** Indeks kolumny grupujacej w columns; -1 gdy tabela nie jest dzielona. */
        public final int groupColumnIndex;
        public final List<RowDiff> rows = new ArrayList<>();
        public String note;
        /** Tabeli nie ma w bazie po tej stronie - skrypt synchronizujacy do niej nie powstaje. */
        public boolean missingLeft;
        public boolean missingRight;

        Scope(DataTableSpec spec, String group, String caption, List<String> columns, List<DataSources.ColumnKind> kinds, int[] keyColumnIndexes, int groupColumnIndex) {
            this.spec = spec;
            this.table = spec.table;
            this.group = group;
            this.caption = caption;
            this.columns = columns;
            this.kinds = kinds;
            this.keyColumnIndexes = keyColumnIndexes;
            this.groupColumnIndex = groupColumnIndex;
        }

        public Map<ModuleComparer.Status, Integer> counts() {
            Map<ModuleComparer.Status, Integer> counts = new EnumMap<>(ModuleComparer.Status.class);
            for (RowDiff row : rows) counts.merge(row.status, 1, Integer::sum);
            return counts;
        }

        public int differenceCount() {
            return (int) rows.stream().filter(r -> r.status != ModuleComparer.Status.SAME).count();
        }

        public boolean hasDifferences() { return note != null || differenceCount() > 0; }

        /** Kolumny, w ktorych choc jeden wiersz sie rozni. */
        public boolean[] differingColumns() {
            boolean[] result = new boolean[columns.size()];
            for (RowDiff row : rows) {
                if (row.differs == null) continue;
                for (int i = 0; i < result.length; i++) result[i] |= row.differs[i];
            }
            return result;
        }

        public boolean isKeyColumn(int column) {
            for (int key : keyColumnIndexes) if (key == column) return true;
            return false;
        }
    }

    // endregion

    // region Porownanie

    /** Porownuje tabele z obu baz; kolumny tylko z jednej strony i duplikaty klucza trafiaja do ostrzezen. */
    public List<Scope> compare(DataSources.TableData left, DataSources.TableData right, List<String> warnings) {
        DataTableSpec spec = left.spec;
        String table = spec.table;

        if (!left.exists && !right.exists) return singleNote(spec, "brak tabeli po obu stronach");

        List<String> columns = commonColumns(left, right, table, warnings);
        int[] leftIndexes = projection(columns, left.columns);
        int[] rightIndexes = projection(columns, right.columns);
        List<DataSources.ColumnKind> kinds = columnKinds(columns, left.exists ? left : right);

        int[] keyIndexes = new int[spec.keyColumns.size()];
        for (int i = 0; i < keyIndexes.length; i++) {
            keyIndexes[i] = indexOfIgnoreCase(columns, spec.keyColumns.get(i));
            if (keyIndexes[i] < 0) return singleNote(spec, "brak kolumny klucza " + spec.keyColumns.get(i));
        }
        int groupIndex = spec.groupColumn == null ? -1 : indexOfIgnoreCase(columns, spec.groupColumn);

        Map<String, String[]> leftRows = indexRows(left, leftIndexes, keyIndexes, "lewa", warnings);
        Map<String, String[]> rightRows = indexRows(right, rightIndexes, keyIndexes, "prawa", warnings);

        TreeMap<String[], String> orderedKeys = new TreeMap<>(DataComparer::compareKeys);
        for (Map.Entry<String, String[]> e : leftRows.entrySet()) orderedKeys.put(keyParts(e.getValue(), keyIndexes), e.getKey());
        for (Map.Entry<String, String[]> e : rightRows.entrySet()) orderedKeys.putIfAbsent(keyParts(e.getValue(), keyIndexes), e.getKey());

        Map<String, Scope> scopes = new TreeMap<>(NATURAL_ORDER);
        Scope single = groupIndex < 0 ? new Scope(spec, null, table, columns, kinds, keyIndexes, -1) : null;

        for (Map.Entry<String[], String> entry : orderedKeys.entrySet()) {
            String[] leftValues = leftRows.get(entry.getValue());
            String[] rightValues = rightRows.get(entry.getValue());
            RowDiff row = compareRow(entry.getKey(), leftValues, rightValues);

            Scope scope = single;
            if (scope == null) {
                String group = row.any()[groupIndex];
                scope = scopes.computeIfAbsent(group == null ? "NULL" : group, g -> new Scope(spec, g, groupCaption(table, g, left, right), columns, kinds, keyIndexes, groupIndex));
            }
            scope.rows.add(row);
        }

        List<Scope> result = new ArrayList<>();
        if (single != null) result.add(single);
        result.addAll(scopes.values());

        String missingNote = !left.exists ? "brak tabeli po lewej" : !right.exists ? "brak tabeli po prawej" : null;
        if (result.isEmpty()) result.add(new Scope(spec, null, table, columns, kinds, keyIndexes, -1));
        for (Scope scope : result) {
            scope.missingLeft = !left.exists;
            scope.missingRight = !right.exists;
            if (missingNote != null) scope.note = missingNote;
        }
        return result;
    }

    private RowDiff compareRow(String[] keyParts, String[] left, String[] right) {
        if (left == null) return new RowDiff(ModuleComparer.Status.ONLY_RIGHT, keyParts, null, right, null);
        if (right == null) return new RowDiff(ModuleComparer.Status.ONLY_LEFT, keyParts, left, null, null);

        boolean[] differs = new boolean[left.length];
        boolean any = false;
        for (int i = 0; i < left.length; i++) {
            differs[i] = !normalize(left[i]).equals(normalize(right[i]));
            any |= differs[i];
        }
        return new RowDiff(any ? ModuleComparer.Status.DIFFERENT : ModuleComparer.Status.SAME, keyParts, left, right, differs);
    }

    /** NULL rozni sie od pustego tekstu; opcje ignorowania dotycza tylko tekstu. */
    private String normalize(String value) {
        if (value == null) return "\u0000NULL";
        String result = value;
        if (ignoreWhitespace) result = result.trim().replaceAll("\\s+", " ");
        if (ignoreCase) result = result.toLowerCase(Locale.ROOT);
        return result;
    }

    // endregion

    // region Kolumny i klucze

    /** Kolumny obecne po obu stronach (kolejnosc z lewej); gdy tabela jest tylko po jednej stronie - jej kolumny. */
    private static List<String> commonColumns(DataSources.TableData left, DataSources.TableData right, String table, List<String> warnings) {
        if (!left.exists) return new ArrayList<>(right.columns);
        if (!right.exists) return new ArrayList<>(left.columns);

        List<String> common = new ArrayList<>();
        for (String column : left.columns) {
            if (indexOfIgnoreCase(right.columns, column) >= 0) common.add(column);
            else warnings.add(table + ": kolumna " + column + " tylko po lewej - pominieta w porownaniu");
        }
        for (String column : right.columns) {
            if (indexOfIgnoreCase(left.columns, column) < 0) warnings.add(table + ": kolumna " + column + " tylko po prawej - pominieta w porownaniu");
        }
        return common;
    }

    private static List<DataSources.ColumnKind> columnKinds(List<String> columns, DataSources.TableData source) {
        List<DataSources.ColumnKind> kinds = new ArrayList<>();
        for (String column : columns) {
            int index = indexOfIgnoreCase(source.columns, column);
            kinds.add(index < 0 || index >= source.kinds.size() ? DataSources.ColumnKind.TEXT : source.kinds.get(index));
        }
        return kinds;
    }

    private static int[] projection(List<String> columns, List<String> sourceColumns) {
        int[] indexes = new int[columns.size()];
        for (int i = 0; i < indexes.length; i++) indexes[i] = indexOfIgnoreCase(sourceColumns, columns.get(i));
        return indexes;
    }

    /** Wiersze po kluczu dopasowania (bez wielkosci liter i spacji na koncach - jak kolacja CI w SQL Server). */
    private static Map<String, String[]> indexRows(DataSources.TableData data, int[] projection, int[] keyIndexes, String side, List<String> warnings) {
        Map<String, String[]> rows = new LinkedHashMap<>();
        if (!data.exists) return rows;

        for (String[] source : data.rows) {
            String[] values = new String[projection.length];
            for (int i = 0; i < projection.length; i++) values[i] = projection[i] < 0 ? null : source[projection[i]];

            String matchKey = matchKey(keyParts(values, keyIndexes));
            if (rows.put(matchKey, values) != null) warnings.add(data.spec.table + " (" + side + "): powtorzony klucz " + String.join(" | ", formatAll(keyParts(values, keyIndexes))) + " - porownany ostatni wiersz");
        }
        return rows;
    }

    private static String[] keyParts(String[] values, int[] keyIndexes) {
        String[] parts = new String[keyIndexes.length];
        for (int i = 0; i < parts.length; i++) parts[i] = values[keyIndexes[i]];
        return parts;
    }

    private static String matchKey(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) sb.append(part == null ? "\u0000" : part.trim().toLowerCase(Locale.ROOT)).append('\u0001');
        return sb.toString();
    }

    /** Ten sam klucz dopasowania = ten sam wiersz (0), inaczej porzadek naturalny czesci klucza. */
    private static int compareKeys(String[] a, String[] b) {
        if (matchKey(a).equals(matchKey(b))) return 0;
        for (int i = 0; i < a.length; i++) {
            int c = compareValues(a[i], b[i]);
            if (c != 0) return c;
        }
        return matchKey(a).compareTo(matchKey(b));
    }

    // endregion

    // region Pomocnicze

    /** Porownanie wartosci: liczby liczbowo, tekst bez wielkosci liter, NULL na koncu. */
    public static int compareValues(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1;
        if (NUMBER.matcher(a).matches() && NUMBER.matcher(b).matches()) {
            int c = new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim()));
            if (c != 0) return c;
        }
        int c = a.compareToIgnoreCase(b);
        return c != 0 ? c : a.compareTo(b);
    }

    public static String format(String value) { return value == null ? "NULL" : value; }

    private static List<String> formatAll(String[] values) {
        List<String> result = new ArrayList<>();
        for (String value : values) result.add(format(value));
        return result;
    }

    private static String groupCaption(String table, String group, DataSources.TableData left, DataSources.TableData right) {
        String label = left.groupLabels.get(group);
        if (label == null) label = right.groupLabels.get(group);
        return label == null ? table + " " + group : table + " " + group + " – " + label;
    }

    /** Zakres bez wierszy (brak tabeli po obu stronach, brak kolumny klucza) - nie da sie dla niego wygenerowac skryptu. */
    private static List<Scope> singleNote(DataTableSpec spec, String note) {
        Scope scope = new Scope(spec, null, spec.table, new ArrayList<>(), new ArrayList<>(), new int[0], -1);
        scope.note = note;
        scope.missingLeft = true;
        scope.missingRight = true;
        List<Scope> result = new ArrayList<>();
        result.add(scope);
        return result;
    }

    private static int indexOfIgnoreCase(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).equalsIgnoreCase(value)) return i;
        return -1;
    }

    // endregion
}
