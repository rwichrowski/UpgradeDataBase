package com.diligentia.diff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skrypt T-SQL wyrownujacy dane slownikowe bazy docelowej do bazy zrodlowej dla zaznaczonych wierszy porownania.
 * Skrypt tylko powstaje jako plik - nic nie jest wykonywane. Konczy sie ROLLBACK, COMMIT robi uzytkownik po sprawdzeniu.
 */
public final class DataSyncScript {

    // region Pola i konstruktor

    private static final Pattern DATE_TIME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})(\\.\\d+)?");
    private static final String NL = "\r\n";

    private final DataDiffService.Result result;
    /** true: zrodlo lewa, cel prawa; false: odwrotnie. */
    private final boolean toRight;

    private int inserts;
    private int updates;
    private int deletes;
    private final List<String> skipped = new ArrayList<>();

    public DataSyncScript(DataDiffService.Result result, boolean toRight) {
        this.result = result;
        this.toRight = toRight;
    }

    // endregion

    // region Generowanie

    /** Tresc skryptu dla zaznaczonych wierszy; w obrebie tabeli kolejnosc DELETE, UPDATE, INSERT (zwalnia unikalne nazwy przed wstawieniem). */
    public String build(Collection<DataComparer.RowDiff> selected) {
        Map<String, List<String>> deletesByTable = new LinkedHashMap<>();
        Map<String, List<String>> updatesByTable = new LinkedHashMap<>();
        Map<String, List<String>> insertsByTable = new LinkedHashMap<>();

        for (DataComparer.Scope scope : result.scopes) {
            for (DataComparer.RowDiff row : scope.rows) {
                if (row.status == ModuleComparer.Status.SAME || !selected.contains(row)) continue;
                if (toRight ? scope.missingRight : scope.missingLeft) {
                    skipped.add(scope.caption + " | " + row.displayKey() + " – brak tabeli w bazie docelowej");
                    continue;
                }
                addStatement(scope, row, deletesByTable, updatesByTable, insertsByTable);
            }
        }

        StringBuilder body = new StringBuilder();
        for (DataComparer.Scope scope : result.scopes) {
            String table = scope.spec.qualifiedName();
            List<String> statements = new ArrayList<>();
            statements.addAll(deletesByTable.getOrDefault(table, new ArrayList<>()));
            statements.addAll(updatesByTable.getOrDefault(table, new ArrayList<>()));
            statements.addAll(insertsByTable.getOrDefault(table, new ArrayList<>()));
            if (statements.isEmpty()) continue;

            body.append(NL).append("-- ===== ").append(table).append(" =====").append(NL);
            statements.forEach(s -> body.append(s).append(NL));
            deletesByTable.remove(table);
            updatesByTable.remove(table);
            insertsByTable.remove(table);
        }

        return header() + body + footer();
    }

    private void addStatement(DataComparer.Scope scope, DataComparer.RowDiff row, Map<String, List<String>> deletesByTable, Map<String, List<String>> updatesByTable, Map<String, List<String>> insertsByTable) {
        String[] source = toRight ? row.left : row.right;
        String[] target = toRight ? row.right : row.left;
        String table = scope.spec.qualifiedName();
        String comment = "-- " + singleLine(scope.caption + " | " + row.displayKey()) + NL;

        if (source != null && target != null) {
            String update = updateStatement(scope, row, source, target);
            if (update != null) {
                updatesByTable.computeIfAbsent(table, t -> new ArrayList<>()).add(comment + update);
                updates++;
            }
        } else if (source != null) {
            insertsByTable.computeIfAbsent(table, t -> new ArrayList<>()).add(comment + insertStatement(scope, source));
            inserts++;
        } else {
            deletesByTable.computeIfAbsent(table, t -> new ArrayList<>()).add(comment + "DELETE FROM " + table + " WHERE " + keyCondition(scope, target) + ";");
            deletes++;
        }
    }

    /** UPDATE tylko zmienionych kolumn; WHERE po kluczu z bazy docelowej. Kolumny binarne nie do przeniesienia - komentarz. */
    private String updateStatement(DataComparer.Scope scope, DataComparer.RowDiff row, String[] source, String[] target) {
        List<String> assignments = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < scope.columns.size(); i++) {
            if (!row.differs[i]) continue;
            if (scope.kinds.get(i) == DataSources.ColumnKind.BINARY) {
                notes.append("-- UWAGA: kolumna binarna ").append(scope.columns.get(i)).append(" rozni sie - przenies recznie").append(NL);
                continue;
            }
            assignments.add(bracket(scope.columns.get(i)) + " = " + literal(source[i], scope.kinds.get(i)));
        }
        if (assignments.isEmpty()) {
            skipped.add(scope.caption + " | " + row.displayKey() + " – rozni sie tylko kolumna binarna");
            return null;
        }
        return notes + "UPDATE " + scope.spec.qualifiedName() + " SET " + String.join(", ", assignments) + " WHERE " + keyCondition(scope, target) + ";";
    }

    /** INSERT z warunkiem IF NOT EXISTS - skrypt mozna uruchomic ponownie. Kolumny binarne pomijane. */
    private String insertStatement(DataComparer.Scope scope, String[] source) {
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < scope.columns.size(); i++) {
            if (scope.kinds.get(i) == DataSources.ColumnKind.BINARY) {
                if (source[i] != null) notes.append("-- UWAGA: kolumna binarna ").append(scope.columns.get(i)).append(" pominieta - przenies recznie").append(NL);
                continue;
            }
            columns.add(bracket(scope.columns.get(i)));
            values.add(literal(source[i], scope.kinds.get(i)));
        }
        String table = scope.spec.qualifiedName();
        return notes + "IF NOT EXISTS (SELECT 1 FROM " + table + " WHERE " + keyCondition(scope, source) + ") INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", values) + ");";
    }

    // endregion

    // region Naglowek i zakonczenie

    private String header() {
        String source = toRight ? result.leftLabel : result.rightLabel;
        String target = toRight ? result.rightLabel : result.leftLabel;
        String targetDatabase = toRight ? result.rightDatabase : result.leftDatabase;

        StringBuilder sb = new StringBuilder();
        sb.append("-- Synchronizacja slownikow: ").append(source).append(" -> ").append(target).append(NL);
        sb.append("-- Baza docelowa: ").append(target).append(targetDatabase == null ? "" : " (" + targetDatabase + ")").append(NL);
        sb.append("-- Wygenerowano: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(" (UpgradeDataBase)").append(NL);
        sb.append("-- Instrukcje: INSERT ").append(inserts).append(", UPDATE ").append(updates).append(", DELETE ").append(deletes).append(NL);
        sb.append("--").append(NL);
        sb.append("-- Skrypt konczy sie ROLLBACK. Uruchom, sprawdz wynik, potem zamien ROLLBACK TRAN na COMMIT TRAN.").append(NL);
        sb.append("-- Kolumny z identyfikatorami innych tabel (np. LogicalAreas.StoreId, TRCORE_TruckTypes.IdDoc) sa przenoszone 1:1 -").append(NL);
        sb.append("-- sprawdz, czy te ID znacza to samo w bazie docelowej.").append(NL);
        if (!skipped.isEmpty()) {
            sb.append("--").append(NL).append("-- Pominiete (").append(skipped.size()).append("):").append(NL);
            skipped.forEach(s -> sb.append("--   ").append(singleLine(s)).append(NL));
        }
        sb.append(NL);

        if (targetDatabase != null) {
            sb.append("IF DB_NAME() <> N'").append(targetDatabase.replace("'", "''")).append("' BEGIN RAISERROR(N'Skrypt jest dla bazy ").append(targetDatabase.replace("'", "''")).append(" - przerwano.', 16, 1); SET NOEXEC ON; END").append(NL);
            sb.append("GO").append(NL);
        }
        sb.append("SET XACT_ABORT ON;").append(NL);
        sb.append("BEGIN TRAN;").append(NL);
        return sb.toString();
    }

    private static String footer() {
        return NL + "-- Po sprawdzeniu zamien na COMMIT TRAN" + NL + "ROLLBACK TRAN;" + NL + "GO" + NL + "SET NOEXEC OFF;" + NL + "GO" + NL;
    }

    // endregion

    // region Zapis

    /** Zapisuje skrypt w katalogu wyniku porownania jako sync_ZRODLO_to_CEL.sql i zwraca sciezke. */
    public Path write(String script) throws IOException {
        String source = toRight ? result.leftLabel : result.rightLabel;
        String target = toRight ? result.rightLabel : result.leftLabel;
        Path file = result.outDir().resolve(SqlModule.sanitizeFileName("sync_" + source + "_to_" + target) + ".sql");
        Files.createDirectories(file.getParent());
        SchemaDiffService.writeUtf8(file, script);
        return file;
    }

    public int statementCount() { return inserts + updates + deletes; }

    // endregion

    // region Pomocnicze

    private static String keyCondition(DataComparer.Scope scope, String[] values) {
        List<String> parts = new ArrayList<>();
        for (int index : scope.keyColumnIndexes) {
            String column = bracket(scope.columns.get(index));
            parts.add(values[index] == null ? column + " IS NULL" : column + " = " + literal(values[index], scope.kinds.get(index)));
        }
        return String.join(" AND ", parts);
    }

    /** Liczba bez cudzyslowu, data w formacie ISO (niezalezny od jezyka sesji), reszta jako N'...'. */
    static String literal(String value, DataSources.ColumnKind kind) {
        if (value == null) return "NULL";
        if (kind == DataSources.ColumnKind.NUMBER) return value;
        if (kind == DataSources.ColumnKind.DATE) {
            Matcher m = DATE_TIME.matcher(value);
            if (m.matches()) {
                String fraction = m.group(3) == null ? "" : m.group(3).length() > 4 ? m.group(3).substring(0, 4) : m.group(3);
                return "'" + m.group(1) + "T" + m.group(2) + fraction + "'";
            }
        }
        return "N'" + value.replace("'", "''") + "'";
    }

    private static String bracket(String name) { return "[" + name.replace("]", "]]") + "]"; }

    private static String singleLine(String text) { return text.replace('\r', ' ').replace('\n', ' '); }

    // endregion
}
