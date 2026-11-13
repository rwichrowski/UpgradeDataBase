package com.diligentia.diff;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Uruchamianie Git Extensions: historia pojedynczego pliku (polecenie "filehistory").
 */
public final class GitExtensions {

    // region Pola i konstruktor

    private static final String DEFAULT_EXE = "GitExtensions.exe";

    private GitExtensions() { }

    // endregion

    // region Uruchamianie

    /** Sciezka GitExtensions.exe: wskazana (gdy istnieje), typowe katalogi instalacji, a na koncu sama nazwa z PATH. */
    public static String find(String preferred) {
        if (preferred != null && !preferred.isEmpty() && new File(preferred).isFile()) return preferred;

        String localAppData = System.getenv("LOCALAPPDATA");
        List<String> candidates = new ArrayList<>(Arrays.asList("C:\\Program Files\\GitExtensions\\GitExtensions.exe", "C:\\Program Files (x86)\\GitExtensions\\GitExtensions.exe"));
        if (localAppData != null) candidates.add(localAppData + "\\Programs\\GitExtensions\\GitExtensions.exe");

        for (String candidate : candidates) {
            if (new File(candidate).isFile()) return candidate;
        }
        return DEFAULT_EXE;
    }

    /** Okno historii pliku ze wszystkimi commitami, ktore go zmienialy; katalog roboczy = katalog pliku, zeby Git Extensions trafil w repozytorium. */
    public static void fileHistory(String exe, Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        new ProcessBuilder(exe, "filehistory", absolute.toString()).directory(absolute.getParent().toFile()).start();
    }

    // endregion
}
