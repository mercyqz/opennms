package org.opennms.install;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
  Big To-dos:
    - Fix all of the XXX items (some coding, some discussion)
    - Change the Exceptions to something more reasonable
    - Do exception handling where it makes sense (give users reasonable error
      messages for common problems)
    - Add a friendly startup script?
    - Javadoc
 */

public class Installer {
    String m_version = "0.7";
    String m_revision = "1";

    boolean m_rpm = false; // XXX only prints out a diagnostic message

    String m_opennms_home = null;

    boolean m_update_database = false;
    boolean m_do_inserts = false;
    boolean m_update_iplike = false;
    boolean m_install_webapp = false;

    boolean m_force = false;

    boolean m_debug = false;

    String m_pg_driver = null;
    String m_pg_url = null;
    String m_pg_user = "postgres";
    String m_pg_pass = "";
    String m_user = null;
    String m_pass = null;
    String m_database = null;

    String m_sql_dir = null;
    String m_create_sql = null;
    String m_pg_iplike = null;
    String m_tomcat_conf = null;
    String m_server_xml = null;
    String m_webappdir = null;
    String m_tomcatserverlibdir = null;
    String m_install_servletdir = null;

    String m_tomcat_serverlibs = null;

    HashMap m_seqmapping = new HashMap();
    LinkedList m_tables = new LinkedList();
    LinkedList m_sequences = new LinkedList();
    // LinkedList m_cfunctions = new LinkedList(); // Unused, not in create.sql
    // LinkedList m_functions = new LinkedList(); // Unused, not in create.sql
    // LinkedList m_languages = new LinkedList(); // Unused, not in create.sql
    LinkedList m_indexes = new LinkedList();
    HashMap m_inserts = new HashMap();
    HashSet m_drops = new HashSet();
    HashSet m_changed = new HashSet();

    String m_cascade = "";
    LinkedList m_sql_l = new LinkedList();
    String m_sql;

    Properties m_properties = null;
    Connection m_dbconnection;
    Map m_dbtypes = null;

    String m_required_options = "At least one of -d, -i, -s, -y, -S, " +
	"or -T is required.";

    public void install(String[] argv) throws Exception {
	printHeader();
	loadProperties();
	parseArguments(argv);


	if (!m_update_database &&
	    !m_do_inserts &&
	    !m_update_iplike &&
	    m_tomcat_conf == null &&
	    m_server_xml == null &&
	    !m_install_webapp) {
	    throw new Exception("Nothing to do.\n" + m_required_options +
				"\nUse '-h' for help.");
	}
	    
	checkJava();
	// XXX Check Tomcat version?

	if (m_update_database || m_update_iplike) {
	    databaseConnect("template1");
	    databaseCheckVersion();
	}

	printDiagnostics();

	verifyFilesAndDirectories();

	if (m_update_database) {
	    readTables();

	    // XXX Check and optionally modify pg_hba.conf

	    if (!databaseUserExists()) {
		databaseAddUser();
	    }
	    if (!databaseDBExists()) {
		databaseAddDB();
	    }
	}

	if (m_update_database || m_update_iplike) {
	    databaseDisconnect();
	    
	    databaseConnect(m_database);
	}

	if (m_update_database) {
	    createSequences();
	    createTables();
	    createIndexes();
	    // createFunctions(m_cfunctions); // Unused, not in create.sql
	    // createLanguages(); // Unused, not in create.sql
	    // createFunctions(m_functions); // Unused, not in create.sql
	    
	    fixData();
	    insertData();

	    checkUnicode();
	}

	if (m_install_webapp) {
	    installWebApp();
	}

	if (m_tomcat_conf != null) {
	    updateTomcatConf();
	}
       
	if (m_server_xml != null) {
	    updateServerXml();
	}
	
	if (m_update_iplike) {
	    updateIplike();
	}

	if (m_update_database) {
	    // XXX should we be using createFunctions and createLanguages
	    //     instead?
	    updatePlPgsql();
       
	    // XXX should we be using createFunctions instead?
	    addStoredProcedures();
	}

	if (m_update_database || m_update_iplike) {
	    databaseDisconnect();
	}
    }

    public void printHeader() {
	System.out.println("===============================================" +
			   "===============================");
	System.out.println("OpenNMS Installer Version " + m_version +
			   " (Revision " + m_revision +")");
	System.out.println("===============================================" +
			   "===============================");
	System.out.println("");
	System.out.println("Configures PostgreSQL tables, users, and other " +
			   "miscellaneous settings.");
	System.out.println("");
    }

    public void loadProperties() throws Exception {
	m_properties = new Properties();
	m_properties.load(Installer.class.getResourceAsStream("installer.properties"));

	/* Do this if we want to merge our properties with the system
	   properties...
	*/
	Properties sys = System.getProperties();
	m_properties.putAll(sys);

	m_opennms_home = fetchProperty("install.dir");
	m_database = fetchProperty("install.database.name");
	m_user = fetchProperty("install.database.user");
	m_pass = fetchProperty("install.database.password");
	m_pg_driver = fetchProperty("install.database.driver");
	m_pg_url = fetchProperty("install.database.url");
	m_sql_dir = fetchProperty("install.etc.dir");
	m_install_servletdir = fetchProperty("install.servlet.dir");
	m_tomcat_serverlibs = fetchProperty("install.tomcat.serverlibs");

	String soext = fetchProperty("build.soext");
	String pg_iplike_dir = fetchProperty("install.postgresql.dir");

	m_pg_iplike = pg_iplike_dir + File.separator + "iplike." + soext;
	m_create_sql = m_sql_dir + File.separator + "create.sql";
    }

    public String fetchProperty(String property) throws Exception {
	String value;

	if ((value = m_properties.getProperty(property)) == null) {
	    throw new Exception("property \"" + property + "\" not set " +
				"from bundled installer.properties file");
	}

	return value;
    }

    public void parseArguments(String[] argv) throws Exception {
	LinkedList args = new LinkedList();

	for (int i = 0; i < argv.length; i++) {
	    StringBuffer b = new StringBuffer(argv[i]);
	    boolean is_arg = false;

	    while (b.length() > 0 && b.charAt(0) == '-') {
		is_arg = true;
		b.deleteCharAt(0);
	    }

	    if (is_arg) {
		while (b.length() > 0) {
		    char c = b.charAt(0);
		    b.deleteCharAt(0);

		    switch (c) {
		    case 'h':
			printHelp();
			break;

		    case 'c':
			m_force = true;
			break;

		    case 'd':
			m_update_database = true;
			break;
		       
		    case 'i':
			m_do_inserts = true;
			break;

		    case 's':
			m_update_iplike = true;
			break;

		    case 'u':
			i++;
			m_pg_user = getNextArg(argv, i, 'u');
			break;

		    case 'p':
			i++;
			m_pg_pass = getNextArg(argv, i, 'p');
			break;

		    case 'y':
			m_install_webapp = true;
			break;

		    case 'T':
			i++;
			m_tomcat_conf = getNextArg(argv, i, 'T');
			break;

		    case 'S':
			i++;
			m_server_xml = getNextArg(argv, i, 'S');
			break;

		    case 'w':
			i++;
			m_webappdir = getNextArg(argv, i, 'w');
			break;

		    case 'W':
			i++;
			m_tomcatserverlibdir = getNextArg(argv, i, 'W');
			break;

		    case 'x':
			m_debug = true;
			break;

		    case 'r':
			m_rpm = true;
			break;

		    default:
			throw new Exception("unknown option '" + c + "'" +
					    ", use '-h' option for usage");
		    }
		}
	    } else {
		args.add(argv[i]);
	    }
	}

	if (args.size() != 0) {
	    throw new Exception("too many command-line arguments specified");
	}
    }

