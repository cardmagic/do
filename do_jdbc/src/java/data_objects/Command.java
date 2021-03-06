package data_objects;

import data_objects.drivers.DriverDefinition;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyObject;
import org.jruby.RubyObjectAdapter;
import org.jruby.RubyString;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.javasupport.Java;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.javasupport.JavaObject;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.builtin.IRubyObject;

import static data_objects.DataObjects.DATA_OBJECTS_MODULE_NAME;

/**
 * Command Class
 *
 * @author alexbcoles
 */
@JRubyClass(name = "Command")
public class Command extends RubyObject {

    public final static String RUBY_CLASS_NAME = "Command";
    private static RubyObjectAdapter api;
    private static DriverDefinition driver;
    private static String moduleName;
    private static String errorName;

    private final static ObjectAllocator COMMAND_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            Command instance = new Command(runtime, klass);
            return instance;
        }
    };

    public static RubyClass createCommandClass(final Ruby runtime,
            final String moduleName, final String errorName,
            final DriverDefinition driverDefinition) {
        RubyModule doModule = runtime.getModule(DATA_OBJECTS_MODULE_NAME);
        RubyModule quotingModule = (RubyModule) doModule.getConstant("Quoting");
        RubyClass superClass = doModule.getClass(RUBY_CLASS_NAME);
        RubyModule driverModule = (RubyModule) doModule.getConstant(moduleName);
        RubyClass commandClass = runtime.defineClassUnder("Command",
                superClass, COMMAND_ALLOCATOR, driverModule);
        Command.api = JavaEmbedUtils.newObjectAdapter();
        Command.driver = driverDefinition;
        Command.moduleName = moduleName;
        Command.errorName = errorName;
        commandClass.includeModule(quotingModule);
        commandClass.defineAnnotatedMethods(Command.class);
        return commandClass;
    }

    private Command(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
    }

    // -------------------------------------------------- DATAOBJECTS PUBLIC API

    // inherit initialize

    @JRubyMethod(optional = 1)
    public static IRubyObject execute_non_query(IRubyObject recv, IRubyObject[] args) {
        Ruby runtime = recv.getRuntime();
        IRubyObject connection = api.getInstanceVariable(recv, "@connection");
        IRubyObject url = api.getInstanceVariable(connection, "@uri");
        IRubyObject wrappedJdbcConnection = api.getInstanceVariable(connection, "@connection");
        IRubyObject insert_key = runtime.getNil();
        RubyClass resultClass = Result.createResultClass(runtime, moduleName, errorName, driver);

        // escape all parameters given and pass them to query
        String querySql = buildQueryFromArgs(recv, args);

        java.sql.Connection javaConn = (java.sql.Connection) wrappedJdbcConnection.dataGetStruct();
        int affectedCount = 0;
        Statement sqlStatement = null;
        java.sql.ResultSet keys = null;
        boolean supportsGeneratedKeys = driver.supportsJdbcGeneratedKeys();

        debug(runtime, querySql);
        try {
            sqlStatement = javaConn.createStatement();
            javaConn.setAutoCommit(true); // hangs with autocommit set to false
            // sqlStatement.setMaxRows();

            if (supportsGeneratedKeys) {
                // Derby, H2, and MySQL all support getGeneratedKeys(), but only
                // to varying extents.
                //
                // However, javaConn.getMetaData().supportsGetGeneratedKeys()
                // currently returns FALSE for the Derby driver, as its support
                // is limited. As such, we use supportsJdbcGeneratedKeys() from
                // our own driver definition.
                //
                // See http://issues.apache.org/jira/browse/DERBY-242
                // See http://issues.apache.org/jira/browse/DERBY-2631
                // (Derby only supplies getGeneratedKeys() for auto-incremented
                // columns)
                //
                affectedCount = sqlStatement.executeUpdate(querySql, Statement.RETURN_GENERATED_KEYS);
                keys = sqlStatement.getGeneratedKeys();
            } else {
                // If there is no support, then a custom method canb e defined
                // to return a ResultSet with keys
                affectedCount = sqlStatement.executeUpdate(querySql);
                keys = driver.getGeneratedKeys(javaConn);
            }
            if (keys != null) {
                insert_key = unmarshal_id_result(runtime, keys);
            }

            sqlStatement.close();
            sqlStatement = null;
        } catch (SQLException sqle) {
            // TODO: log sqle.printStackTrace();
            throw DataObjectsUtils.newDriverError(runtime, errorName, sqle.getLocalizedMessage());
        } finally {
            if (sqlStatement != null) {
                try {
                    sqlStatement.close();
                } catch (SQLException sqle2) {
                }
            }
        }

        // return nil if no updates are made
        if (affectedCount <= 0) {
            return runtime.getNil();
        }

        IRubyObject affected_rows = runtime.newFixnum(affectedCount);

        IRubyObject result = api.callMethod(resultClass, "new", new IRubyObject[] {
            recv, affected_rows, insert_key
        });
        return result;
    }

    @JRubyMethod(optional = 1)
    public static IRubyObject execute_reader(IRubyObject recv, IRubyObject[] args) {
        Ruby runtime = recv.getRuntime();
        IRubyObject connection = api.getInstanceVariable(recv, "@connection");
        IRubyObject wrappedJdbcConnection = api.getInstanceVariable(connection, "@connection");
        RubyClass readerClass = Reader.createReaderClass(runtime, moduleName, errorName, driver);

        // escape all parameters given and pass them to query
        String querySql = buildQueryFromArgs(recv, args);

        java.sql.Connection javaConn = (java.sql.Connection) wrappedJdbcConnection.dataGetStruct();
        boolean inferTypes = false;
        int columnCount = 0;
        int rowCount = 0;
        Statement sqlStatement = null;
        ResultSet resultSet = null;
        ResultSetMetaData metaData = null;

        // instantiate a new reader
        IRubyObject reader = readerClass.newInstance(runtime.getCurrentContext(),
                new IRubyObject[] { }, Block.NULL_BLOCK);

        // execute the query
        debug(runtime, querySql);
        try {
            sqlStatement = javaConn.createStatement();
            //sqlStatement.setMaxRows();
            resultSet = sqlStatement.executeQuery(querySql);
            metaData = resultSet.getMetaData();
            columnCount = metaData.getColumnCount();

            // pass the response to the reader
            IRubyObject wrappedResultSet = Java.java_to_ruby(recv, JavaObject.wrap(recv.getRuntime(), resultSet), Block.NULL_BLOCK);
            reader.getInstanceVariables().setInstanceVariable("@reader", wrappedResultSet);

            wrappedResultSet.dataWrapStruct(resultSet);

            // handle each result

            // mark the reader as opened
            api.setInstanceVariable(reader, "@opened", runtime.newBoolean(true));
            // TODO: if no response return nil

            api.setInstanceVariable(reader, "@position", runtime.newFixnum(0));

            // save the field_count in reader
            api.setInstanceVariable(reader, "@field_count", runtime.newFixnum(columnCount));
            api.setInstanceVariable(reader, "@row_count", runtime.newFixnum(rowCount));

            // get the field types
            RubyArray field_names = runtime.newArray();
            IRubyObject field_types = api.getInstanceVariable(recv , "@types");

            // If no types are passed in, infer them
            if (field_types == null) {
                field_types = runtime.newArray();
                inferTypes = true;
            } else {
                int fieldTypesCount = field_types.convertToArray().getLength();
                if (field_types.isNil() || fieldTypesCount == 0) {
                    field_types = runtime.newArray();
                    inferTypes = true;
                } else if (fieldTypesCount != columnCount) {
                    // Wrong number of fields passed to set_types. Close the reader
                    // and raise an error.
                    api.callMethod(reader, "close");
                    throw DataObjectsUtils.newDriverError(runtime, errorName,
                            String.format("Field-count mismatch. Expected %1$d fields, but the query yielded %2$d",
                            fieldTypesCount,
                            columnCount));
                }
            }

            // for each field
            for (int i = 0; i < columnCount; i++) {
                RubyString field_name = runtime.newString("");//metaData.getColumnName(i));
                // infer the type if no types passed
                field_names.push_m(new IRubyObject[] { field_name });
                if (inferTypes) {
                    // TODO: do something
                }
            }

            // set the reader @field_names and @types (guessed or otherwise)
            api.setInstanceVariable(reader, "@fields", field_names);
            api.setInstanceVariable(reader, "@types", field_types);

            // keep the statement open
            //sqlStatement.close();
            //sqlStatement = null;
        } catch (SQLException sqle) {
            // TODO: log sqle.printStackTrace();
            throw DataObjectsUtils.newDriverError(runtime, errorName, sqle.getLocalizedMessage());
        } finally {
            //if (sqlStatement != null) {
            //    try {
            //        sqlStatement.close();
            //    } catch (SQLException stsqlex) {
            //    }
            //}
        }

        // return the reader
        return reader;
    }

    @JRubyMethod(required = 1)
    public static IRubyObject set_types(IRubyObject recv, IRubyObject value) {
        IRubyObject types = api.setInstanceVariable(recv, "@types", value);
        return types;
    }

    // ------------------------------------------------ ADDITIONAL JRUBY METHODS

    @JRubyMethod(required = 1)
    public static IRubyObject quote_string(IRubyObject recv, IRubyObject value) {
        String toQuote = value.asJavaString();
        StringBuffer quotedValue = new StringBuffer(toQuote.length() + 2);
        quotedValue.append("\'");
        quotedValue.append(toQuote);
        quotedValue.append("\'");
        return recv.getRuntime().newString(quotedValue.toString());
    }

    // ---------------------------------------------------------- HELPER METHODS

    /**
     * Unmarshal a java.sql.Resultset containing generated keys, and return a
     * Ruby Fixnum with the last key.
     *
     * @param runtime
     * @param rs
     * @return
     * @throws java.sql.SQLException
     */
    public static IRubyObject unmarshal_id_result(Ruby runtime, ResultSet rs) throws SQLException {
        try {
            if (rs.next()) {
                if (rs.getMetaData().getColumnCount() > 0) {
                    // Need to do check for other types here, as keys could be
                    // of type Integer, Long or String
                    return runtime.newFixnum(rs.getLong(1));
                }
            }
            return runtime.getNil();
        } finally {
            try {
                rs.close();
            } catch (Exception e) {}
        }
    }

    /**
     *
     * @param recv
     * @param args
     * @return the query as a java.lang.String
     */
    private static String buildQueryFromArgs(IRubyObject recv, IRubyObject[] args) {
        Ruby runtime = recv.getRuntime();
        String query = api.getInstanceVariable(recv, "@text").asJavaString();

        if (args.length > 0) {
            RubyArray escape_args = runtime.newArray(args);
            query = api.callMethod(recv, "escape_sql", escape_args).convertToString().asJavaString();
        }
        return query;
    }

    /**
     * Output a log message
     *
     * @param runtime
     * @param logMessage
     */
    private static void debug(Ruby runtime, String logMessage) {
        RubyModule driverModule = (RubyModule) runtime.getModule(DATA_OBJECTS_MODULE_NAME).getConstant(moduleName);
        IRubyObject logger = api.callMethod(driverModule, "logger");
        int level = RubyNumeric.fix2int(api.callMethod(logger, "level"));

        if (level == 0) {
            api.callMethod(logger, "debug", runtime.newString(logMessage));
        }
    }

}
