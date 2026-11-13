package com.diligentia.diff;

import com.diligentia.SqlExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Przygotowanie wdrozenia wersji z repozytorium na baze z porownania: wypelnia scripts_paths.txt wybranymi plikami
 * i zostawia w targets.txt aktywne tylko docelowe srodowisko. Samo wdrozenie robi SqlExecutor uruchomiony bez argumentow.
 */
public final class DeploymentPreparer {

    // region Pola i konstruktor

    public static final String SCRIPTS_FILE = "scripts_paths.txt";

    /** Kolejnosc wdrazania: najpierw obiekty, od ktorych zwykle zaleza inne. */
    private static final List<String> TYPE_ORDER = Arrays.asList("FUNCTION", "VIEW", "PROCEDURE", "TRIGGER");

    private DeploymentPreparer() { }

    /** Co zostanie zapisane: srodowisko docelowe, pliki w kolejnosci wdrazania i uwagi do potwierdzenia. */
    public static final class Plan {
        public final String environment;
        public final String repositoryDescription;
        public final List<Path> files;
        public final List<String> warnings;

        Plan(String environment, String repositoryDescription, List<Path> files, List<String> warnings) {
            this.environment = environment;
            this.repositoryDescription = repositoryDescription;
            this.files = files;
            this.warnings = warnings;
        }
    }

    // endregion

    // region Plan wdrozenia

    /** true gdy jedna strona to baza, a druga katalog repozytorium. */
    public static boolean isRepositoryComparison(SchemaDiffService.Result result) {
        return result != null && result.left.isDatabase() != result.right.isDatabase();
    }

    public static SchemaDiffService.Side databaseSide(SchemaDiffService.Result result) {
        return result.left.isDatabase() ? result.left : result.right;
    }

    public static SchemaDiffService.Side repositorySide(SchemaDiffService.Result result) {
        return result.left.isDatabase() ? result.right : result.left;
    }

    /** Obiekt po stronie repozytorium; null gdy obiekt jest tylko w bazie (nie ma czego wdrazac). */
    public static SqlModule repositoryModule(SchemaDiffService.Result result, ModuleComparer.Entry entry) {
        if (!isRepositoryComparison(result)) return null;
        return result.left.isDatabase() ? entry.right : entry.left;
    }

    public static Plan buildPlan(SchemaDiffService.Result result, Collection<ModuleComparer.Entry> selected) {
        if (!isRepositoryComparison(result)) throw new IllegalStateException("Wdrozenie jest dostepne tylko dla porownania baza <-> repozytorium");

        SchemaDiffService.Side repository = repositorySide(result);
        List<ModuleComparer.Entry> ordered = new ArrayList<>(selected);
        ordered.removeIf(e -> repositoryModule(result, e) == null);
        ordered.sort(Comparator.comparingInt((ModuleComparer.Entry e) -> typeRank(repositoryModule(result, e).getType())).thenComparing(e -> e.key));

        Map<String, List<SqlModule>> modulesByFile = new LinkedHashMap<>();
        for (SqlModule module : repository.modules) modulesByFile.computeIfAbsent(module.getOrigin(), k -> new ArrayList<>()).add(module);

        Set<Path> files = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        for (ModuleComparer.Entry entry : ordered) {
            SqlModule module = repositoryModule(result, entry);
            if (!files.add(Paths.get(module.getOrigin()))) continue;

            if (entry.status == ModuleComparer.Status.SAME) warnings.add(module.getDisplayName() + " jest identyczny jak w bazie.");

            List<SqlModule> inFile = modulesByFile.getOrDefault(module.getOrigin(), new ArrayList<>());
            if (inFile.size() > 1) warnings.add(repository.relativeOrigin(module) + " zawiera " + inFile.size() + " obiekty – SqlExecutor wykona caly plik jako jeden batch (bez GO), co zwykle konczy sie bledem.");
        }

        return new Plan(databaseSide(result).description, repository.description, new ArrayList<>(files), warnings);
    }

    // endregion

    // region Zapis plikow

    /** Zapisuje scripts_paths.txt i targets.txt. */
    public static void write(Plan plan, Path scriptsFile, Path targetsFile) throws IOException {
        List<String> targetLines = Files.readAllLines(targetsFile, StandardCharsets.UTF_8);
        List<String> activated = activateOnly(targetLines, plan.environment);

        List<String> scriptLines = new ArrayList<>();
        scriptLines.add("# Wdrozenie z porownania " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ": " + plan.repositoryDescription + " -> " + plan.environment);
        plan.files.forEach(f -> scriptLines.add(f.toString()));

        writeLines(scriptsFile, scriptLines, lineSeparatorOf(scriptsFile));
        writeLines(targetsFile, activated, lineSeparatorOf(targetsFile));
    }

    /** Odkomentowuje pierwsza definicje srodowiska docelowego, pozostale definicje komentuje; reszta linii bez zmian. */
    static List<String> activateOnly(List<String> lines, String environment) {
        List<String> result = new ArrayList<>();
        boolean activated = false;

        for (String line : lines) {
            String clean = line.replace("﻿", "");
            SqlExecutor.DbTarget target = SchemaDiffService.parseTargetLine(clean);
            boolean commented = clean.trim().startsWith("#");

            if (target == null) {
                result.add(line);
            } else if (!activated && target.getEnv().equalsIgnoreCase(environment)) {
                result.add(clean.trim().replaceFirst("^#+\\s*", ""));
                activated = true;
            } else {
                result.add(commented ? line : "# " + line);
            }
        }

        if (!activated) throw new IllegalArgumentException("Nie znaleziono srodowiska '" + environment + "' w " + SchemaDiffService.TARGETS_FILE);
        return result;
    }

    // endregion

    // region Pomocnicze

    private static int typeRank(String type) {
        int index = TYPE_ORDER.indexOf(type);
        return index < 0 ? TYPE_ORDER.size() : index;
    }

    /** Zachowuje konce linii istniejacego pliku; dla nowego pliku CRLF. */
    private static String lineSeparatorOf(Path file) throws IOException {
        if (!Files.exists(file)) return "\r\n";
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return text.contains("\r\n") || !text.contains("\n") ? "\r\n" : "\n";
    }

    /** UTF-8 bez BOM - SqlExecutor czyta pierwsza linie doslownie, BOM zepsulby sciezke albo nazwe srodowiska. */
    private static void writeLines(Path file, List<String> lines, String separator) throws IOException {
        Files.write(file, (String.join(separator, lines) + separator).getBytes(StandardCharsets.UTF_8));
    }

    // endregion
}
