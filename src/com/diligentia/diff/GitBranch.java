package com.diligentia.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Biezaca galaz repozytorium git, w ktorym lezy katalog - odczytana z .git/HEAD, bez uruchamiania gita.
 */
public final class GitBranch {

    // region Pola i konstruktor

    private static final Set<String> MAIN_BRANCHES = new HashSet<>(Arrays.asList("master", "main"));
    private static final String HEADS_PREFIX = "ref: refs/heads/";
    private static final String REF_PREFIX = "ref: ";
    private static final String GITDIR_PREFIX = "gitdir:";

    private GitBranch() { }

    // endregion

    // region Odczyt

    /** Nazwa galezi; "(odlaczony HEAD abc1234)" przy detached HEAD; null gdy katalog nie lezy w repozytorium git. */
    public static String of(Path folder) {
        try {
            Path gitDir = findGitDir(folder);
            if (gitDir == null) return null;

            Path head = gitDir.resolve("HEAD");
            if (!Files.isRegularFile(head)) return null;

            List<String> lines = Files.readAllLines(head, StandardCharsets.UTF_8);
            String content = lines.isEmpty() ? "" : lines.get(0).trim();
            if (content.startsWith(HEADS_PREFIX)) return content.substring(HEADS_PREFIX.length());
            if (content.startsWith(REF_PREFIX)) return content.substring(REF_PREFIX.length());
            return "(odłączony HEAD " + content.substring(0, Math.min(7, content.length())) + ")";
        } catch (IOException e) {
            return null;
        }
    }

    /** true dla master i main - przy nich porownanie nie wymaga potwierdzenia. */
    public static boolean isMainBranch(String branch) {
        return branch != null && MAIN_BRANCHES.contains(branch.toLowerCase(Locale.ROOT));
    }

    // endregion

    // region Pomocnicze

    /** Katalog .git szukany w gore drzewa; obsluguje tez plik .git z "gitdir: ..." (worktree, submodul). */
    private static Path findGitDir(Path folder) throws IOException {
        Path current = folder.toAbsolutePath().normalize();
        while (current != null) {
            Path git = current.resolve(".git");
            if (Files.isDirectory(git)) return git;

            if (Files.isRegularFile(git)) {
                for (String line : Files.readAllLines(git, StandardCharsets.UTF_8)) {
                    if (line.startsWith(GITDIR_PREFIX)) return current.resolve(line.substring(GITDIR_PREFIX.length()).trim()).normalize();
                }
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    // endregion
}
