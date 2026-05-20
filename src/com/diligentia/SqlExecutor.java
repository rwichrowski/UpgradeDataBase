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

            } catch (SQLException ex) {

                if (isMissingObjectError(ex) && containsAlter(sql)) {

                    String createSql = replaceAlterWithCreate(sql);

                    try {
                        stmt.execute(createSql);
                        System.out.printf("OK  | %s | %s (ALTER->CREATE)%n",
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


    private static String normalizeSql(String sql) {
        String upper = sql.toUpperCase();

        int[] indexes = {
                upper.indexOf("ALTER PROCEDURE"),
                upper.indexOf("CREATE PROCEDURE"),
                upper.indexOf("CREATE FUNCTION"),
                upper.indexOf("ALTER FUNCTION")
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

    private static boolean isMissingObjectError(SQLException ex) {
        int errorCode = ex.getErrorCode();

        // SQL Server
        return errorCode == 208     // Invalid object name
                || errorCode == 15151; // Cannot find the object
    }

    private static boolean containsAlter(String sql) {
        String upper = sql.toUpperCase();
        return upper.contains("ALTER PROCEDURE")
                || upper.contains("ALTER FUNCTION");
    }

    private static String replaceAlterWithCreate(String sql) {
        return sql
                .replaceFirst("(?i)ALTER\\s+PROCEDURE", "CREATE PROCEDURE")
                .replaceFirst("(?i)ALTER\\s+FUNCTION", "CREATE FUNCTION");
    }

}