    public String getNextArg(String[] argv, int i, char letter) 
	throws Exception {
	if (i >= argv.length) {
	    throw new Exception("no argument provided for '" + letter +
				"' option");
	}
	if (argv[i].charAt(0) == '-') {
	    throw new Exception("argument to '" + letter + "' option looks " +
				"like another option (begins with a dash)");
	}
	return argv[i];
    }

    // Mac OS X: http://developer.apple.com/technotes/tn2002/tn2110.html
    public void checkJava() throws Exception {
	String javaVersion = System.getProperty("java.version");
	if (!javaVersion.startsWith("1.4")) {
	    throw new Exception("Need a Java version of 1.4 or higher.  " +
				"You have " + javaVersion);
	}
	// XXX if we only run properly on Sun Java at this time, should
	// XXX we check for that?  Check for specific release verisions?
    }

    public void printDiagnostics() {
	System.out.println("* using '" + m_user + "' as the PostgreSQL " +
			   "user for OpenNMS");
	System.out.println("* using '" + m_pass + "' as the PostgreSQL " +
			   "password for OpenNMS");
	System.out.println("* using '" + m_database + "' as the PostgreSQL " +
			   "database name for OpenNMS");
	if (m_rpm) {
	    System.out.println("* I am being called from an RPM install");
	}
    }

    public void readTables() throws Exception {
	BufferedReader r = new BufferedReader(new FileReader(m_create_sql));
	String line;

	Pattern seqmappingPattern = Pattern.compile("\\s*--#\\s+install:\\s*" +
						    "(\\S+)\\s+(\\S+)\\s+" +
						    "(\\S+)\\s*.*");
	Pattern createPattern = Pattern.compile("(?i)\\s*create\\b.*");
	Pattern insertPattern = Pattern.compile("(?i)INSERT INTO " +
						"[\"']?([\\w_]+)[\"']?.*");
	Pattern dropPattern = Pattern.compile("(?i)DROP TABLE [\"']?" +
					      "([\\w_]+)[\"']?.*");

	while ((line = r.readLine()) != null) {
	    Matcher m;

	    if (line.matches("\\s*") || line.matches("\\s*\\\\.*")) {
		continue;
	    }

	    m = seqmappingPattern.matcher(line);
	    if (m.matches()) {
		String[] a =  { m.group(2), m.group(3) };
		m_seqmapping.put(m.group(1), a);
		continue;
	    }

	    if (line.matches("--.*")) {
		continue;
	    }

	    if (createPattern.matcher(line).matches()) {
		m = Pattern.compile("(?i)\\s*create\\s+((?:unique )?\\w+)" +
				    "\\s+[\"']?(\\w+)[\"']?.*").matcher(line);
		if (m.matches()) {
		    String type = m.group(1);
		    String name = m.group(2).
			replaceAll("^[\"']", "").
			replaceAll("[\"']$", "");
		    
		    if (type.toLowerCase().indexOf("table") != -1) {
			m_tables.add(name);
		    } else if (type.toLowerCase().indexOf("sequence") != -1) {
			m_sequences.add(name);
		    /* -- Not used, nothing in create.sql to get us here
		    } else if (type.toLowerCase().indexOf("function") != -1) {
			if (type.toLowerCase().indexOf("language 'c'") != -1) {
			    m_cfunctions.add(name);
			} else {
			    m_functions.add(name);
			}
		    } else if (type.toLowerCase().indexOf("trusted") != -1) {
			m = Pattern.compile("(?i)\\s*create\\s+trutsed " +
					    "procedural language\\s+[\"']?" +
					    "(\\w+)[\"']?.*").matcher(line);
			if (!m.matches()) {
			    throw new Exception("Could not match name and " +
						"type of the trusted " +
						"procedural language in this" +
						"line: " + line);
			}
			m_languages.add(m.group(1));
		    */
		    } else if (type.toLowerCase().matches(".*\\bindex\\b.*")) {
			m = Pattern.compile("(?i)\\s*create\\s+(?:unique )?" +
					    "index\\s+[\"']?([\\w_]+)" +
					    "[\"']?.*").matcher(line);
			if (!m.matches()) {
			    throw new Exception("Could not match name and " +
						"type of the index " +
						"in this" +
						"line: " + line);
			}
			m_indexes.add(m.group(1));
		    } else {
			throw new Exception("Unknown CREATE encountered: " +
					    "CREATE " + type + " " + name);
		    }
		} else {
		    throw new Exception("Unknown CREATE encountered: " + line);
		}

		m_sql_l.add(line);
		continue;
	    }

	    m = insertPattern.matcher(line);
	    if (m.matches()) {
		String table = m.group(1);
		if (!m_inserts.containsKey(table)) {
		    m_inserts.put(table, new LinkedList());
		}
		((LinkedList) m_inserts.get(table)).add(line);

		continue;
	    }
 
	    if (line.toLowerCase().startsWith("select setval ")) {
		String table = "select_setval";
		if (!m_inserts.containsKey(table)) {
		    m_inserts.put(table, new LinkedList());
		}
		((LinkedList) m_inserts.get(table)).add(line);

		m_sql_l.add(line);
		continue;
	    }

	    m = dropPattern.matcher(line);
	    if (m.matches()) {
		m_drops.add(m.group(1));
		
		m_sql_l.add(line);
		continue;
	    }

	    // XXX should do something here to we can catch what we can't parse
	    // System.out.println("unmatched line: " + line);

	    m_sql_l.add(line);
	}
	r.close();

	m_sql = cleanText(m_sql_l);
    }

    public void databaseConnect(String database) throws Exception {
	Class.forName(m_pg_driver);	
	m_dbconnection = DriverManager.getConnection(m_pg_url + database,
						     m_pg_user,
						     m_pg_pass);
    }

    public void databaseDisconnect() throws Exception {
	if (m_dbconnection != null) {
	    m_dbconnection.close();
	}
    }

    public void databaseCheckVersion() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs = st.executeQuery("SELECT version()");
	if (!rs.next()) {
	    throw new Exception("Database didn't return any rows for " +
				"'SELECT version()'");
	}

	String versionString = rs.getString(1);

	rs.close();
	st.close();

	Matcher m = Pattern.compile("^PostgreSQL (\\d+\\.\\d+)").
	    matcher(versionString);

	if (!m.find()) {
	    throw new Exception("Could not parse version number out of " +
				"version string: " + versionString);
	}
	String version = m.group(1);
	float version_f = Float.parseFloat(version);

