package com.diligentia.diff;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tabela slownikowa do porownania danych miedzy dwiema bazami.
 * Wiersze sa dopasowywane po kluczu naturalnym (nie po IDENTITY, ktore w kazdej bazie jest inne),
 * kolumny pomijane nie biora udzialu w porownaniu, a groupColumn dzieli tabele na osobne zakresy (Dict -> slownik).
 */
public final class DataTableSpec {

    public final String schema;
    public final String table;
    public final List<String> keyColumns;
    /** Nazwy kolumn pomijanych, malymi literami. */
    public final Set<String> ignoredColumns;
    /** Kolumna dzielaca tabele na zakresy; null gdy tabela to jeden zakres. */
    public final String groupColumn;
    /** Zapytanie zwracajace (wartosc grupy, opis) do podpisu zakresu; null gdy brak. */
    public final String groupLabelQuery;

    public DataTableSpec(String schema, String table, List<String> keyColumns, List<String> ignoredColumns, String groupColumn, String groupLabelQuery) {
        this.schema = schema;
        this.table = table;
        this.keyColumns = Collections.unmodifiableList(keyColumns);
        this.ignoredColumns = ignoredColumns.stream().map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.groupColumn = groupColumn;
        this.groupLabelQuery = groupLabelQuery;
    }

    /** Tabele porownywane przyciskiem "Porownaj slowniki". Nowa tabela = nowa linia tutaj. */
    public static List<DataTableSpec> defaults() {
        return Arrays.asList(
                new DataTableSpec("dbo", "Dict", Arrays.asList("DictCatId", "Value"), Arrays.asList("id"), "DictCatId", "SELECT id, [Desc] FROM dbo.DictCat"),
                new DataTableSpec("dbo", "Parameters", Arrays.asList("Name"), Collections.emptyList(), null, null),
                new DataTableSpec("dbo", "TRCORE_TruckTypes", Arrays.asList("TypeDesc"), Arrays.asList("Id"), null, null),
                new DataTableSpec("dbo", "StoreAreas", Arrays.asList("Name"), Arrays.asList("WMSObjectId"), null, null),
                new DataTableSpec("dbo", "LogicalAreas", Arrays.asList("Name"), Arrays.asList("WMSObjectID"), null, null),
                new DataTableSpec("dbo", "SUTypes", Arrays.asList("Desc"), Arrays.asList("Id"), null, null));
    }

    public String qualifiedName() { return "[" + schema + "].[" + table + "]"; }

    public boolean isIgnored(String column) { return ignoredColumns.contains(column.toLowerCase(Locale.ROOT)); }
}
