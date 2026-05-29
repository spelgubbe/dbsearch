package com.dbsearch;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class DbSearch {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void save(Map<String, Map<String, Map<String, String>>> db, File cacheFile) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(new FileOutputStream(cacheFile)))) {
            oos.writeObject(db);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Map<String, String>>> load(File cacheFile)
            throws IOException, ClassNotFoundException {
        if (!cacheFile.exists())
            return null;
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(cacheFile)))) {
            return (Map<String, Map<String, Map<String, String>>>) ois.readObject();
        }
    }

    private static File metaFile(File cacheFile) {
        return new File(cacheFile.getPath() + ".meta");
    }

    private static void saveMeta(File cacheFile, String dbUrl) throws IOException {
        Properties meta = new Properties();
        meta.setProperty("url", dbUrl);
        meta.setProperty("loadedAt", String.valueOf(System.currentTimeMillis()));
        try (OutputStream out = new FileOutputStream(metaFile(cacheFile))) {
            meta.store(out, null);
        }
    }

    private static void printCacheMeta(File cacheFile, boolean verbose) {
        if (!verbose) return;
        File mf = metaFile(cacheFile);
        if (!mf.exists()) return;
        try {
            Properties meta = new Properties();
            try (InputStream in = new FileInputStream(mf)) {
                meta.load(in);
            }
            String url = meta.getProperty("url", "unknown");
            long ts = Long.parseLong(meta.getProperty("loadedAt", "0"));
            String loaded = ts == 0 ? "unknown"
                    : LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(FMT);
            System.out.printf("[Cache] %s  loaded %s%n", url, loaded);
        } catch (Exception e) {
            System.err.println("Warning: failed to read cache metadata:");
            e.printStackTrace(System.err);
        }
    }

    /** Nested map: schema -> table -> column -> SQL type + nullability. */
    public static Map<String, Map<String, Map<String, String>>> loadSchema(
            Connection db, String schemaPattern, String tablePattern) throws SQLException {
        Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<>();
        DatabaseMetaData md = db.getMetaData();

        try (ResultSet tbls = md.getTables(null, schemaPattern, tablePattern, new String[]{"TABLE"})) {
            while (tbls.next()) {
                String schema = replaceNull(tbls.getString("TABLE_SCHEM"));
                String table = tbls.getString("TABLE_NAME");
                if (isSystemSchema(schema)) continue;

                Map<String, Map<String, String>> tableMap = result.computeIfAbsent(schema, k -> new LinkedHashMap<>());
                Map<String, String> colMap = new LinkedHashMap<>();

                try (ResultSet cols = md.getColumns(null, schema.isEmpty() ? null : schema, table, "%")) {
                    while (cols.next()) {
                        String col = cols.getString("COLUMN_NAME");
                        String type = cols.getString("TYPE_NAME");
                        colMap.put(col, type + " " + getNullability(cols));
                    }
                }

                tableMap.put(table, colMap);
            }
        }

        System.out.printf("Loaded schema: %d schemas%n", result.size());
        return result;
    }

    private static String getNullability(ResultSet cols) throws SQLException {
        int nullable = cols.getInt("NULLABLE");
        return switch (nullable) {
            case DatabaseMetaData.columnNoNulls -> "NOT NULL";
            case DatabaseMetaData.columnNullable -> "NULL";
            default -> "";
        };
    }

    public static void search(String ownerFilter, String tableFilter, String columnFilter, File cacheFile,
            boolean verbose) throws IOException, ClassNotFoundException {
        var dbSpec = load(cacheFile);
        if (dbSpec == null) {
            System.out.println("No cache found. Run 'load' first or use --live.");
            return;
        }
        printCacheMeta(cacheFile, verbose);
        searchInMemory(ownerFilter, tableFilter, columnFilter, dbSpec);
    }

    public static void searchLive(String ownerFilter, String tableFilter, String columnFilter, Connection db)
            throws SQLException {
        // Uppercase to match Oracle's identifier storage; in-memory filter below is authoritative.
        String schemaJdbc = ownerFilter.isEmpty() ? "%" : "%" + ownerFilter.toUpperCase(Locale.ROOT) + "%";
        String tableJdbc  = tableFilter.isEmpty()  ? "%" : "%" + tableFilter.toUpperCase(Locale.ROOT) + "%";
        searchInMemory(ownerFilter, tableFilter, columnFilter, loadSchema(db, schemaJdbc, tableJdbc));
    }

    private static void searchInMemory(String ownerFilter, String tableFilter, String columnFilter,
            Map<String, Map<String, Map<String, String>>> dbSpec) {
        final String ownerQ  = replaceNull(replaceWildcard(ownerFilter)).toLowerCase(Locale.ROOT);
        final String tableQ  = replaceNull(replaceWildcard(tableFilter)).toLowerCase(Locale.ROOT);
        final String columnQ = replaceNull(replaceWildcard(columnFilter)).toLowerCase(Locale.ROOT);

        dbSpec.forEach((schema, tableMap) -> {
            if (schema.toLowerCase(Locale.ROOT).contains(ownerQ)) {
                tableMap.forEach((tableName, columns) -> {
                    if (tableName.toLowerCase(Locale.ROOT).contains(tableQ)) {
                        for (String columnName : columns.keySet()) {
                            if (columnName.toLowerCase(Locale.ROOT).contains(columnQ)) {
                                printTable(schema, tableName, columns);
                                break;
                            }
                        }
                    }
                });
            }
        });
    }

    private static void searchAll(String ownerFilter, String tableFilter, String columnFilter,
            Map<String, Map<String, String>> ini, boolean verbose) {
        for (Map.Entry<String, Map<String, String>> entry : ini.entrySet()) {
            String name = entry.getKey();
            ConnConfig conn = fromIniSection(entry.getValue(), name);
            System.out.printf("%n[%s]%n", name);
            if (verbose) System.out.printf("  %s%n", conn.url());
            try (Connection db = conn.connect()) {
                searchLive(ownerFilter, tableFilter, columnFilter, db);
            } catch (SQLException e) {
                System.err.printf("Failed to connect to '%s': %s%n", name, e.getMessage());
            }
        }
    }

    private static void printTable(String schema, String tableName, Map<String, String> columns) {
        System.out.printf("%nTABLE %s.%s%n", schema, tableName);
        columns.forEach((col, type) -> System.out.printf(" - %s (%s)%n", col, type));
    }

    /** Parses an INI file into section name -> (key -> value). */
    private static Map<String, Map<String, String>> parseIni(Path path) throws IOException {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (!Files.exists(path)) return result;
        String section = null;
        for (String line : Files.readAllLines(path)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).strip();
                result.putIfAbsent(section, new LinkedHashMap<>());
            } else if (section != null && line.contains("=")) {
                int eq = line.indexOf('=');
                result.get(section).put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return result;
    }

    private static Path repoRoot() {
        try {
            Path jarDir = Path.of(DbSearch.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            return jarDir.getParent();
        } catch (Exception e) {
            throw new RuntimeException("Failed to locate repo root relative to application", e);
        }
    }

    private record ConnConfig(String url, String user, String password, String cachePath) {
        Connection connect() throws SQLException {
            Properties props = new Properties();
            props.put("user", user);
            props.put("password", password);
            return DriverManager.getConnection(url, props);
        }
    }

    private static ConnConfig fromIniSection(Map<String, String> section, String name) {
        String url = section.get("url");
        if (url == null) {
            System.err.printf("Connection '%s' in connections.ini is missing 'url'%n", name);
            System.exit(1);
        }
        return new ConnConfig(
                url,
                section.getOrDefault("user", ""),
                section.getOrDefault("password", ""),
                section.getOrDefault("cache", "db.ser"));
    }

    private static ConnConfig resolveConnection(Map<String, Map<String, String>> ini, String connName) {
        String key = connName != null ? connName : "default";
        Map<String, String> section = ini.get(key);
        if (section == null) {
            if (connName != null)
                System.err.printf("Unknown connection '%s'. Add a [%s] section to connections.ini%n", connName, connName);
            else
                System.err.println("No [default] section in connections.ini. Add one or use --conn <name>.");
            System.exit(1);
        }
        return fromIniSection(section, key);
    }

    private static void listConnections(Map<String, Map<String, String>> ini) {
        if (ini.isEmpty()) {
            System.out.println("No connections configured. Create connections.ini from connections.ini.example.");
            return;
        }
        for (Map.Entry<String, Map<String, String>> entry : ini.entrySet()) {
            String name  = entry.getKey();
            String url   = entry.getValue().getOrDefault("url", "(no url)");
            String label = name.equals("default") ? "(default)" : "--conn " + name;
            System.out.printf("  %-20s  %s%n", label, url);
        }
    }

    private static String replaceNull(String s) {
        return s == null ? "" : s;
    }

    private static String replaceWildcard(String s) {
        return s.trim().equals("%") ? "" : s;
    }

    private static boolean isSystemSchema(String schema) {
        String s = schema.toUpperCase(Locale.ROOT);
        return s.equals("PG_CATALOG") || s.equals("INFORMATION_SCHEMA") || s.equals("SYS") || s.equals("SYSTEM")
               || s.startsWith("SYS_") || s.equals("CTXSYS") || s.equals("XDB") || s.equals("MDSYS")
               || s.equals("ORDSYS");
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  dbsearch [--conn <name>] load");
        System.out.println("  dbsearch [--conn <name> | --all] [-v] [--live] <owner> <table> <column>");
        System.out.println("  dbsearch connections");
    }

    public static void main(String[] args) throws SQLException, IOException, ClassNotFoundException {
        Map<String, Map<String, String>> ini = parseIni(repoRoot().resolve("connections.ini"));

        String connName = null;
        boolean live = false;
        boolean verbose = false;
        boolean all = false;
        int idx = 0;
        while (idx < args.length && args[idx].startsWith("-")) {
            switch (args[idx]) {
                case "--conn"          -> connName = args[++idx];
                case "--live"          -> live = true;
                case "--verbose", "-v" -> verbose = true;
                case "--all"           -> all = true;
                default -> { System.err.println("Unknown flag: " + args[idx]); printUsage(); System.exit(1); }
            }
            idx++;
        }

        if (all && connName != null) {
            System.err.println("--all and --conn are mutually exclusive.");
            System.exit(1);
        }

        String[] rest = Arrays.copyOfRange(args, idx, args.length);

        if (rest.length == 0) {
            printUsage();
            return;
        }

        if (rest[0].equals("connections")) {
            listConnections(ini);
            return;
        }

        if (rest[0].equals("load")) {
            ConnConfig conn = resolveConnection(ini, connName);
            long start = System.currentTimeMillis();
            try (Connection db = conn.connect()) {
                var dbSpec = loadSchema(db, "%", "%");
                File cacheFile = new File(conn.cachePath());
                save(dbSpec, cacheFile);
                saveMeta(cacheFile, conn.url());
            }
            System.out.printf("Loaded in %d ms.%n", System.currentTimeMillis() - start);
            return;
        }

        if (rest.length < 3) {
            printUsage();
            return;
        }

        String ownerFilter  = rest[0].trim();
        String tableFilter  = rest[1].trim();
        String columnFilter = rest[2].trim();
        System.out.println(ownerFilter);
        System.out.println(tableFilter);
        System.out.println(columnFilter);

        if (all) {
            if (ini.isEmpty()) {
                System.err.println("No connections configured. Create connections.ini from connections.ini.example.");
                System.exit(1);
            }
            searchAll(ownerFilter, tableFilter, columnFilter, ini, verbose);
        } else if (live) {
            ConnConfig conn = resolveConnection(ini, connName);
            if (verbose) System.out.printf("[Live] %s%n", conn.url());
            try (Connection db = conn.connect()) {
                searchLive(ownerFilter, tableFilter, columnFilter, db);
            }
        } else {
            ConnConfig conn = resolveConnection(ini, connName);
            File cacheFile = new File(conn.cachePath());
            if (!cacheFile.exists()) {
                System.out.printf("Cache not found: %s%nRun 'load' first or use --live.%n", cacheFile);
                return;
            }
            search(ownerFilter, tableFilter, columnFilter, cacheFile, verbose);
        }
    }
}
