package com.diligentia.diff;

import com.diligentia.SqlExecutor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Wspolna logika trybu diff dla konsoli i okna: wczytanie stron, porownanie, pliki wynikowe i raport.
 */
public final class SchemaDiffService {

    // region Pola i konstruktor

    public static final String TARGETS_FILE = "targets.txt";
    private static final Path SNAPSHOTS_DIR = Paths.get("snapshots");
    private static final Path DIFF_DIR = Paths.get("diff");
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private SchemaDiffService() { }

    /** Jedna strona porownania; snapshotDir tylko dla bazy (katalog zrzutu), dla katalogu plikow null. */
    public static final class Side {
        public final String label;
        public final String description;
        public final List<SqlModule> modules;
        public final Path snapshotDir;
        /** Galaz git katalogu w chwili porownania; null dla bazy i katalogu spoza repozytorium. */
        public final String branch;

        Side(String label, String description, List<SqlModule> modules, Path snapshotDir, String branch) {
            this.label = label;
            this.description = description;
            this.modules = modules;
            this.snapshotDir = snapshotDir;
            this.branch = branch;
        }

        /** true dla bazy (pobranej z targets.txt), false dla katalogu plikow. */
        public boolean isDatabase() { return snapshotDir != null; }

        /** Opis do naglowkow i raportu, z galezia gdy jest. */
        public String describeWithBranch() { return branch == null ? description : description + " [gałąź: " + branch + "]"; }

        /** Plik, w ktorym lezy obiekt tej strony: plik repozytorium albo plik zrzutu bazy. */
        public Path sourceFile(SqlModule module) {
            if (module == null || module.isEncrypted()) return null;
            return module.isFromFile() ? Paths.get(module.getOrigin()) : snapshotDir.resolve(module.getFileName());
        }

        /** Sciezka pliku wzgledem katalogu strony (dla czytelnosci w raporcie i tabeli). */
        public String relativeOrigin(SqlModule module) {
            String origin = module.getOrigin();
            if (module.isFromFile() && origin.startsWith(description) && origin.length() > description.length()) return origin.substring(description.length() + 1);
            return origin;
        }
    }

    /** Wynik porownania razem z katalogami plikow do WinMerge. */
    public static final class Result {
        public final Side left;
        public final Side right;
        public final List<ModuleComparer.Entry> entries;
        public final List<String> warnings;
        public final Path outDir;
        public final Path leftDir;
        public final Path rightDir;
        public final List<String> report;

        Result(Side left, Side right, List<ModuleComparer.Entry> entries, List<String> warnings, Path outDir, List<String> report) {
            this.left = left;
            this.right = right;
            this.entries = entries;
            this.warnings = warnings;
            this.outDir = outDir;
            this.leftDir = outDir.resolve("left");
            this.rightDir = outDir.resolve("right");
            this.report = report;
        }

        public Path reportFile() { return outDir.resolve("report.txt"); }
    }

    // endregion

    // region Porownanie

    /** Wczytuje obie strony (bazy pobiera na biezaco), porownuje i zapisuje pliki do WinMerge oraz report.txt. */
    public static Result compare(String leftSpec, String rightSpec, boolean ignoreWhitespace, boolean ignoreCase, Consumer<String> log) throws Exception {
        List<String> warnings = new ArrayList<>();
        List<SqlExecutor.DbTarget> targets = readAllTargets();
        Side left = loadSide(leftSpec, targets, warnings, log);
        Side right = loadSide(rightSpec, targets, warnings, log);

        String rightLabel = left.label.equalsIgnoreCase(right.label) ? right.label + "_2" : right.label;
        Path outDir = DIFF_DIR.resolve(SqlModule.sanitizeFileName(left.label + "__vs__" + rightLabel)).toAbsolutePath();

        log.accept("Porownywanie...");
        List<ModuleComparer.Entry> entries = new ModuleComparer(ignoreWhitespace, ignoreCase).compare(left.modules, right.modules, warnings);

        List<String> report = new ArrayList<>();
        Result result = new Result(left, right, entries, warnings, outDir, report);
        writeDiffFiles(result);
        report.addAll(buildReport(result, ignoreWhitespace, ignoreCase));
        writeUtf8(result.reportFile(), String.join(System.lineSeparator(), report));
        return result;
    }