	if (version_f >= 7.3) {
	    m_cascade = " CASCADE";
	}
    }

    public boolean databaseUserExists() throws Exception {
	boolean exists;

	Statement st = m_dbconnection.createStatement();
	ResultSet rs = st.executeQuery("SELECT usename FROM pg_user WHERE " +
				       "usename = '" + m_user + "'");
	
	exists = rs.next();

	rs.close();
	st.close();

	return exists;
    }

    public void databaseAddUser() throws Exception {
	Statement st = m_dbconnection.createStatement();
	st.execute("CREATE USER " + m_user +
		   " WITH PASSWORD '" + m_pass +
		   "' CREATEDB CREATEUSER");
    }

    public boolean databaseDBExists() throws Exception {
	boolean exists;

	Statement st = m_dbconnection.createStatement();
	ResultSet rs = st.executeQuery("SELECT datname from pg_database " +
				       "WHERE datname = '" + m_database + "'");

	exists = rs.next();

	rs.close();
	st.close();

	return exists;
    }

    public void databaseAddDB() throws Exception {
	Statement st = m_dbconnection.createStatement();
	st.execute("CREATE DATABASE " + m_database +
				  " WITH ENCODING='UNICODE'");
    }

    public void createSequences() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	System.out.println("- creating sequences... ");

	Iterator i = m_sequences.iterator();
	while (i.hasNext()) {
	    String sequence = (String) i.next();
	    if (!m_seqmapping.containsKey(sequence)) {
		throw new Exception("Cannot find sequence mapping for " +
				    sequence);
	    }
	    String[] mapping = (String[]) m_seqmapping.get(sequence);
	}

	i = m_sequences.iterator();
	while (i.hasNext()) {
	    String sequence = (String) i.next();
	    String[] mapping = (String[]) m_seqmapping.get(sequence);
	    int minvalue = 1;
	    boolean remove;

	    System.out.print("  - checking \"" + sequence +
			       "\" minimum value... ");

	    try {
		rs = st.executeQuery("SELECT MAX(" + mapping[0] +
				     ") AS max FROM " + mapping[1]);

		if (rs.next()) {
		    minvalue = rs.getInt(1) + 1;
		}
	    } catch (SQLException e) {
		if (e.toString().indexOf("does not exist") == -1) {
		    throw new Exception(e);
		}
	    }

	    System.out.println(Integer.toString(minvalue));
	    
	    System.out.print("  - removing sequence \"" + sequence + "\"... ");

	    rs = st.executeQuery("SELECT relname FROM pg_class " +
				 "WHERE relname = '" +
				 sequence.toLowerCase() + "'");

	    remove = rs.next();
	    if (remove) {
		st.execute("DROP SEQUENCE " + sequence);
		System.out.println("REMOVED");
	    } else {
		System.out.println("CLEAN");
	    }

	    System.out.print("  - creating sequence \"" + sequence + "\"... ");
	    st.execute("CREATE SEQUENCE " + sequence + " minvalue " +
		       minvalue);
	    st.execute("GRANT ALL on " + sequence + " TO " + m_user);
	    System.out.println("OK");
	}

	System.out.println("- creating sequences... DONE");
    }

    public void createTables() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;
	Iterator i = m_tables.iterator();

	System.out.println("- creating tables...");
       
	while (i.hasNext()) {
	    String table = (String) i.next();

	    if (m_force) {
		table = table.toLowerCase();

		String create = getTableFromSQL(table);

		boolean remove;

		rs = st.executeQuery("SELECT relname FROM pg_class " +
				     "WHERE relname = '" + table + "'");

		remove = rs.next();

		System.out.print("  - removing old table... ");
		if (remove) {
		    st.execute("DROP TABLE " + table + m_cascade);
		    System.out.println("REMOVED");
		} else {
		    System.out.println("CLEAN");
		}

		System.out.print("  - creating table \"" + table + "\"... ");
		st.execute("CREATE TABLE " + table + " (" + create + ")");
		System.out.println("CREATED");

		System.out.print("  - giving \"" + m_user +
				 "\" permissions on \"" + table + "\"... ");
		st.execute("GRANT ALL ON " + table + " TO " + m_user);
		System.out.println("GRANTED");
	    } else {
		System.out.print("  - checking table \"" + table + "\"... ");

		table = table.toLowerCase();

		List newColumns = getTableColumnsFromSQL(table);
		List oldColumns = getTableColumnsFromDB(table);

		if (newColumns.equals(oldColumns)) {
		    System.out.println("UPTODATE");
		} else {
		    if (oldColumns.size() == 0) {
			String create = getTableFromSQL(table);
			st.execute("CREATE TABLE " + table +
				   " (" + create + ")");
			st.execute("GRANT ALL ON " + table + " TO " + m_user);
			System.out.println("CREATED");
		    } else {
			changeTable(table, oldColumns, newColumns);
		    }
		}
	    }
	}

	System.out.println("- creating tables... DONE");
    }

    public void createIndexes() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	System.out.println("- creating indexes...");

	Iterator i = m_indexes.iterator();
	while (i.hasNext()) {
	    String index = (String) i.next();
	    boolean exists;

	    System.out.print("  - creating index \"" + index + "\"... ");

	    rs = st.executeQuery("SELECT relname FROM pg_class " +
				 "WHERE relname = '" +
				 index.toLowerCase() + "'");

	    exists = rs.next();

	    if (exists) {
		System.out.println("EXISTS");
	    } else {
		st.execute(getIndexFromSQL(index));
		System.out.println("OK");
	    }
	}

	System.out.println("- creating indexes... DONE");
    }

    public Map getTypesFromDB() throws SQLException {
	if (m_dbtypes != null) {
	    return m_dbtypes;
	}

	Statement st = m_dbconnection.createStatement();
	ResultSet rs;
	HashMap m = new HashMap();

	rs = st.executeQuery("SELECT oid,typname,typlen FROM pg_type");

	while (rs.next()) {
	    try {
		m.put(Column.normalizeColumnType(rs.getString(2),
						  (rs.getInt(3) < 0)),
		      new Integer(rs.getInt(1)));
	    } catch (Exception e) {
		// ignore
	    }
	}

	m_dbtypes = m;
	return m_dbtypes;
    }

    /* -- Not used, nothing in create.sql...
    public void createFunctions(List functions) throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;
       
	Iterator i = functions.iterator();
	while (i.hasNext()) {
	    String function = (String) i.next();
	    String functionSql = getFunctionFromSQL(function);
	    Matcher m =
		Pattern.compile("\\s*\\((.+?)\\).*").matcher(functionSql);
	    String columns = m.group(1);

	    if (m_force) {
		// XXX this doesn't check to see if the function exists
		//     before it drops it, so it will fail and throw an
		//     exception if the function doesn't exist.
		System.out.print("- removing function \"" + function +
				 "\" if it exists... ");
		String dropSql = "DROP FUNCTION \"" + function + "\" (" +
		    columns + ");";
		st.execute(dropSql);
		System.out.println("REMOVED");
	    }

	    // XXX this doesn't check to see if the function exists before
	    //     it tries to create it, so it will fail and throw an
	    //     exception if the function does exist.
	    System.out.print("- creating function \"" + function +
			     "\"... ");
	    st.execute("CREATE FUNCTION \"" + function + "\" " + functionSql);
	    System.out.println("OK");
	}
    }

    public void createLanguages() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	Iterator i = m_languages.iterator();
	while (i.hasNext()) {
	    String language = (String) i.next();
	    String languageSql = getLanguageFromSQL(language);

	    // XXX this doesn't check to see if the language exists before
	    //     it tries to create it, so it will fail and throw an
	    //     exception if the language does already exist.
	    System.out.print("- creating language reference \"" + language +
			     "\"... ");
	    st.execute("CREATE TRUSTED PROCEDURAL LANGUAGE '" +
		       language + "' " + languageSql);
	    System.out.println("OK");
	}
    }
    */

    public void fixData() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	st.execute("UPDATE ipinterface SET issnmpprimary='N' " +
		   "WHERE issnmpprimary IS NULL");
	st.execute("UPDATE service SET servicename='SSH' " +
		   "WHERE servicename='OpenSSH'");
	st.execute("UPDATE snmpinterface SET snmpipadentnetmask=NULL");
    }

    // XXX This causes the following Postgres error:
    //      ERROR:  duplicate key violates unique constraint "pk_dpname"
    void insertData() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	if (!m_do_inserts) {
	    return;
	}

	for (Iterator i = m_inserts.keySet().iterator(); i.hasNext(); ) {
	    String table = (String) i.next();
	    boolean exists = false;

	    System.out.print("- inserting initial table data for \"" +
			       table + "\"... ");

	    for (Iterator j = ((LinkedList) m_inserts.get(table)).iterator();
		 j.hasNext(); ) {
		try {
		    st.execute((String) j.next());
		} catch (SQLException e) {
		    if (e.toString().indexOf("duplicate key") != -1) {
			exists = true;
		    } else {
			throw new SQLException(e.toString());
		    }
		}
	    }

	    if (exists) {
		System.out.println("EXISTS");
	    } else {
		System.out.println("OK");
	    }
	}
    }

    // XXX Should this all be in Java, instead of calling
    //      convert_db_to_unicode.sh?
    /* 
       XXX This doesn't work reliably.  Here are two errors:

    - dumping data to /tmp/pg_dump-opennms... ok
    - dropping old database... failed
    Exception in thread "main" java.lang.Exception: convert_db_to_unicode.sh returned non-zero exit value: 20
        at org.opennms.install.Installer.checkUnicode(Installer.java:917)
        at org.opennms.install.Installer.install(Installer.java:165)
        at org.opennms.install.Installer.main(Installer.java:1985)

    - dumping data to /tmp/pg_dump-opennms... ok
    - dropping old database... ok
    - creating new unicode database... failed
    Exception in thread "main" java.lang.Exception: convert_db_to_unicode.sh returned non-zero exit value: 30
        at org.opennms.install.Installer.checkUnicode(Installer.java:917)
        at org.opennms.install.Installer.install(Installer.java:165)
        at org.opennms.install.Installer.main(Installer.java:1985)

    ... and their errors in /tmp/unicode-convert.log:

	Sun Jul 11 03:16:26 EDT 2004 dumping data to /tmp/pg_dump-opennms
	Sun Jul 11 03:16:26 EDT 2004 dropping old database
	dropdb: database removal failed: ERROR:  database "opennms" is being accessed by other users

	Sun Jul 11 03:18:14 EDT 2004 dumping data to /tmp/pg_dump-opennms
	Sun Jul 11 03:18:15 EDT 2004 dropping old database
	DROP DATABASE
	Sun Jul 11 03:18:15 EDT 2004 creating new unicode database
	createdb: database creation failed: ERROR:  source database "template1" is being accessed by other users
     */
    public void checkUnicode() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	System.out.print("- checking if database \"" + m_database +
			 "\" is unicode... ");
	
	rs = st.executeQuery("SELECT encoding FROM pg_database WHERE " +
			     "datname='" + m_database.toLowerCase() + "'");
	rs.next();
	if (rs.getInt(1) == 5 || rs.getInt(1) == 6) {
	    System.out.println("ALREADY UNICODE");
	    return;
	}

	System.out.println("NOT UNICODE, CONVERTING");

	databaseDisconnect();

	String[] cmd = {
	    m_opennms_home + "/bin/convert_db_to_unicode.sh", 
	    m_pg_user,
	    m_user,
	    m_database,
	    m_sql_dir + File.separator + "create.sql"
	};

	ProcessExec e = new ProcessExec();
	int exitVal;
	if ((exitVal = e.exec(cmd)) != 0) {
	    throw new Exception("convert_db_to_unicode.sh returned " +
				"non-zero exit value: " + exitVal);
	}

	databaseConnect(m_database);
    }

    public void verifyFilesAndDirectories() throws FileNotFoundException {
	if (m_update_database) {
	    verifyFileExists(true,
			     m_sql_dir,
			     "SQL directory",
			     "install.etc.dir property");

	    verifyFileExists(false,
			     m_create_sql,
			     "create.sql",
			     "install.etc.dir property");
	}

	if (m_update_iplike) {
	    verifyFileExists(false,
			     m_pg_iplike,
			     "iplike module",
			     "install.postgresql.dir property");
	}

	if (m_tomcat_conf != null) {
	    verifyFileExists(false,
			     m_tomcat_conf,
			     "Tomcat startup configuration file tomcat4.conf",
			     "-T option");
	}

	if (m_server_xml != null) {
	    verifyFileExists(false,
			     m_server_xml,
			     "Tomcat server.xml",
			     "-S option");
	}

	if (m_install_webapp) {
	    verifyFileExists(true,
			     m_webappdir,
			     "Top-level web application directory",
			     "-w option");

	    verifyFileExists(true,
			     m_tomcatserverlibdir,
			     "Tomcat server library directory",
			     "-W option");

	    verifyFileExists(true,
			     m_install_servletdir,
			     "OpenNMS servlet directory",
			     "install.servlet.dir property");
	}
    }

    public static void verifyFileExists(boolean isDir,
					String file,
					String description,
					String option)
	throws FileNotFoundException {
	File f;

	if (file == null) {
	    throw new FileNotFoundException("The user most provide the " +
					    "location of " + description +
					    ", but this is not specified.  " +
					    "Use the " + option +
					    " to specify this file.");
	}

	System.out.print("- using " + description + "... ");

	f = new File(file);

	if (!f.exists()) {
	    throw new FileNotFoundException(description +
					    " does not exist at \"" + file +
					    "\".  Use the " + option +
					    " to specify another location.");
	}

	if (!isDir) {
	    if (!f.isFile()) {
		throw new FileNotFoundException(description +
						" not a file at \"" + file +
						"\".  Use the " + option +
						" to specify another file.");
	    }
	} else {
	    if (!f.isDirectory()) {
		throw new FileNotFoundException(description +
						" not a directory at \"" +
						file + "\".  Use the " +
						option + " to specify " +
						"another directory.");
	    }
	}

	System.out.println(f.getAbsolutePath());
    }

    public void addStoredProcedures() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	System.out.print("- adding stored procedures... ");

	FileFilter sqlFilter = new FileFilter() {
		public boolean accept(File pathname) {
		    return (pathname.getName().startsWith("get") &&
			    pathname.getName().endsWith(".sql"));
		}
	    };

	File[] list = new File(m_sql_dir).listFiles(sqlFilter);

	for (int i = 0; i < list.length; i++) {
	    LinkedList drop = new LinkedList();
	    StringBuffer create = new StringBuffer();
	    String line;
	    
	    System.out.print("\n  - " + list[i].getName() + "... ");
	    
	    BufferedReader r = new BufferedReader(new FileReader(list[i]));
	    while ((line = r.readLine()) != null) {
		line = line.trim();

		if (line.matches("--.*")) {
		    continue;
		}

		if (line.toLowerCase().startsWith("drop function")) {
		    drop.add(line);
		} else {
		    create.append(line);
		    create.append("\n");
		}
	    }
	    r.close();

	    // XXX install.pl has code to drop the functions, however I think
	    //     it is buggy and it would have ever droopped a function.
	    //     The supposed buggy code is not implemented here.
	
	    Matcher m = Pattern.compile("(?is)\\bCREATE FUNCTION\\s+" +
					"(\\w+)\\s*\\((.+?)\\)\\s+" +
					"RETURNS\\s+(\\S+)\\s+AS\\s+" +
					"(.+? language ['\"]?\\w+['\"]?);").
		matcher(create.toString());

	    if (!m.find()) {
		throw new Exception("Could match \"" + m.pattern().pattern() +
				    "\" in string \"" + create + "\"");
	    }
	    String function = m.group(1);
	    String columns = m.group(2);
	    String returns = m.group(3);
	    String rest = m.group(4);

	    if (functionExists(function, columns, returns)) {
		if (m_force) {
		    st.execute("DROP FUNCTION " +
			       function + "(" + columns + ")");
		    st.execute(create.toString());
		    System.out.print("OK (dropped and re-added)");
		} else {
		    System.out.print("EXISTS");
		}
	    } else {
		st.execute(create.toString());
		System.out.print("OK");
	    }
	}
	System.out.println("");
    }

    public boolean functionExists(String function, String columns,
				  String returnType) throws Exception {
	Map types = getTypesFromDB();

	String[] splitColumns = columns.split(",");
	int[] columnTypes = new int[splitColumns.length];
	Column c;
	for (int j = 0; j < splitColumns.length; j++) {
	    c = new Column();
	    c.parseColumnType(splitColumns[j]);
	    columnTypes[j] = ((Integer)types.get(c.getType())).intValue();
	}

	c = new Column();
	c.parseColumnType(returnType);
	int retType = ((Integer)types.get(c.getType())).intValue();
	
	return functionExists(function, columnTypes, retType);
    }

    public boolean functionExists(String function, int[] columnTypes,
				  int retType) throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	StringBuffer ct = new StringBuffer();
	for (int j = 0; j < columnTypes.length; j++) {
	    ct.append(" " + columnTypes[j]);
	}

	rs = st.executeQuery("SELECT oid from pg_proc WHERE proname='" +
			     function.toLowerCase() + "' AND " +
			     "prorettype=" + retType + " AND " +
			     "proargtypes='" + ct.toString().trim() + "'");
	return rs.next();
    }

    public void installWebApp() throws Exception {
	String[] jars = m_tomcat_serverlibs.split(File.pathSeparator);

	System.out.println("- Install OpenNMS webapp... ");

	installLink(m_install_servletdir,
		    m_webappdir + File.separator + "opennms",
		    "web application directory", true);

	for (int i = 0; i < jars.length; i++) {
	    String source = ".." + File.separator + ".." + File.separator +
		"webapps" + File.separator + "opennms" +
		File.separator + "WEB-INF" + File.separator +
		"lib" + File.separator + jars[i];
	    String destination = m_tomcatserverlibdir + File.separator +
		jars[i];
	    installLink(source, destination, "jar file " + jars[i], false);
	}

	System.out.println("- Installing OpenNMS webapp... DONE");
    }

    public void installLink(String source, String destination, 
			    String description, boolean recursive)
	throws Exception {

	File f;
	String[] cmd;
	ProcessExec e = new ProcessExec();
	int exists;

	if (new File(destination).exists()) {
	    System.out.print("  - " + destination + " exists, removing... ");
	    if (recursive) {
		cmd = new String[3];
		cmd[0] = "rm";
		cmd[1] = "-r";
		cmd[2] = destination;
	    } else {
		cmd = new String[2];
		cmd[0] = "rm";
		cmd[1] = destination;
	    }
	    if (e.exec(cmd) != 0) {
		throw new Exception("Non-zero exit value returned while " +
				    "removing " + description + ", " +
				    destination + ", using \"" +
				    join(" ", cmd) + "\"");
	    }

	    if (new File(destination).exists()) {
		throw new Exception("Could not delete existing " +
				    description + ": " +
				    destination);
	    }

	    System.out.println("REMOVED");
	}
	
	System.out.print("  - creating link to " + destination + "... ");

	cmd = new String[4];
	cmd[0] = "ln";
	cmd[1] = "-sf";
	cmd[2] = source;
	cmd[3] = destination;
	
	if (e.exec(cmd) != 0) {
	    throw new Exception("Non-zero exit value returned while " +
				"linking " + description + ", " +
				source + " into " + destination);
	}

	System.out.println("DONE");
    }

    public void updateTomcatConf() throws Exception {
	File f = new File(m_tomcat_conf);

	// XXX give the user the option to set the user to something else?
	//     if so, should we chown the appropriate OpenNMS files to the
	//     tomcat user?
	//
	// XXX should we have the option to automatically try to determine
	//     the tomcat user and chown the OpenNMS files to that user?

	System.out.print("- setting tomcat4 user to 'root'... ");

	BufferedReader r = new BufferedReader(new FileReader(f));
	StringBuffer b = new StringBuffer();
	String line;

	while ((line = r.readLine()) != null) {
	    if (line.startsWith("TOMCAT_USER=")) {
		b.append("TOMCAT_USER=\"root\"\n");
	    } else {
		b.append(line);
		b.append("\n");
	    }
	}
	r.close();

	f.renameTo(new File(m_tomcat_conf + ".before-opennms-" +
			    System.currentTimeMillis()));

	f = new File(m_tomcat_conf);
	PrintWriter w = new PrintWriter(new FileOutputStream(f));

	w.print(b.toString());
	w.close();

	System.out.println("done");
    }

    public void updateServerXml() throws Exception {
	File f = new File(m_server_xml);

	System.out.print("- checking Tomcat 4 for OpenNMS web UI... ");

	BufferedReader r = new BufferedReader(new FileReader(f));
	StringBuffer b = new StringBuffer();
	String line;

	while ((line = r.readLine()) != null) {
	    b.append(line);
	    b.append("\n");
	}

	r.close();

	String server_in = b.toString();

	// XXX Can the next two patterns be made more specific?
	Matcher m = Pattern.compile("(?si)opennms").matcher(server_in);
	if (m.find()) {
	    m = Pattern.compile("(?s)homeDir").matcher(server_in);
	    if (m.find()) {
		System.out.println("FOUND");
	    } else {
		System.out.println("UPDATING");
		server_in = server_in.replaceAll("(?s)userFile\\s*=\\s*\".*?\"\\s*", "homeDir=" + m_opennms_home + "\" ");
		server_in = server_in.replaceAll("(?s)<Logger className=\"org.apache.catalina.logger.FileLogger\" prefix=\"localhost_opennms_log.\" suffix=\".txt\" timestamp=\"true\"/>", "<Logger className=\"org.opennms.web.log.Log4JLogger\" homeDir=\"" + m_opennms_home + "\" />");

		f.renameTo(new File(m_server_xml + ".before-opennms" +
				    System.currentTimeMillis()));

		f = new File(m_server_xml);
		PrintWriter w = new PrintWriter(new FileOutputStream(f));

		w.print(server_in);
		w.close();

		System.out.println("DONE");
	    }
	} else {
	    System.out.println("UPDATING");

	    String add = "\n" +
		"        <Context path=\"/opennms\" docBase=\"opennms\" debug=\"0\" reloadable=\"true\">\n" +
		"         <Logger className=\"org.opennms.web.log.Log4JLogger\" homeDir=\"" + m_opennms_home + "\"/>\n" +
		"         <Realm className=\"org.opennms.web.authenticate.OpenNMSTomcatRealm\" homeDir=\"" + m_opennms_home + "\"/>\n" +
		"        </Context>\n";
	    server_in = server_in.replaceAll("(?mi)^(.*)(</host>)",
					     add + "$1$2");

	    f.renameTo(new File(m_server_xml + ".before-opennms" +
				System.currentTimeMillis()));

	    f = new File(m_server_xml);
	    PrintWriter w = new PrintWriter(new FileOutputStream(f));

	    w.print(server_in);
	    w.close();

	    System.out.println("DONE");
	}
    }

    public void updateIplike() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	if (!m_update_iplike) {
	    return;
	}

	System.out.print("- checking for stale iplike references... ");
	try {
	    st.execute("DROP FUNCTION iplike(text,text)");
	    System.out.println("REMOVED");
	} catch (SQLException e) {
	    if (e.toString().indexOf("does not exist") != -1) {
		System.out.println("CLEAN");
	    } else {
		throw new SQLException(e.toString());
	    }
	}

	// XXX This error is generated from Postgres if eventtime(text)
	//     does not exist:
	//        ERROR:  function eventtime(text) does not exist
	System.out.print("- checking for stale eventtime.so references... ");
	try {
	    st.execute("DROP FUNCTION eventtime(text)");
	    System.out.println("REMOVED");
	} catch (SQLException e) {
	    if (e.toString().indexOf("does not exist") != -1) {
		System.out.println("CLEAN");
	    } else {
		throw new SQLException(e.toString());
	    }
	}

	System.out.print("- adding iplike database function... ");
	st.execute("CREATE FUNCTION iplike(text,text) RETURNS bool " +
		   "AS '" + m_pg_iplike +
		   "' LANGUAGE 'c' WITH(isstrict)");
	System.out.println("OK");
    }

    public void updatePlPgsql() throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;

	System.out.print("- adding PL/pgSQL call handler... ");
	rs = st.executeQuery("SELECT oid from pg_proc WHERE " +
			     "proname='plpgsql_call_handler' AND " +
			     "proargtypes = ''");
	if (rs.next()) {
	    System.out.println("EXISTS");
	} else {
	    st.execute("CREATE FUNCTION plpgsql_call_handler () " +
		       "RETURNS OPAQUE AS '$libdir/plpgsql.so' LANGUAGE 'c'");
	    System.out.println("OK");
	}

	System.out.print("- adding PL/pgSQL language module... ");
	rs = st.executeQuery("SELECT pg_language.oid FROM " +
			     "pg_language, pg_proc WHERE " +
			     "pg_proc.proname='plpgsql_call_handler' AND " +
			     "pg_proc.proargtypes = '' AND " +
			     "pg_proc.oid = pg_language.lanplcallfoid AND " +
			     "pg_language.lanname = 'plpgsql'");
	if (rs.next()) {
	    System.out.println("EXISTS");
	} else {
	    st.execute("CREATE TRUSTED PROCEDURAL LANGUAGE 'plpgsql' " +
		       "HANDLER plpgsql_call_handler LANCOMPILER 'PL/pgSQL'");
	    System.out.println("OK");
	}
    }

    public Column findColumn(List columns, String column) {
	Column c;

	for (Iterator i = columns.iterator(); i.hasNext(); ) {
	    c = (Column) i.next();
	    if (c.getName().equals(column.toLowerCase())) {
		return c;
	    }
	}

	return null;
    }

    public String getXFromSQL(String item, String regex, int itemGroup,
			      int returnGroup, String description)
	throws Exception {

	item = item.toLowerCase();
	Matcher m = Pattern.compile(regex).matcher(m_sql);

	while (m.find()) {
	    if (m.group(itemGroup).toLowerCase().equals(item)) {
		return m.group(returnGroup);
	    }
	}

	throw new Exception("could not find " + description +
			    " \"" + item + "\"");
    }

    public String getTableFromSQL(String table) throws Exception {
	return getXFromSQL(table,
			   "(?i)\\bcreate table\\s+['\"]?(\\S+)['\"]?" +
			   "\\s+\\((.+?)\\);", 1, 2, "table");
    }
    
    public String getIndexFromSQL(String index) throws Exception {
	return getXFromSQL(index, "(?i)\\b(create (?:unique )?index\\s+" +
			   "['\"]?(\\S+)['\"]?\\s+.+?);", 2, 1, "index");
    }
    
    public String getFunctionFromSQL(String function) throws Exception {
	return getXFromSQL(function, "(?is)\\bcreate function\\s+" +
			   "['\"]?(\\S+)['\"]?\\s+" +
			   "(.+? language ['\"]?\\w+['\"]?);", 1, 2,
			   "function");
    }
    
    public String getLanguageFromSQL(String language) throws Exception {
	return getXFromSQL(language,
			   "(?is)\\bcreate trusted procedural " +
			   "language\\s+['\"]?(\\S+)['\"]?\\s+(.+?);",
			   1, 2, "language");
    }

    public List getTableColumnsFromSQL(String table) throws Exception {
	String create = getTableFromSQL(table);
	LinkedList columns = new LinkedList();
	boolean parens = false;
	StringBuffer accumulator = new StringBuffer();
	Matcher m;

	for (int i = 0; i <= create.length(); i++) {
	    char c = ' ';

	    if (i < create.length()) {
		c = create.charAt(i);

		if (c == '(' || c == ')') {
		    parens = (c == '(');
		    accumulator.append(c);
		    continue;
		}
	    }

	    if (((c == ',') && !parens) || i == create.length()) {
		String a = accumulator.toString().trim();

		if (a.toLowerCase().startsWith("constraint ")) {
		    if (columns.size() == 0) {
			throw new Exception("found constraint with no " +
					    "previous column");
		    }

		    Constraint constraint = new Constraint(a);
		    Column lastcol = (Column) columns.getLast();
		    if (!constraint.getColumn().equals(lastcol.getName())) {
			throw new Exception("constraint does not " +
					    "reference previous column (" +
					    lastcol.getName() + "): " +
					    constraint);
		    }
		    lastcol.addConstraint(constraint);
		} else {
		    Column column = new Column();
		    column.parse(accumulator.toString());
		    columns.add(column);
		}

		accumulator = new StringBuffer();
	    } else {
		accumulator.append(c);
	    }
	}

	return columns;
    }

    public static String cleanText(List list) {
	StringBuffer s = new StringBuffer();
	Iterator i = list.iterator();

	while (i.hasNext()) {
	    String l = (String) i.next();

	    s.append(l.replaceAll("\\s+", " "));
	    if (l.indexOf(';') != -1) {
		s.append('\n');
	    }
	}

	return s.toString();
    }

    public List getTableColumnsFromDB(String table) throws Exception {
	Statement st = m_dbconnection.createStatement();
	ResultSet rs;
	LinkedList r = new LinkedList();

	rs = st.executeQuery("SELECT DISTINCT tablename FROM pg_tables " +
			     "WHERE lower(tablename) = '" +
			     table.toLowerCase() + "'");
	if (!rs.next()) {
	    return r;
	}

	String query = 
		"SELECT " +
		"        a.attname, " +
		"        format_type(a.atttypid, a.atttypmod), " +
		"        a.attnotnull, " +
		"        c.contype " +
		"FROM " +
		"        pg_constraint c RIGHT JOIN " +
		"                (pg_attribute a JOIN pg_type t ON " +
		"                        a.atttypid = t.oid) " +
		"                ON c.conrelid = a.attrelid AND " +
		"		   a.attnum = ANY(c.conkey) AND " +
		"		   c.contype = 'p' " +
		"		    " +
		"		 " +
		"WHERE " +
		"        a.attrelid = " +
		"                (SELECT oid FROM pg_class WHERE relname = '" +
	                         table + "') AND " +
		"        a.attnum > 0 AND " +
		"        a.attisdropped = false  " +
		"ORDER BY " +
		"        a.attnum;";

	rs = st.executeQuery(query);

	while (rs.next()) {
	    Column c = new Column();
	    c.setName(rs.getString(1));
	    c.parseColumnType(rs.getString(2));
	    c.setNotNull(rs.getBoolean(3));

	    String constraintType = rs.getString(4);
	    r.add(c);
	}

	query =
		"SELECT " +
		"       c.conname, " +
		"	c.contype, " +
		"	a.attname, " +
		"	d.relname, " +
		"	b.attname " +
		"FROM " +
		"	pg_class d RIGHT JOIN " +
		"	  (pg_attribute b RIGHT JOIN " +
		"	    (pg_constraint c JOIN pg_attribute a " +
		"	      ON c.conrelid = a.attrelid AND " +
		"	         a.attnum = ANY(c.conkey)) " +
		"	    ON c.confrelid = b.attrelid AND " +
		"	       b.attnum = ANY(c.confkey)) " +
		"	  ON b.attrelid = d.oid " +
		"WHERE " +
		"	a.attrelid = " +
	        "         (SELECT oid FROM pg_class WHERE relname = '" +
	                       table.toLowerCase() + "');";

	rs = st.executeQuery(query);

	while (rs.next()) {
	    Constraint constraint;
	    if (rs.getString(2).equals("p")) {
		constraint = new Constraint(rs.getString(1), rs.getString(3));
	    } else if (rs.getString(2).equals("f")) {
		constraint = new Constraint(rs.getString(1), rs.getString(3),
					    rs.getString(4), rs.getString(5));
	    } else {
		throw new Exception("Do not support constraint type \"" +
				    rs.getString(2) + "\" in constraint \"" +
				    rs.getString(1) + "\"");
	    }

	    Column c = findColumn(r, constraint.getColumn());
	    if (c == null) {
		throw new Exception("Got a constraint for column \"" +
				    constraint.getColumn() + "\" of table " +
				    table + ", but could not find column.  " +
				    "Constraint: " + constraint);
	    }

	    c.addConstraint(constraint);
	}

	return r;
    }

    public void changeTable(String table, List oldColumns, List newColumns)
	throws Exception {
	Statement st = m_dbconnection.createStatement();
	TreeMap columnChanges = new TreeMap();
	String[] oldColumnNames = new String[oldColumns.size()];

	int i;
	Iterator j;
      
	if (m_changed.contains(table)) {
	    return;
	}
	m_changed.add(table);

	System.out.println("SCHEMA DOES NOT MATCH");
	System.out.println("  - differences:");

	// XXX This doesn't check for old column rows that don't exist
	//     in newColumns.
	for (j = newColumns.iterator(); j.hasNext(); ) {
	    Column newColumn = (Column) j.next();
	    Column oldColumn = findColumn(oldColumns,
					  newColumn.getName());

	    if (oldColumn == null || !newColumn.equals(oldColumn)) {
		System.out.println("    - column \"" + newColumn.getName() +
				   "\" is different");
	    }

	    if (!columnChanges.containsKey(newColumn.getName())) {
		columnChanges.put(newColumn.getName(), new ColumnChange());
	    }

	    ColumnChange columnChange = (ColumnChange)
		columnChanges.get(newColumn.getName());
	    columnChange.setColumn(newColumn);

	    /*
	     * If the new column has a NOT NULL constraint, set a null
	     * replace value for the column.  Throw an exception if it
	     * is possible for null data to be inserted into the new
	     * column.  This would happen if there is not a null
	     * replacement and the column either didn't exist before or
	     * it did NOT have the NOT NULL constraint before.
	     */
	    if (newColumn.isNotNull()) {
		if (newColumn.getName().equals("eventsource")) {
		    columnChange.setNullReplace("OpenNMS.Eventd");
		} else if (newColumn.getName().equals("svcregainedeventid")
			   && table.equals("outages")) {
		    columnChange.setNullReplace(new Integer(0));
		} else if (newColumn.getName().equals("eventid") &&
			   table.equals("notifications")) {
		    columnChange.setNullReplace(new Integer(0));
		} else if (oldColumn == null) {
		    throw new Exception("Column " + newColumn.getName() +
					" in new table has NOT NULL " +
					"constraint, however this column " +
					"did not exist before and there is " +
					"no null replacement for this " +
					"column");
		} else if (!oldColumn.isNotNull()) {
		    throw new Exception("Column " + newColumn.getName() +
					" in new table has NOT NULL " +
					"constraint, however this column " +
					"did not have the NOT NULL " +
					"constraint before and there is " +
					"no null replacement for this " +
					"column");
		}
	    }
	}

	i = 0;
	for (j = oldColumns.iterator(); j.hasNext(); i++) {
	    Column oldColumn = (Column) j.next();

	    oldColumnNames[i] = oldColumn.getName();

	    if (columnChanges.containsKey(oldColumn.getName())) {
		ColumnChange columnChange = (ColumnChange)
		    columnChanges.get(oldColumn.getName());
		Column newColumn = (Column) columnChange.getColumn();
		if (newColumn.getType().indexOf("timestamp") != -1) {
		    columnChange.setUpgradeTimestamp(true);
		}
	    } else {
		System.out.println("    * WARNING: column \"" +
				   oldColumn.getName() + "\" exists in the " +
				   "database but is not in the new schema.  " +
				   "NOT REMOVING COLUMN");
	    }
	}

	String oldTable = table + "_old_" + System.currentTimeMillis();

	st.execute("ALTER TABLE " + table + " RENAME TO " + oldTable);

	try {
	    st.execute("CREATE TABLE " + table + "(" + getTableFromSQL(table) +
		       ")");

	    transformData(table, oldTable, columnChanges, oldColumnNames);

	    st.execute("GRANT ALL ON " + table + " TO " + m_user);

	    System.out.print("  - optimizing table " + table + "... ");
	    st.execute("VACUUM ANALYZE " + table);
	    System.out.println("DONE");
	} catch (Exception e) {
	    try {
		st.execute("DROP TABLE " + table + m_cascade);
		st.execute("ALTER TABLE " + oldTable + " RENAME TO " + table);
	    } catch (SQLException se) {
		throw new Exception("Got SQLException while trying to " +
				    "revert table changes due to original " +
				    "error: " + e + "\n" +
				    "SQLException while reverting table: " +
				    se, e);
	    }
	    throw e;
	}

	// We don't care if dropping the old table fails since we've
	// completed copying it, so it's outside of the try/catch block above.
	st.execute("DROP TABLE " + oldTable);

	System.out.println("  - completed updating table... ");
    }

    public void transformData(String table, String oldTable,
			      TreeMap columnChanges, String[] oldColumnNames)
	throws SQLException, ParseException, Exception {
	Statement st = m_dbconnection.createStatement();
	Iterator j;
	int i;

	String[] columns = (String[])
	    columnChanges.keySet().toArray(new String[0]);
	String[] questionMarks = new String[columns.length];

	for (i = 0; i < oldColumnNames.length; i++) {
	    ColumnChange c = (ColumnChange)
		columnChanges.get(oldColumnNames[i]);
	    c.setSelectIndex(i + 1);
	}

	for (i = 0; i < columns.length; i++) {
	    questionMarks[i] = "?";
	    ColumnChange c = (ColumnChange) columnChanges.get(columns[i]);
	    c.setPrepareIndex(i + 1);
	    c.setColumnType(((Column) c.getColumn()).getColumnSqlType());
	}

	/* Pull everything in from the old table and filter it to update
	   the data to any new formats. */

	System.out.print("  - transforming data into the new table...\r");

	if (table.equals("events")) {
	    st.execute("INSERT INTO events (eventid, eventuei, eventtime, " +
		       "eventsource, eventdpname, eventcreatetime, " +
		       "eventseverity, eventlog, eventdisplay) values " +
		       "(0, 'http://uei.opennms.org/dummyevent', now(), " +
		       "'OpenNMS.Eventd', 'localhost', now(), 1, 'Y', 'Y')");
	}

	ResultSet rs = st.executeQuery("SELECT count(*) FROM " + oldTable);
	rs.next();
	long num_rows = rs.getLong(1);

	PreparedStatement select = null;
	PreparedStatement insert = null;
	String order;
	if (table.equals("outages")) {
	    order = " ORDER BY iflostservice";
	} else {
	    order = "";
	}

	String dbcmd = "SELECT " + join(", ", oldColumnNames) + " FROM " +
	    oldTable + order;
	if (m_debug) {
	    System.out.println("  - performing select: " + dbcmd);
	}
	select = m_dbconnection.prepareStatement(dbcmd);
	// error =		      "Unable to prepare select from temp";

	dbcmd = "INSERT INTO " + table + " (" + join(", ", columns) +
	    ") values (" + join(", ", questionMarks) + ")";
	if (m_debug) {
	    System.out.println("  - performing insert: " + dbcmd);
	}
	insert = m_dbconnection.prepareStatement(dbcmd);
	// error = 	      "Unable to prepare insert into " + table);

	rs = select.executeQuery();
	m_dbconnection.setAutoCommit(false);

	String name;
	ColumnChange change;
	Object obj;
	SimpleDateFormat dateParser =
	    new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss");
	SimpleDateFormat dateFormatter =
	    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	char spin[] = { '/', '-', '\\', '|' };

	int current_row = 0;

	while (rs.next()) {
	    for (j = columnChanges.keySet().iterator(); j.hasNext(); ) {
		name = (String) j.next();
		change = (ColumnChange) columnChanges.get(name);
		
		if (change.getSelectIndex() > 0) {
		    obj = rs.getObject(change.getSelectIndex());
		    if (rs.wasNull()) {
			obj = null;
		    }
		} else {
		    if (m_debug) {
			System.out.println("    - don't know what to do " +
					   "for \"" +
					   name + "\", prepared column " +
					   change.getPrepareIndex() +
					   ": setting to null");
		    }
		    obj = null;
		}

		if (table.equals("outages") && name.equals("outageid")) {
		    obj = new Integer(current_row + 1);
		}
		if (obj == null && change.isNullReplace()) {
		    obj = change.getNullReplace();
		    if (m_debug) {
			System.out.println("    - " + name +
					   " was NULL but is a " +
					   "requires NULL replacement -- " +
					   "replacing with '" + obj + "'");
		    }
		}

		if (obj != null) {
		    if (change.isUpgradeTimestamp() &&
			!obj.getClass().equals(java.sql.Timestamp.class)) {
			if (m_debug) {
			    System.out.println("    - " + name +
					       " is an old-style timestamp");
			}
			String newObj =
			    dateFormatter.format(dateParser.parse((String)
								  obj));
			if (m_debug) {
			    System.out.println("    - " +
					       obj + " -> " + newObj);
			}

			obj = newObj;
		    }
		    if (m_debug) {
			System.out.println("    - " + name + " = " + obj);
		    }
		} else {
		    if (m_debug) {
			System.out.println("    - " + name + " = undefined");
		    }
		}

		if (obj == null) {
		    insert.setNull(change.getPrepareIndex(),
				   change.getColumnType());
		} else {
		    insert.setObject(change.getPrepareIndex(), obj);
		}
	    }

	    try {
		insert.execute();
	    } catch (SQLException e) {
		if (e.toString().indexOf("key referenced from " + table +
					 " not found in") == -1 &&
		    e.toString().indexOf("Cannot insert a duplicate key " +
					 "into unique index") == -1) {
		    throw e;
		    // error =	      "can't insert into " + table;
		}
	    }

	    current_row++;

	    if ((current_row % 20) == 0) {
		System.out.print("  - transforming data into the new " +
				 "table... " +
				 (int)Math.floor((current_row * 100) / num_rows) +
				 "%  [" + spin[(current_row / 20) % spin.length] + "]\r");
	    }
	}

	m_dbconnection.commit();
	m_dbconnection.setAutoCommit(true);

	System.out.println("  - transforming data into the new table... " +
			   "DONE           ");
    }

    public void printHelp() {
	System.out.println("usage:");
	System.out.println("  java -jar opennms_install.jar -h");
	System.out.println("  java -jar opennms_install.jar " +
			   "[-r] [-x] [-c] [-d] [-i] [-s] [-y]");
	System.out.println("                                " +
			   "[-u <PostgreSQL admin user>]");
	System.out.println("                                " +
			   "[-p <PostgreSQL admin password>]");
	System.out.println("                                " +
			   "[-S <tomcat server.xml file>]");
	System.out.println("                                " +
			   "[-T <tomcat4.conf>]");
	System.out.println("                                " +
			   "[-w <tomcat webapps directory>");
	System.out.println("                                " +
			   "[-W <tomcat server/lib directory>]");
	System.out.println("");
	System.out.println(m_required_options);
	System.out.println("");
	System.out.println("   -h    this help");
	System.out.println("");
	System.out.println("   -d    perform database actions");
	System.out.println("   -i    insert data into the database");
	System.out.println("   -s    update iplike postgres function");
	System.out.println("   -y    install web application (see -w and -W)");
	System.out.println("");
	System.out.println("   -u    username of the PostgreSQL " +
			   "administrator (default: \"" + m_pg_user + "\")");
	System.out.println("   -p    password of the PostgreSQL " +
			   "administrator (default: \"" + m_pg_pass + "\")");
	System.out.println("   -c    drop and recreate tables that already " +
			   "exist");
	System.out.println("");
	System.out.println("   -S    location of tomcat's server.xml");
	System.out.println("   -T    location of tomcat.conf");
	System.out.println("   -w    location of tomcat's webapps directory");
	System.out.println("   -W    location of tomcat's server/lib " +
			   "directory");
	System.out.println("");
	System.out.println("   -r    run as an RPM install (does nothing)");
	System.out.println("   -x    turn on debugging for database data " +
			   "transformation");

	System.exit(0);
    }

    public static void main(String[] argv) throws Exception {
	new Installer().install(argv);
    }

    /**
     * Join all of the elements of a String together into a single
     * string, inserting sep between each element.
     */
    public static String join(String sep, String[] array) {
	StringBuffer sb = new StringBuffer();

	if (array.length > 0) {
	    sb.append(array[0]);
	}

	for (int i = 1; i < array.length; i++) {
	    sb.append(sep + array[i]);
	}

	return sb.toString();
    }
}
