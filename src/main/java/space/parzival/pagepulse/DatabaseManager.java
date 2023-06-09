package space.parzival.pagepulse;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import space.parzival.pagepulse.database.HistoryEntry;
import space.parzival.pagepulse.database.Service;
import space.parzival.pagepulse.database.Status;
import space.parzival.pagepulse.properties.ApplicationProperties;
import space.parzival.pagepulse.properties.DatabaseProperties;
import space.parzival.pagepulse.properties.format.ServiceConfiguration;

@Slf4j
@Component
public class DatabaseManager {
  private Connection connection;

  private final String servicesTable;
  private final String historyTable;

  public DatabaseManager(DatabaseProperties dbProperties, ApplicationProperties properties) throws SQLException {
    this.servicesTable = dbProperties.getTablePrefix() + "services";
    this.historyTable = dbProperties.getTablePrefix() + "history";

    if (dbProperties.getConnection().isEmpty()) {
      log.warn("You did not define database path. Please check you application.properties file. I will fallback to an in-memory database...");
      dbProperties.setConnection("jdbc:sqlite::memory:");
    }

    // create database connection
    this.connection = DriverManager.getConnection(dbProperties.getConnection());
    log.info("Database connected.");

    this.initTables();
    this.populateServices(properties);
  }

  private void initTables() throws SQLException {
    try (Statement statement = this.connection.createStatement()) {

      // initialize services table
      if (!doesTableExist(this.servicesTable)) {
        log.debug("Initializing missing table 'services'...");

        statement.execute(
          "CREATE TABLE " + this.servicesTable + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "\"group\" TEXT NOT NULL," +
            "name TEXT NOT NULL, " +
            "endpoint TEXT NOT NULL, " +
            "hidden BOOL NOT NULL" +
          ")"
        );
      }
      
      // initialize history table
      if (!doesTableExist(this.historyTable)) {
        log.debug("Initializing missing table 'history'...");

        statement.execute(
          "CREATE TABLE " + this.historyTable + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "serviceId INTEGER NOT NULL, "+
            "timestamp TIMESTAMP NOT NULL, "+
            "status VARCHAR(11) NOT NULL, "+
            "error TEXT, "+
            "possibleCause TEXT, "+
            "FOREIGN KEY (serviceId) REFERENCES " + this.servicesTable + "(id) ON DELETE CASCADE" +
          ")"
        );
      }

    }
  }

  private boolean doesTableExist(String tableName) throws SQLException {
    DatabaseMetaData meta = this.connection.getMetaData();
    ResultSet resultSet = meta.getTables(null, null, tableName, new String[] {"TABLE"});

    return resultSet.next();
  }

  private void populateServices(ApplicationProperties properties) throws SQLException {
    List<ServiceConfiguration> serviceConfigurations = properties.getServices();
    if (serviceConfigurations.isEmpty()) {
      try (Statement statement = this.connection.createStatement()) {
        log.warn("No services registered in configuration file. The service table will empty.");
        statement.execute("PRAGMA foreign_keys = ON");
        statement.execute("DELETE FROM " + this.servicesTable);
      }
      return;
    }

    log.info("Populating services table...");

    // remove obsolete services
    StringBuilder deleteStatement = new StringBuilder();
    deleteStatement.append("DELETE FROM " + this.servicesTable + " WHERE ");
    for (int i = 0; i < serviceConfigurations.size(); i++) {
      ServiceConfiguration sConf = serviceConfigurations.get(i);

      // update delete statement
      deleteStatement.append(
        String.format(
          "id NOT IN (SELECT id FROM %s WHERE (name = '%s' AND \"group\" = '%s' AND endpoint = '%s'))", 
          this.servicesTable,
          sConf.getName(),
          sConf.getGroup(),
          sConf.getEndpoint()
        )
      );
      if (i +1 != serviceConfigurations.size()) deleteStatement.append(" AND ");
    }
    
    try (Statement statement = this.connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys = ON");
      statement.execute(deleteStatement.toString());
    }

    // create new entries
    int skipped = 0;
    StringBuilder insertStatement = new StringBuilder();
    insertStatement.append("INSERT INTO " + this.servicesTable + " (name, \"group\", endpoint, hidden) VALUES ");
    for (int i = 0; i < serviceConfigurations.size(); i++) {
      ServiceConfiguration sConf = serviceConfigurations.get(i);

      // make sure the service is not already registered
      if (this.getServiceId(sConf.getName(), sConf.getGroup()) != -1) {
        skipped++;
        continue;
      }

      // insert new services
      if (i > skipped) insertStatement.append(", ");
      insertStatement.append(String.format("('%s', '%s', '%s', %b)", sConf.getName(), sConf.getGroup(), sConf.getEndpoint(), sConf.isEndpointHidden()));
    }
    if (serviceConfigurations.size() - skipped == 0) {
      log.info("All service entries are up to date.");
    } else {
      log.info("Updating service entries...");
      try (Statement statement = this.connection.createStatement()) {
        statement.execute(insertStatement.toString());
      }
    }
  }

