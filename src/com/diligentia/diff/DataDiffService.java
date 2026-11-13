package com.diligentia.diff;

import com.diligentia.SqlExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Porownanie danych tabel slownikowych (Dict, Parameters, ...) miedzy dwiema bazami z targets.txt, z raportem data_report.txt.
 */
public final class DataDiffService {

    // region Pola i konstruktor

    private static final Path DIFF_DIR = Paths.get("diff");
    private static final Pattern DATABASE_NAME = Pattern.compile("(?i)(?:databaseName|database)=([^;]+)");

    private DataDiffService() { }

    /** Wynik porownania danych: zakresy (tabela albo slownik) z wierszami, ostrzezenia i raport. */
    public static final class Result {
        public final String leftLabel;
        public final String rightLabel;
        public final List<DataComparer.Scope> scopes;
        public final List<String> warnings;
        public final Path reportFile;
        public final List<String> report = new ArrayList<>();
        /** Nazwa bazy z URL JDBC (databaseName=...); null gdy jej tam nie ma. Sluzy do zabezpieczenia skryptu przed zla baza. */
        public final String leftDatabase;
        public final String rightDatabase;

        Result(String leftLabel, String rightLabel, List<DataComparer.Scope> scopes, List<String> warnings, Path reportFile, String leftDatabase, String rightDatabase) {
            this.leftLabel = leftLabel;
            this.rightLabel = rightLabel;
            this.scopes = scopes;
            this.warnings = warnings;
            this.reportFile = reportFile;
            this.leftDatabase = leftDatabase;
            this.rightDatabase = rightDatabase;
        }

        public Path outDir() { return reportFile.getParent(); }

        public Map<ModuleComparer.Status, Integer> counts() {
            Map<ModuleComparer.Status, Integer> counts = new EnumMap<>(ModuleComparer.Status.class);
            for (DataComparer.Scope scope : scopes) scope.counts().forEach((status, count) -> counts.merge(status, count, Integer::sum));
            return counts;
        }
    }

    // endregion

    // region Porownanie

    /** Obie strony musza byc srodowiskami z targets.txt - z repozytorium danych slownikowych nie da sie wziac. */
    public static Result compare(String leftSpec, String rightSpec, boolean ignoreWhitespace, boolean ignoreCase, Consumer<String> log) throws Exception {
        List<SqlExecutor.DbTarget> targets = SchemaDiffService.readAllTargets();
        SqlExecutor.DbTarget left = findTarget(leftSpec, targets);
        SqlExecutor.DbTarget right = findTarget(rightSpec, targets);
        List<DataTableSpec> specs = DataTableSpec.defaults();
        List<String> warnings = new ArrayList<>();

        log.accept("Pobieranie danych: " + left.getEnv() + " ...");
        List<DataSources.TableData> leftTables = DataSources.fromDatabase(left, specs, warnings);
        log.accept("Pobieranie danych: " + right.getEnv() + " ...");
        List<DataSources.TableData> rightTables = DataSources.fromDatabase(right, specs, warnings);

        log.accept("Porownywanie danych...");
        DataComparer comparer = new DataComparer(ignoreWhitespace, ignoreCase);
        List<DataComparer.Scope> scopes = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) scopes.addAll(comparer.compare(leftTables.get(i), rightTables.get(i), warnings));

        String rightLabel = left.getEnv().equalsIgnoreCase(right.getEnv()) ? right.getEnv() + "_2" : right.getEnv();
        Path outDir = DIFF_DIR.resolve(SqlModule.sanitizeFileName(left.getEnv() + "__vs__" + rightLabel)).toAbsolutePath();
        Result result = new Result(left.getEnv(), right.getEnv(), scopes, warnings, outDir.resolve("data_report.txt"), databaseName(left.getUrl()), databaseName(right.getUrl()));

        result.report.addAll(buildReport(result, ignoreWhitespace, ignoreCase));
        Files.createDirectories(outDir);
        SchemaDiffService.writeUtf8(result.reportFile, String.join(System.lineSeparator(), result.report));
        return result;
    }

    /** databaseName z URL JDBC SQL Server (jdbc:sqlserver://host:port;databaseName=X;...). */
    static String databaseName(String url) {
        Matcher matcher = DATABASE_NAME.matcher(url == null ? "" : url);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static SqlExecutor.DbTarget findTarget(String spec, List<SqlExecutor.DbTarget> targets) {
        for (SqlExecutor.DbTarget target : targets) if (target.getEnv().equalsIgnoreCase(spec)) return target;
        throw new IllegalArgumentException("'" + spec + "' to nie srodowisko z " + SchemaDiffService.TARGETS_FILE + " - slowniki porownuje sie tylko miedzy dwiema bazami");
    }

    // endregion

    // region Raport

    private static List<String> buildReport(Result result, boolean ignoreWhitespace, boolean ignoreCase) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("=== Porownanie slownikow ===");
        lines.add("Lewa:  " + result.leftLabel);
        lines.add("Prawa: " + result.rightLabel);
        if (ignoreWhitespace || ignoreCase) lines.add("Opcje: " + (ignoreWhitespace ? "ignoruj biale znaki " : "") + (ignoreCase ? "ignoruj wielkosc liter" : ""));

        for (DataComparer.Scope scope : result.scopes) {
            if (!scope.hasDifferences()) continue;
            lines.add("");
            lines.add("--- " + scope.caption + " (" + describeCounts(scope.counts()) + ")" + (scope.note == null ? "" : " [" + scope.note + "]"));

            for (DataComparer.RowDiff row : scope.rows) {
                if (row.status == ModuleComparer.Status.SAME) continue;
                lines.add(String.format("%-12s | %-40s | %s", row.status.getLabel(), row.displayKey(), describeRow(scope, row)));
            }
        }

        lines.add("");
        lines.add("Podsumowanie: " + describeCounts(result.counts()));

        if (!result.warnings.isEmpty()) {
            lines.add("");
            lines.add("Ostrzezenia (" + result.warnings.size() + "):");
            result.warnings.forEach(w -> lines.add("  " + w));
        }
        return lines;
    }

    /** Dla roznic: tylko zmienione kolumny (lewa -> prawa); dla wierszy jednostronnych: wszystkie kolumny poza kluczem. */
    private static String describeRow(DataComparer.Scope scope, DataComparer.RowDiff row) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < scope.columns.size(); i++) {
            if (scope.isKeyColumn(i) || i == scope.groupColumnIndex) continue;
            if (row.differs != null) {
                if (row.differs[i]) parts.add(scope.columns.get(i) + ": " + quote(row.left[i]) + " -> " + quote(row.right[i]));
            } else {
                parts.add(scope.columns.get(i) + "=" + quote(row.any()[i]));
            }
        }
        return String.join("; ", parts);
    }

    private static String describeCounts(Map<ModuleComparer.Status, Integer> counts) {
        return String.format("rozni sie %d, tylko lewa %d, tylko prawa %d, identyczne %d", counts.getOrDefault(ModuleComparer.Status.DIFFERENT, 0), counts.getOrDefault(ModuleComparer.Status.ONLY_LEFT, 0), counts.getOrDefault(ModuleComparer.Status.ONLY_RIGHT, 0), counts.getOrDefault(ModuleComparer.Status.SAME, 0));
    }

    private static String quote(String value) { return value == null ? "NULL" : "'" + value + "'"; }

    // endregion
}
