package com.dbsearch;

import java.io.*;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
public final class DbSearch {

    // TODO: Write db schema to json to make it relative fast to search independent of language.

    private static final String FILE_NAME = "db.ser";

    public static void save (Map<String, Map<String, Map<String, String>>> db) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream (
                new BufferedOutputStream (new FileOutputStream (FILE_NAME)))) {
            oos.writeObject (db);
        }
    }

    public static boolean deleteFile () {
        return new File (FILE_NAME).delete ();
    }

    @SuppressWarnings ("unchecked")
    public static Map<String, Map<String, Map<String, String>>> load () throws IOException, ClassNotFoundException {
        File f = new File (FILE_NAME);
        if (!f.exists ())
            return null;
        try (ObjectInputStream ois = new ObjectInputStream (new BufferedInputStream (new FileInputStream (f)))) {
            return (Map<String, Map<String, Map<String, String>>>) ois.readObject ();
        }
    }

    /**
     * Loads all non-system tables and columns into a nested map: schema -> table -> column -> SQL type name.
     */
    public static Map<String, Map<String, Map<String, String>>> loadSchema (Connection db) throws SQLException {
        Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<> ();

        DatabaseMetaData md = db.getMetaData ();

        try (ResultSet tbls = md.getTables (null, null, "%", new String[] {"TABLE"})) {
            while (tbls.next ()) {
                String schema = replaceNull (tbls.getString ("TABLE_SCHEM"));
                String table = tbls.getString ("TABLE_NAME");
                if (isSystemSchema (schema))
                    continue;

                // inner maps
                Map<String, Map<String, String>> tableMap = result.computeIfAbsent (schema,
                                                                                    k -> new LinkedHashMap<> ());
                Map<String, String> colMap = new LinkedHashMap<> ();

                try (ResultSet cols = md.getColumns (null, schema.isEmpty () ? null : schema, table, "%")) {
                    while (cols.next ()) {
                        String col = cols.getString ("COLUMN_NAME");
                        String type = cols.getString ("TYPE_NAME");
                        colMap.put (col, type);
                    }
                }

                tableMap.put (table, colMap);
            }
        }

        System.out.printf ("Loaded schema cache: %d schemas\n", result.size ());
        return result;
    }

    /** Case-insensitive substring search across table and column names */
    public static void search (String ownerFilter, String tableFilter, String columnFilter)
    throws IOException, ClassNotFoundException {
        ownerFilter = replaceNull (replaceStar (ownerFilter));
        tableFilter = replaceNull (replaceStar (tableFilter));
        columnFilter = replaceNull (replaceStar (columnFilter));

        final String ownerQ = ownerFilter.toLowerCase (Locale.ROOT);
        final String tableQ = tableFilter.toLowerCase (Locale.ROOT);
        final String columnQ = columnFilter.toLowerCase(Locale.ROOT);

        var dbSpec = load ();
        if (dbSpec == null) {
            System.out.println ("load first");
            return;
        }
        AtomicInteger hitCount = new AtomicInteger (0);
        dbSpec.forEach ((schema, tableMap) -> {
            String schemaL = schema.toLowerCase (Locale.ROOT);
            if (schemaL.contains (ownerQ)) {
                tableMap.forEach ((tableName, columns) -> {
                    String tableNameL = tableName.toLowerCase (Locale.ROOT);
                    if (tableNameL.contains (tableQ)) {
                        for (Map.Entry<String, String> columnToType : columns.entrySet ()) {
                            String columnName = columnToType.getKey ();
                            String columnNameL = columnName.toLowerCase (Locale.ROOT);
                            if (columnNameL.contains (columnQ)) {
                                hitCount.incrementAndGet ();
                                printTable (schema, tableName, columns);
                                break;
                            }
                        }
                    }
                });
            }
        });
        System.out.println ("Found " + hitCount.get () + " matches.");
    }

    private static void printTable (String schema, String tableName, Map<String, String> columns) {
        System.out.printf ("%nTABLE %s.%s%n", schema, tableName);
        columns.forEach ((columnName, typeName) -> {
            System.out.printf (" - %s (%s)%n", columnName, typeName);
        });
    }

    private static String replaceNull (String s) {
        return s == null ? "" : s;
    }

    private static String replaceWildcard (String s) {
        String test = s.trim ();
        if (test.equals ("%")) {
            return "";
        }
        return s;
    }

    private static boolean isSystemSchema (String schema) {
        String s = schema.toUpperCase (Locale.ROOT);
        return s.equals ("PG_CATALOG") || s.equals ("INFORMATION_SCHEMA") || s.equals ("SYS") || s.equals ("SYSTEM")
               || s.startsWith ("SYS_") || s.equals ("CTXSYS") || s.equals ("XDB") || s.equals ("MDSYS") || s.equals (
                "ORDSYS");
    }

    public static void main (String[] args) throws SQLException, IOException, ClassNotFoundException {

        String user = System.getenv ("dbsearch_db_user");
        String password = System.getenv ("dbsearch_db_password");
        String dbUrl = System.getenv ("dbsearch_db_url");
        Properties props = new Properties ();
        props.put ("user", user);
        props.put ("password", password);

        Connection db = DriverManager.getConnection (dbUrl, props);

        String ownerNameFilter = args[0].trim ();

        if (ownerNameFilter.equals ("load")) {
            long start = System.currentTimeMillis ();
            var dbSpec = loadSchema (db);
            save (dbSpec);
            long end = System.currentTimeMillis ();
            long dur = end - start;
            System.out.println ("Loaded in " + dur + " millis.");
        } else {
            String tableNameFilter = args[1].trim ();
            String columnNameFilter = args[2].trim ();
            System.out.println (ownerNameFilter);
            System.out.println (tableNameFilter);
            System.out.println (columnNameFilter);
            // Searching using % means empty string: matches true to any test string.
            search (ownerNameFilter, tableNameFilter, columnNameFilter);
        }
    }
}
