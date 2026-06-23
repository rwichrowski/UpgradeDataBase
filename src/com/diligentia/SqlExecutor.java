package com.diligentia;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SqlExecutor {

    public static class DbTarget {
        private final String env;
        private final String url;
        private final String user;
        private final String password;

        public DbTarget(String env, String url, String user, String password) {
            this.env = env;
            this.url = url;
            this.user = user;
            this.password = password;
        }

        public String getEnv() { return env; }
        public String getUrl() { return url; }
        public String getUser() { return user; }
        public String getPassword() { return password; }
    }

    private static Connection getConnection(DbTarget target) throws SQLException {
        return DriverManager.getConnection(target.getUrl(), target.getUser(), target.getPassword());
    }

    public static List<DbTarget> readTargets(String filePath) {
        List<DbTarget> targets = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] envAndRest = line.split(";", 2);
                if (envAndRest.length != 2) {
                    throw new IllegalArgumentException("Invalid target line (expected env;url|user|password): " + line);
                }

                String env = envAndRest[0].trim();
                String rest = envAndRest[1].trim();

                String[] parts = rest.split("\\|", -1);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Invalid target line (expected env;url|user|password): " + line);
                }

                targets.add(new DbTarget(env, parts[0], parts[1], parts[2]));
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to read targets from: " + filePath, e);
        }

        return targets;
    }

    public static List<File> readScriptPaths(String filePath) {
        List<File> scriptFiles = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                File scriptFile = new File(line);
                if (scriptFile.exists()) {
                    scriptFiles.add(scriptFile);
                } else {
                    System.out.println("Script file not found: " + line);
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to read scripts from: " + filePath, e);
        }

        return scriptFiles;
    }

    public static void executeSqlScript(DbTarget target, File scriptFile) {
        try (Connection connection = getConnection(target);
             Statement stmt = connection.createStatement()) {

            String originalSql = new String(
                    java.nio.file.Files.readAllBytes(scriptFile.toPath()),
                    StandardCharsets.UTF_8);

            String sql = normalizeSql(originalSql);

            try {
                stmt.execute(sql);
                System.out.printf("OK  | %s | %s%n", target.getEnv(), scriptFile.getName());
                printSqlMessages(target, scriptFile, stmt);

            } catch (SQLException ex) {

                if (containsCreateOrAlter(sql)) {
                    // CREATE OR ALTER jest idempotentne — nie ma sensu retry; zgłoś błąd wprost
                    System.out.printf("ERR | %s | %s | %s%n",
                            target.getEnv(), scriptFile.getName(), ex.getMessage());

                } else if (shouldRetryWithOppositeKeyword(ex) && containsAlter(sql)) {

                    // Obiekt nie istnieje → spróbuj CREATE
                    String createSql = replaceAlterWithCreate(sql);
                    try {
                        stmt.execute(createSql);
                        System.out.printf("OK  | %s | %s (ALTER->CREATE)%n",
                                target.getEnv(), scriptFile.getName());
                    } catch (SQLException ex2) {
                        System.out.printf("ERR | %s | %s | %s%n",
                                target.getEnv(), scriptFile.getName(), ex2.getMessage());
                    }

                } else if (shouldRetryWithOppositeKeyword(ex) && containsCreate(sql)) {

                    // Obiekt już istnieje → spróbuj ALTER
                    String alterSql = replaceCreateWithAlter(sql);
                    try {
                        stmt.execute(alterSql);
                        System.out.printf("OK  | %s | %s (CREATE->ALTER)%n",
                                target.getEnv(), scriptFile.getName());
                    } catch (SQLException ex2) {
                        System.out.printf("ERR | %s | %s | %s%n",
                                target.getEnv(), scriptFile.getName(), ex2.getMessage());
                    }

                } else {
                    System.out.printf("ERR | %s | %s | %s%n",
                            target.getEnv(), scriptFile.getName(), ex.getMessage());
                }
            }

        } catch (SQLException | IOException e) {
            System.out.printf("ERR | %s | %s | %s%n",
                    target.getEnv(), scriptFile.getName(), e.getMessage());
        }
    }

    public static void main(String[] args) {
        String scriptsPathsFile = "scripts_paths.txt";
        String targetsFile = "targets.txt";

        List<File> scripts = readScriptPaths(scriptsPathsFile);
        List<DbTarget> targets = readTargets(targetsFile);

        if (scripts.isEmpty()) {
            System.out.println("No SQL scripts found in scripts_paths.txt");
            return;
        }
        if (targets.isEmpty()) {
            System.out.println("No DB targets found in targets.txt");
            return;
        }

        for (DbTarget target : targets) {
            System.out.printf("%n=== ENV: %s ===%n", target.getEnv());

            for (File script : scripts) {
                executeSqlScript(target, script);
            }
        }

        System.out.println("\nDone.");
    }

    private static void printSqlMessages(DbTarget target, File scriptFile, Statement stmt) {
        try {
            SQLWarning warning = stmt.getWarnings();
            while (warning != null) {
                System.out.printf("    > %s | %s | %s%n",
                        target.getEnv(), scriptFile.getName(), warning.getMessage());
                warning = warning.getNextWarning();
            }
            stmt.clearWarnings();
        } catch (SQLException ignored) {
            // brak komunikatow PRINT lub sterownik ich nie udostepnia
        }
    }

    private static String normalizeSql(String sql) {
        String upper = sql.toUpperCase();

        int[] indexes = {
                upper.indexOf("CREATE OR ALTER PROCEDURE"),
                upper.indexOf("CREATE OR ALTER FUNCTION"),
                upper.indexOf("CREATE OR ALTER TRIGGER"),
                upper.indexOf("ALTER PROCEDURE"),
                upper.indexOf("CREATE PROCEDURE"),
                upper.indexOf("CREATE FUNCTION"),
                upper.indexOf("ALTER FUNCTION"),
                upper.indexOf("ALTER TRIGGER"),
                upper.indexOf("CREATE TRIGGER")
        };

        int minIndex = Integer.MAX_VALUE;
        for (int idx : indexes) {
            if (idx != -1 && idx < minIndex) {
                minIndex = idx;
            }
        }

        if (minIndex != Integer.MAX_VALUE) {
            sql = sql.substring(minIndex);
        }

        // usuwanie GO
        sql = sql.replaceAll("(?im)^\\s*GO\\s*$", "");

        return sql;
    }

    private static boolean shouldRetryWithOppositeKeyword(SQLException ex) {
        int errorCode = ex.getErrorCode();
        return errorCode == 208      // Invalid object name (brak obiektu → użyj CREATE)
                || errorCode == 15151  // Cannot find the object (brak obiektu → użyj CREATE)
                || errorCode == 4902   // Cannot find the object - trigger (brak obiektu → użyj CREATE)
                || errorCode == 2714;  // There is already an object named '...' (istnieje → użyj ALTER)
    }

    private static boolean containsCreateOrAlter(String sql) {
        String upper = sql.toUpperCase();
        return upper.contains("CREATE OR ALTER PROCEDURE")
                || upper.contains("CREATE OR ALTER FUNCTION")
                || upper.contains("CREATE OR ALTER TRIGGER");
    }

    private static boolean containsAlter(String sql) {
        // Negative lookbehind wyklucza "OR ALTER" będące częścią "CREATE OR ALTER"
        return java.util.regex.Pattern
                .compile("(?i)(?<!OR\\s)ALTER\\s+(PROCEDURE|FUNCTION|TRIGGER)")
                .matcher(sql).find();
    }

    private static boolean containsCreate(String sql) {
        String upper = sql.toUpperCase();
        return upper.contains("CREATE PROCEDURE")
                || upper.contains("CREATE FUNCTION")
                || upper.contains("CREATE TRIGGER");
    }

    private static String replaceAlterWithCreate(String sql) {
        return sql
                .replaceFirst("(?i)ALTER\\s+PROCEDURE", "CREATE PROCEDURE")
                .replaceFirst("(?i)ALTER\\s+FUNCTION",  "CREATE FUNCTION")
                .replaceFirst("(?i)ALTER\\s+TRIGGER",   "CREATE TRIGGER");
    }

    private static String replaceCreateWithAlter(String sql) {
        return sql
                .replaceFirst("(?i)CREATE\\s+PROCEDURE", "ALTER PROCEDURE")
                .replaceFirst("(?i)CREATE\\s+FUNCTION",  "ALTER FUNCTION")
                .replaceFirst("(?i)CREATE\\s+TRIGGER",   "ALTER TRIGGER");
    }
}