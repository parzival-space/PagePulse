package space.parzival.pagepulse;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.util.Date;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import space.parzival.pagepulse.database.Service;
import space.parzival.pagepulse.database.Status;
import space.parzival.pagepulse.properties.ApplicationProperties;
import space.parzival.pagepulse.properties.format.ServiceConfiguration;

@Slf4j
@Component
public class HealthChecker implements InitializingBean {
  private ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);;

  @Autowired
  private DatabaseManager database;

  @Autowired
  private ApplicationProperties properties;

  @Override
  public void afterPropertiesSet() throws Exception {
    // configure ping intervals for services
    for (Service service : database.getServices()) {
      ServiceConfiguration serviceConfiguration = properties.getServices().stream()
          .filter(s -> s.getName().equals(service.getName()) &&
              s.getGroup().equals(service.getGroup()) &&
              s.getEndpoint().equals(service.getEndpoint()))
          .findAny()
          .orElse(null);
      
      // get interval
      long interval = serviceConfiguration != null ? serviceConfiguration.getInterval() : 5;

      log.info("Scheduling a check task to run every {} seconds for service {}.", interval, service.getName());
      this.executor.scheduleAtFixedRate(() -> this.runServiceCheck(service), 0, interval, TimeUnit.SECONDS);
    }
  }

  /**
   * Checks the a check on the given service and stores the result in the database.
   * @param service The service to run the check on.
   */
  private void runServiceCheck(Service service) {
    Status status = Status.UNKNOWN;
    String error = null;

    try {
      // check connection
      URL endpoint = service.getEndpoint().toURL();
      HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();

      // check ssl
      if (connection instanceof HttpsURLConnection) {
        HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;

        // check certificate
        httpsConnection.connect();
        for (Certificate cert : httpsConnection.getServerCertificates()) {
          ((X509Certificate) cert).checkValidity();
        }
      }

      // all checks passed
      status = Status.OPERATIONAL;
    } catch (SSLHandshakeException sslhe) {
      log.info("SSL connection failed ({}/{}): {}", service.getGroup(), service.getName(), sslhe.getMessage());

      status = Status.LIMITED;
      error = sslhe.getMessage();
    } catch (CertificateException ce) {
      log.info("Certificate check ({}/{}): {}", service.getGroup(), service.getName(), ce.getMessage());

      status = Status.LIMITED;
      error = ce.getMessage();
    } catch (IOException ioe) {
      log.info("Connection check failed ({}/{}): {}", service.getGroup(), service.getName(), ioe.getMessage());

      ioe.printStackTrace();

      status = Status.OFFLINE;
      error = "Failed to connect to server.";
    }

    database.addHistoryEntry(service.getId(), new Timestamp(new Date().getTime()), status, error);
  }

}