  /**
   * Fetches the full service list from the database.
   * Could cause overhead on huge databases.
   * @return Full service list.
   */
  public List<Service> getServices() {
    List<Service> services = new ArrayList<>();

    try (Statement statement = this.connection.createStatement()) {
      ResultSet rs = statement.executeQuery("SELECT * FROM " + this.servicesTable);

      while (rs.next()) {
        Service service = new Service();

        service.setId(rs.getInt("id"));
        service.setName(rs.getString("name"));
        service.setGroup(rs.getString("group"));
        service.setEndpoint(new URI(rs.getString("endpoint")));
        service.setEndpointHidden(rs.getBoolean("hidden"));

        services.add(service);
      }
    }
    catch (SQLException | URISyntaxException e) {
      log.error("{}", e);
    }

    return services;
  }

  /**
   * Fetches the full history of a service by its id.
   * @param serviceId The service you want to get the history for.
   * @return Full service history.
   */
  public List<HistoryEntry> getHistory(int serviceId, int limit) {
    List<HistoryEntry> history = new ArrayList<>();

    try (Statement statement = this.connection.createStatement()) {
      ResultSet rs = statement.executeQuery("SELECT * FROM " + this.historyTable + " WHERE (serviceId = " + serviceId + ") ORDER BY timestamp DESC LIMIT " + limit);

      while (rs.next()) {
        HistoryEntry entry = new HistoryEntry();

        entry.setTimestamp(rs.getTimestamp("timestamp"));
        entry.setError(rs.getString("error"));
        entry.parseStatus(rs.getString("status"));
        entry.setPossibleCause(rs.getString("possibleCause"));

        history.add(entry);
      }
    }
    catch (SQLException e) {
      log.error("{}", e);
    }

    return history;
  }

  /**
   * Returns the ID of a service or -1 if the Service could not be found.
   * @param name The name of the service.
   * @param group The group of the service.
   * @return The ID of a service or -1 if the Service could not be found.
   */
  public int getServiceId(String name, String group) {
    int result = -1;

    try (Statement statement = this.connection.createStatement()) {
      ResultSet rs = statement.executeQuery("SELECT id FROM " + this.servicesTable + " WHERE (name = '" + name + "' AND \"group\" = '" + group + "')");

      while (rs.next()) {
        result = rs.getInt("id");
      }
    }
    catch (SQLException e) {
      log.error("{}", e);
    }

    return result;
  }

  public void addHistoryEntry(int serviceId, Timestamp timestamp, Status status, String error, String possibleCause) {
    try (Statement statement = this.connection.createStatement()) {
      statement.execute(
        "INSERT INTO " + this.historyTable + " (serviceId, timestamp, status, error, possibleCause) " +
        "VALUES ('" + serviceId + "', '" + timestamp.toString() + 
          "', '" + status.toString() + 
          "', " + (error == null ? "NULL" : "'" + error + "'") +
          ", " + (possibleCause == null ? "NULL" : "'" + possibleCause + "'") + 
        ")"
      );
    }
    catch (SQLException e) {
      log.error("{}", e);
    }
  }

  public void cleanupOldEntries(int serviceId, int entriesAfterCleanup) {
    String query = String.format("DELETE FROM %s WHERE id NOT IN (SELECT id FROM pagepulse_history ORDER BY timestamp DESC LIMIT %d) AND serviceId = %d", this.historyTable, entriesAfterCleanup, serviceId);

    try (Statement statement = this.connection.createStatement()) {
      statement.execute(query);
    }
    catch (SQLException e) {
      log.error("Cleanup failed for service with id: {}", serviceId);
      log.trace("Cleanup failed because of an SQLException", e);
    }
  }
}
