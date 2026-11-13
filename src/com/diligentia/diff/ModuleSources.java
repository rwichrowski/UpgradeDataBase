package com.diligentia.diff;

import com.diligentia.SqlExecutor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wczytywanie obiektow z bazy (sys.sql_modules) i z katalogu plikow .sql oraz zapis zrzutu na dysk.
 */
public final class ModuleSources {

    // region Pola i konstruktor

    private static final String MODULES_QUERY = "SELECT s.name, o.name, o.type, CONVERT(varchar(16), o.modify_date, 120), m.definition FROM sys.sql_modules m JOIN sys.objects o ON o.object_id = m.object_id JOIN sys.schemas s ON s.schema_id = o.schema_id WHERE o.is_ms_shipped = 0 AND o.type IN ('P', 'FN', 'IF', 'TF', 'V', 'TR') ORDER BY s.name, o.name";

    private static final Charset WINDOWS_1250 = Charset.forName("windows-1250");

    private ModuleSources() { }

    // endregion

    // region Baza danych

    /** Jedno zapytanie na baze: wszystkie procedury, funkcje, widoki i triggery z trescia. */
    public static List<SqlModule> fromDatabase(SqlExecutor.DbTarget target) throws SQLException {
        List<SqlModule> modules = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(target.getUrl(), target.getUser(), target.getPassword());
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(MODULES_QUERY)) {

            while (rs.next()) {
                modules.add(new SqlModule(rs.getString(1), rs.getString(2), typeFromCode(rs.getString(3)), rs.getString(5), target.getEnv(), rs.getString(4), false));
            }
        }
        return modules;
    }

    // endregion

    // region Katalog plikow

    /** Rekurencyjnie czyta pliki .sql; kazdy batch z naglowkiem CREATE/ALTER obiektu to osobny obiekt. */
    public static List<SqlModule> fromFolder(Path folder, List<String> warnings) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(folder)) {
            files = stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")).sorted().collect(Collectors.toList());
        }

        List<SqlModule> modules = new ArrayList<>();
        for (Path file : files) {
            String text = readText(file);
            boolean found = false;

            for (String batch : SqlTextParser.splitBatches(text)) {
                SqlTextParser.Header header = SqlTextParser.findHeader(batch);
                if (header == null) continue;

                modules.add(new SqlModule(header.schema, header.name, header.type, batch.substring(header.start), file.toString(), null, true));
                found = true;
            }

            if (!found) warnings.add("Plik bez CREATE/ALTER procedury, funkcji, widoku ani triggera: " + file);
        }
        return modules;
    }

    // endregion

    // region Zrzut na dysk

    /** Zapisuje tresc obiektow 1:1 jak w bazie, plik na obiekt. Wczesniejsze pliki .sql z katalogu sa usuwane. */
    public static void writeSnapshot(Path folder, List<SqlModule> modules) throws IOException {
        Files.createDirectories(folder);
        deleteSqlFiles(folder);

        for (SqlModule module : modules) {
            if (module.isEncrypted()) continue;
            Files.write(folder.resolve(module.getFileName()), module.getDefinition().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Usuwa pliki .sql z katalogu (bez podkatalogow) - katalog jest wynikiem pracy narzedzia. */
    public static void deleteSqlFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> stream = Files.list(folder)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }

    // endregion

    // region Pomocnicze

    /** Odczyt z rozpoznaniem BOM (UTF-8 / UTF-16 z SSMS); bez BOM UTF-8, a gdy niepoprawny - windows-1250. */
    public static String readText(Path file) throws IOException {
        byte[] b = Files.readAllBytes(file);

        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);

        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, WINDOWS_1250);
        }
    }

    private static String typeFromCode(String code) {
        switch (code.trim()) {
            case "P": return "PROCEDURE";
            case "FN":
            case "IF":
            case "TF": return "FUNCTION";
            case "V": return "VIEW";
            case "TR": return "TRIGGER";
            default: return code.trim();
        }
    }

    // endregion
}
