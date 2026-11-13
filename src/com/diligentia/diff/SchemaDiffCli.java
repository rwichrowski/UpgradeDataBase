package com.diligentia.diff;

import com.diligentia.SqlExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * Tryb "diff" z linii polecen.
 *
 * <pre>
 * diff [gui]                                      okno porownania
 * diff snapshot [ENV ...]
 * diff compare &lt;lewa&gt; &lt;prawa&gt; [-w] [-i] [--open]
 * </pre>
 * Strona porownania to nazwa srodowiska z targets.txt (pobierana na biezaco) albo sciezka katalogu z plikami .sql.
 */
public final class SchemaDiffCli {

    // region Pola i konstruktor

    private SchemaDiffCli() { }

    // endregion

    // region Polecenia

    public static void run(String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            SchemaDiffWindow.open();
            return;
        }

        List<String> positional = new ArrayList<>();
        boolean ignoreWhitespace = false;
        boolean ignoreCase = false;
        boolean open = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "-w": ignoreWhitespace = true; break;
                case "-i": ignoreCase = true; break;
                case "--open": open = true; break;
                default: positional.add(args[i]);
            }
        }

        try {
            switch (args[0].toLowerCase()) {
                case "snapshot":
                    runSnapshot(positional);
                    break;
                case "compare":
                    if (positional.size() != 2) {
                        printUsage();
                        return;
                    }
                    runCompare(positional.get(0), positional.get(1), ignoreWhitespace, ignoreCase, open);
                    break;
                default:
                    printUsage();
            }
        } catch (Exception e) {
            System.out.println("ERR | " + e.getMessage());
        }
    }

    private static void runSnapshot(List<String> envs) throws Exception {
        List<SqlExecutor.DbTarget> targets = envs.isEmpty() ? SchemaDiffService.readTargetsIfPresent() : SchemaDiffService.readAllTargets();
        if (targets.isEmpty()) {
            System.out.println("Brak srodowisk w " + SchemaDiffService.TARGETS_FILE);
            return;
        }

        for (SqlExecutor.DbTarget target : targets) {
            if (!envs.isEmpty() && envs.stream().noneMatch(e -> e.equalsIgnoreCase(target.getEnv()))) continue;
            try {
                SchemaDiffService.fetchAndStore(target, System.out::println);
            } catch (Exception e) {
                System.out.printf("ERR | %s | %s%n", target.getEnv(), e.getMessage());
            }
        }
        System.out.println("\nDone.");
    }

    private static void runCompare(String leftSpec, String rightSpec, boolean ignoreWhitespace, boolean ignoreCase, boolean open) throws Exception {
        SchemaDiffService.Result result = SchemaDiffService.compare(leftSpec, rightSpec, ignoreWhitespace, ignoreCase, System.out::println);
        result.report.forEach(System.out::println);
        System.out.println("Raport: " + result.reportFile());

        if (open) WinMerge.start(WinMerge.find(null), WinMerge.folderArgs(result.left.description, result.right.description, result.leftDir, result.rightDir));
    }

    // endregion

    // region Pomocnicze

    private static void printUsage() {
        System.out.println("Uzycie:");
        System.out.println("  diff [gui]                                    okno porownania");
        System.out.println("  diff snapshot [ENV ...]                       zrzut obiektow z baz do snapshots\\ENV (bez ENV - wszystkie z targets.txt)");
        System.out.println("  diff compare <lewa> <prawa> [-w] [-i] [--open]");
        System.out.println("      <lewa>/<prawa>  nazwa srodowiska z targets.txt albo katalog z plikami .sql");
        System.out.println("      -w              ignoruj roznice w bialych znakach");
        System.out.println("      -i              ignoruj wielkosc liter");
        System.out.println("      --open          otworz wynik w WinMerge (porownanie katalogow)");
    }

    // endregion
}
