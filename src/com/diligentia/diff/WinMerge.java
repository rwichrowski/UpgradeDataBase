package com.diligentia.diff;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Uruchamianie WinMerge: porownanie dwoch plikow albo dwoch katalogow.
 */
public final class WinMerge {

    // region Pola i konstruktor

    private static final String DEFAULT_EXE = "WinMergeU.exe";

    private WinMerge() { }

    // endregion

    // region Uruchamianie

    /** Sciezka WinMergeU.exe: wskazana (gdy istnieje), typowe katalogi instalacji, a na koncu sama nazwa z PATH. */
    public static String find(String preferred) {
        if (preferred != null && !preferred.isEmpty() && new File(preferred).isFile()) return preferred;

        String localAppData = System.getenv("LOCALAPPDATA");
        List<String> candidates = new ArrayList<>(Arrays.asList("C:\\Program Files\\WinMerge\\WinMergeU.exe", "C:\\Program Files (x86)\\WinMerge\\WinMergeU.exe"));
        if (localAppData != null) candidates.add(localAppData + "\\Programs\\WinMerge\\WinMergeU.exe");

        for (String candidate : candidates) {
            if (new File(candidate).isFile()) return candidate;
        }
        return DEFAULT_EXE;
    }

    public static List<String> fileArgs(String leftDescription, String rightDescription, Path left, Path right) {
        return Arrays.asList("/e", "/u", "/dl", leftDescription, "/dr", rightDescription, left.toAbsolutePath().toString(), right.toAbsolutePath().toString());
    }

    public static List<String> folderArgs(String leftDescription, String rightDescription, Path left, Path right) {
        return Arrays.asList("/r", "/u", "/dl", leftDescription, "/dr", rightDescription, left.toAbsolutePath().toString(), right.toAbsolutePath().toString());
    }

    public static void start(String exe, List<String> args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(exe);
        command.addAll(args);
        new ProcessBuilder(command).start();
    }

    /** Komenda do wklejenia w PowerShell. */
    public static String commandLine(String exe, List<String> args) {
        StringBuilder sb = new StringBuilder("& \"").append(exe).append('"');
        for (String arg : args) {
            sb.append(' ').append(arg.startsWith("/") ? arg : "\"" + arg + "\"");
        }
        return sb.toString();
    }

    // endregion
}