    /** Pliki lewy/prawy dla jednego obiektu do WinMerge; brakujaca strona to pusty plik, identyczne zapisywane na zadanie. */
    public static Path[] prepareFiles(Result result, ModuleComparer.Entry entry) throws IOException {
        boolean same = entry.status == ModuleComparer.Status.SAME;
        Path leftDir = same ? result.outDir.resolve("same").resolve("left") : result.leftDir;
        Path rightDir = same ? result.outDir.resolve("same").resolve("right") : result.rightDir;
        String fileName = entry.any().getFileName();
        return new Path[] { ensureFile(leftDir, fileName, entry.leftText, result.outDir), ensureFile(rightDir, fileName, entry.rightText, result.outDir) };
    }

    // endregion

    // region Zrodla

    public static Side loadSide(String spec, List<SqlExecutor.DbTarget> targets, List<String> warnings, Consumer<String> log) throws Exception {
        for (SqlExecutor.DbTarget target : targets) {
            if (target.getEnv().equalsIgnoreCase(spec)) {
                return new Side(target.getEnv(), target.getEnv(), fetchAndStore(target, log), snapshotDir(target), null);
            }
        }

        Path folder = Paths.get(spec).toAbsolutePath().normalize();
        if (Files.isDirectory(folder)) {
            log.accept("Czytanie katalogu: " + folder + " ...");
            List<SqlModule> modules = ModuleSources.fromFolder(folder, warnings);
            log.accept("    " + modules.size() + " obiektow");
            Path name = folder.getFileName();
            return new Side(name == null ? folder.toString() : name.toString(), folder.toString(), modules, null, GitBranch.of(folder));
        }

        throw new IllegalArgumentException("'" + spec + "' to ani srodowisko z " + TARGETS_FILE + ", ani istniejacy katalog");
    }

    /** Pobiera obiekty z bazy i zapisuje zrzut w snapshots\ENV. */
    public static List<SqlModule> fetchAndStore(SqlExecutor.DbTarget target, Consumer<String> log) throws Exception {
        log.accept("Pobieranie: " + target.getEnv() + " ...");
        List<SqlModule> modules = ModuleSources.fromDatabase(target);
        Path dir = snapshotDir(target);
        ModuleSources.writeSnapshot(dir, modules);
        log.accept("    " + modules.size() + " obiektow -> " + dir);
        return modules;
    }

    /** Tylko aktywne (niezakomentowane) srodowiska - te, na ktore wdraza SqlExecutor. */
    public static List<SqlExecutor.DbTarget> readTargetsIfPresent() {
        return new File(TARGETS_FILE).exists() ? SqlExecutor.readTargets(TARGETS_FILE) : new ArrayList<>();
    }

