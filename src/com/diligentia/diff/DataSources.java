package com.diligentia.diff;

import com.diligentia.SqlExecutor;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Odczyt danych tabel slownikowych z bazy (SELECT * na tabele) do postaci tekstowej gotowej do porownania.
 */
public final class DataSources {

    // region Pola i konstruktor

    private static final String TABLE_EXISTS_QUERY = "SELECT OBJECT_ID(?, 'U')";

    private DataSources() { }

    /** Rodzaj kolumny - decyduje o zapisie wartosci w skrypcie synchronizujacym. */
    public enum ColumnKind { NUMBER, TEXT, DATE, BINARY }

    /** Dane jednej tabeli z jednej bazy; wartosci wierszy w kolejnosci columns (bez kolumn pomijanych). */
    public static final class TableData {
        public final DataTableSpec spec;
        public final boolean exists;
        public final List<String> columns;
        /** Rodzaj kazdej kolumny, w kolejnosci columns. */
        public final List<ColumnKind> kinds;
        public final List<String[]> rows;
        /** Opis grupy (np. nazwa slownika z DictCat) po wartosci kolumny grupujacej. */
        public final Map<String, String> groupLabels;

        TableData(DataTableSpec spec, boolean exists, List<String> columns, List<ColumnKind> kinds, List<String[]> rows, Map<String, String> groupLabels) {
            this.spec = spec;
            this.exists = exists;
            this.columns = columns;
            this.kinds = kinds;
            this.rows = rows;
            this.groupLabels = groupLabels;
        }

        static TableData missing(DataTableSpec spec) {
            return new TableData(spec, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        }
    }

    // endregion

    // region Baza danych

    /** Jedno polaczenie na baze, po jednym SELECT * na kazda tabele; brak tabeli to nie blad, tylko TableData.missing. */
    public static List<TableData> fromDatabase(SqlExecutor.DbTarget target, List<DataTableSpec> specs, List<String> warnings) throws SQLException {
        List<TableData> tables = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(target.getUrl(), target.getUser(), target.getPassword())) {
            for (DataTableSpec spec : specs) {
                if (!tableExists(connection, spec)) {
                    warnings.add(target.getEnv() + ": brak tabeli " + spec.qualifiedName());
                    tables.add(TableData.missing(spec));
                    continue;
                }
                tables.add(readTable(connection, spec, target.getEnv(), warnings));
            }
        }
        return tables;
    }

    private static boolean tableExists(Connection connection, DataTableSpec spec) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(TABLE_EXISTS_QUERY)) {
            stmt.setString(1, spec.qualifiedName());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getObject(1) != null;
            }
        }
    }

    private static TableData readTable(Connection connection, DataTableSpec spec, String env, List<String> warnings) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<ColumnKind> kinds = new ArrayList<>();
        List<Integer> columnIndexes = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();

        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM " + spec.qualifiedName())) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String name = meta.getColumnName(i);
                if (spec.isIgnored(name) || isGeneratedColumn(meta, i, spec, name)) continue;
                columns.add(name);
                kinds.add(kindOf(meta.getColumnType(i)));
                columnIndexes.add(i);
            }

            while (rs.next()) {
                String[] values = new String[columnIndexes.size()];
                for (int i = 0; i < values.length; i++) values[i] = formatValue(rs.getObject(columnIndexes.get(i)));
                rows.add(values);
            }
        }

        return new TableData(spec, true, columns, kinds, rows, readGroupLabels(connection, spec, env, warnings));
    }

    /** IDENTITY spoza klucza jest w kazdej bazie inne - pomijane tak jak kolumny z listy ignorowanych. */
    private static boolean isGeneratedColumn(ResultSetMetaData meta, int index, DataTableSpec spec, String name) throws SQLException {
        return meta.isAutoIncrement(index) && spec.keyColumns.stream().noneMatch(k -> k.equalsIgnoreCase(name));
    }

    private static ColumnKind kindOf(int sqlType) {
        switch (sqlType) {
            case Types.BIT:
            case Types.BOOLEAN:
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.DECIMAL:
            case Types.NUMERIC:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
                return ColumnKind.NUMBER;
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case microsoft.sql.Types.DATETIMEOFFSET:
                return ColumnKind.DATE;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return ColumnKind.BINARY;
            default:
                return ColumnKind.TEXT;
        }
    }

    private static Map<String, String> readGroupLabels(Connection connection, DataTableSpec spec, String env, List<String> warnings) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (spec.groupLabelQuery == null) return labels;

        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(spec.groupLabelQuery)) {
            while (rs.next()) labels.put(formatValue(rs.getObject(1)), formatValue(rs.getObject(2)));
        } catch (SQLException e) {
            warnings.add(env + ": nie udalo sie odczytac opisow grup " + spec.table + ": " + e.getMessage());
        }
        return labels;
    }

    // endregion

    // region Pomocnicze

    /** Wartosc kolumny jako tekst; null zostaje null, dane binarne jako dlugosc i skrot. */
    static String formatValue(Object value) {
        if (value == null) return null;
        if (value instanceof byte[]) return describeBinary((byte[]) value);
        if (value instanceof Boolean) return (Boolean) value ? "1" : "0";
        if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();
        return value.toString();
    }

    private static String describeBinary(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", digest[i]));
            return "<binarne " + bytes.length + " B, sha1 " + hex + ">";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Brak SHA-1 w JVM", e);
        }
    }

    // endregion
}
