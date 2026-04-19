package com.dbsearch;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public static void save (Map<String, Map<String, Map<String, String>>> db, File cacheFile) throws IOException {
	try (ObjectOutputStream oos = new ObjectOutputStream (
		new BufferedOutputStream (new FileOutputStream (cacheFile)))) {
	    oos.writeObject (db);
	}
    }

    @SuppressWarnings ("unchecked")
    public static Map<String, Map<String, Map<String, String>>> load (File cacheFile)
    throws IOException, ClassNotFoundException {
	if (!cacheFile.exists ())
	    return null;
	try (ObjectInputStream ois = new ObjectInputStream (
		new BufferedInputStream (new FileInputStream (cacheFile)))) {
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
			String nullability = getNullability (cols);
			String col = cols.getString ("COLUMN_NAME");
			String type = cols.getString ("TYPE_NAME");
			String fullTypeName = type + " " + nullability;
			colMap.put (col, fullTypeName);
		    }
		}

		tableMap.put (table, colMap);
	    }
	}

	System.out.printf ("Loaded schema cache: %d schemas\n", result.size ());
	return result;
    }

    private static String getNullability (ResultSet cols) throws SQLException {
	int nullable = cols.getInt ("NULLABLE");
	return switch (nullable) {
	    case DatabaseMetaData.columnNoNulls -> "NOT NULL";
	    case DatabaseMetaData.columnNullable -> "NULL";
	    default -> ""; // unknown / not reported
	};
    }

    /** Case-insensitive substring search across table and column names */
    public static void search (String ownerFilter, String tableFilter, String columnFilter, File cacheFile)
    throws IOException, ClassNotFoundException {
	ownerFilter = replaceNull (replaceWildcard (ownerFilter));
	tableFilter = replaceNull (replaceWildcard (tableFilter));
	columnFilter = replaceNull (replaceWildcard (columnFilter));

	final String ownerQ = ownerFilter.toLowerCase (Locale.ROOT);
	final String tableQ = tableFilter.toLowerCase (Locale.ROOT);
	final String columnQ = columnFilter.toLowerCase (Locale.ROOT);

	var dbSpec = load (cacheFile);
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

    private static File getFile (String path) throws IOException {
	File f = new File (path);
	if (!f.exists ()) {
	    throw new IllegalArgumentException ("Cache file not present");
	}
	return f;
    }

    private static Properties loadEnv (Path envFile) throws IOException {
	Properties p = new Properties ();
	if (envFile != null && Files.exists (envFile)) {
	    try (InputStream in = Files.newInputStream (envFile)) {
		p.load (in);
	    }
	}
	return p;
    }

    private static Properties loadEnvFromRepoRoot () {
	try {
	    Path jarDir = Path.of (
		    DbSearch.class.getProtectionDomain ().getCodeSource ().getLocation ().toURI ()).getParent ();
	    Path jarDirParent = jarDir.getParent ();
	    return loadEnv (jarDirParent.resolve (".env"));

	} catch (Exception e) {
	    throw new RuntimeException ("Failed to locate .env relative to application", e);
	}
    }

    public static void main (String[] args) throws SQLException, IOException, ClassNotFoundException {

	Properties env = loadEnvFromRepoRoot ();

	String user = env.getProperty ("DBSEARCH_DB_USER");
	String password = env.getProperty ("DBSEARCH_DB_PASSWORD");
	String dbUrl = env.getProperty ("DBSEARCH_DB_URL");
	String cachePath = env.getProperty ("DBSEARCH_CACHE");

	File cacheFile = getFile (cachePath);

	Properties dbProps = new Properties ();
	dbProps.put ("user", user);
	dbProps.put ("password", password);

	String ownerNameFilter = args[0].trim ();

	if (ownerNameFilter.equals ("load")) {
	    Connection db = DriverManager.getConnection (dbUrl, dbProps);
	    long start = System.currentTimeMillis ();
	    var dbSpec = loadSchema (db);
	    save (dbSpec, cacheFile);
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
	    search (ownerNameFilter, tableNameFilter, columnNameFilter, cacheFile);
	}
    }
}