    /** Wszystkie srodowiska z targets.txt, takze zakomentowane - do porownania wolno uzyc kazdego. */
    public static List<SqlExecutor.DbTarget> readAllTargets() throws IOException {
        List<SqlExecutor.DbTarget> targets = new ArrayList<>();
        Path file = Paths.get(TARGETS_FILE);
        if (!Files.exists(file)) return targets;

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            SqlExecutor.DbTarget target = parseTargetLine(line.replace("﻿", ""));
            if (target != null && targets.stream().noneMatch(t -> t.getEnv().equalsIgnoreCase(target.getEnv()))) targets.add(target);
        }
        return targets;
    }

    /** Linia targets.txt (takze zakomentowana) jako srodowisko; null gdy to nie jest definicja ENV;jdbc:...|user|haslo. */
    public static SqlExecutor.DbTarget parseTargetLine(String line) {
        String body = line.trim().replaceFirst("^#+\\s*", "");
        int semicolon = body.indexOf(';');
        if (semicolon <= 0) return null;

        String[] parts = body.substring(semicolon + 1).trim().split("\\|", -1);
        if (parts.length != 3 || !parts[0].toLowerCase(Locale.ROOT).startsWith("jdbc:")) return null;
        return new SqlExecutor.DbTarget(body.substring(0, semicolon).trim(), parts[0], parts[1], parts[2]);
    }

    private static Path snapshotDir(SqlExecutor.DbTarget target) {
        return SNAPSHOTS_DIR.resolve(SqlModule.sanitizeFileName(target.getEnv())).toAbsolutePath();
    }

    // endregion

    // region Pliki wynikowe

    /** Znormalizowane pliki tylko dla obiektow roznych lub jednostronnych - gotowe do porownania katalogow w WinMerge. */
    private static void writeDiffFiles(Result result) throws IOException {
        for (Path dir : new Path[] { result.leftDir, result.rightDir, result.outDir.resolve("same").resolve("left"), result.outDir.resolve("same").resolve("right") }) {
            Files.createDirectories(dir);
            ModuleSources.deleteSqlFiles(dir);
        }

        for (ModuleComparer.Entry entry : result.entries) {
            if (entry.status == ModuleComparer.Status.SAME) continue;
            String fileName = entry.any().getFileName();
            if (entry.leftText != null) writeUtf8(result.leftDir.resolve(fileName), toCrLf(entry.leftText));
            if (entry.rightText != null) writeUtf8(result.rightDir.resolve(fileName), toCrLf(entry.rightText));
        }
    }

    private static Path ensureFile(Path dir, String fileName, String text, Path outDir) throws IOException {
        if (text == null) {
            Path empty = outDir.resolve("_brak_obiektu.sql");
            if (!Files.exists(empty)) writeUtf8(empty, "");
            return empty;
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) writeUtf8(file, toCrLf(text));
        return file;
    }

    static void writeUtf8(Path file, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, all, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, all, UTF8_BOM.length, body.length);
        Files.write(file, all);
    }

    private static String toCrLf(String text) {
        return text.replace("\n", "\r\n");
    }

    // endregion

    // region Raport

    private static List<String> buildReport(Result result, boolean ignoreWhitespace, boolean ignoreCase) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("=== Porownanie ===");
        lines.add(String.format("Lewa:  %s (%d obiektow)", result.left.describeWithBranch(), result.left.modules.size()));
        lines.add(String.format("Prawa: %s (%d obiektow)", result.right.describeWithBranch(), result.right.modules.size()));
        if (ignoreWhitespace || ignoreCase) lines.add("Opcje: " + (ignoreWhitespace ? "ignoruj biale znaki " : "") + (ignoreCase ? "ignoruj wielkosc liter" : ""));
        lines.add("");

        for (ModuleComparer.Entry entry : result.entries) {
            if (entry.status == ModuleComparer.Status.SAME) continue;
            SqlModule module = entry.any();
            lines.add(String.format("%-12s | %-9s | %-60s | %s", entry.status.getLabel(), module.getType(), module.getDisplayName(), describeOrigin(entry)));
        }

        lines.add("");
        lines.add(summary(result.entries));

        if (!result.warnings.isEmpty()) {
            lines.add("");
            lines.add("Ostrzezenia (" + result.warnings.size() + "):");
            result.warnings.forEach(w -> lines.add("  " + w));
        }

        lines.add("");
        lines.add("Porownanie katalogow w WinMerge:");
        lines.add("  " + WinMerge.commandLine(WinMerge.find(null), WinMerge.folderArgs(result.left.description, result.right.description, result.leftDir, result.rightDir)));
        return lines;
    }

    public static String summary(List<ModuleComparer.Entry> entries) {
        Map<ModuleComparer.Status, Integer> counts = countByStatus(entries);
        StringBuilder sb = new StringBuilder("Podsumowanie:");
        for (ModuleComparer.Status status : ModuleComparer.Status.values()) {
            sb.append(String.format(" %s %d,", status.getLabel().toLowerCase(), counts.getOrDefault(status, 0)));
        }
        return sb.substring(0, sb.length() - 1);
    }

    public static Map<ModuleComparer.Status, Integer> countByStatus(List<ModuleComparer.Entry> entries) {
        Map<ModuleComparer.Status, Integer> counts = new EnumMap<>(ModuleComparer.Status.class);
        for (ModuleComparer.Entry entry : entries) counts.merge(entry.status, 1, Integer::sum);
        return counts;
    }

    /** Gdzie poprawiac: plik z repozytorium (gdy ktoras strona to plik) albo data zmiany w bazie. */
    private static String describeOrigin(ModuleComparer.Entry entry) {
        List<String> parts = new ArrayList<>();
        for (SqlModule module : new SqlModule[] { entry.left, entry.right }) {
            if (module == null) continue;
            parts.add(module.isFromFile() ? module.getOrigin() : module.getOrigin() + " " + module.getModifyDate());
        }
        return String.join(" <-> ", parts);
    }

    // endregion
}
