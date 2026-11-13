package com.diligentia.diff;

import java.util.Locale;

/**
 * Obiekt programowalny bazy (procedura, funkcja, widok, trigger) - z bazy albo z pliku repozytorium.
 */
public class SqlModule {

    // region Pola i konstruktor

    private final String schema;
    private final String name;
    private final String type;
    private final String definition;
    private final String origin;
    private final String modifyDate;
    private final boolean fromFile;

    public SqlModule(String schema, String name, String type, String definition, String origin, String modifyDate, boolean fromFile) {
        this.schema = schema;
        this.name = name;
        this.type = type;
        this.definition = definition;
        this.origin = origin;
        this.modifyDate = modifyDate;
        this.fromFile = fromFile;
    }

    // endregion

    // region Wlasciwosci

    public String getSchema() { return schema; }
    public String getName() { return name; }
    /** PROCEDURE, FUNCTION, VIEW albo TRIGGER. */
    public String getType() { return type; }
    /** Tresc obiektu; null dla obiektow WITH ENCRYPTION. */
    public String getDefinition() { return definition; }
    /** Sciezka pliku albo nazwa srodowiska. */
    public String getOrigin() { return origin; }
    /** Data modyfikacji w bazie (yyyy-MM-dd HH:mm); null dla plikow. */
    public String getModifyDate() { return modifyDate; }
    public boolean isFromFile() { return fromFile; }
    public boolean isEncrypted() { return definition == null; }

    public String getKey() { return (schema + "." + name).toLowerCase(Locale.ROOT); }

    public String getDisplayName() { return schema + "." + name; }

    /** Nazwa pliku w konwencji SSMS "Generate Scripts", np. dbo.MyProc.StoredProcedure.sql. */
    public String getFileName() { return sanitizeFileName(schema + "." + name) + "." + ssmsTypeName(type) + ".sql"; }

    // endregion

    // region Pomocnicze

    private static String ssmsTypeName(String type) {
        switch (type) {
            case "PROCEDURE": return "StoredProcedure";
            case "FUNCTION": return "UserDefinedFunction";
            case "VIEW": return "View";
            case "TRIGGER": return "Trigger";
            default: return type;
        }
    }

    public static String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // endregion
}
