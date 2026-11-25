package org.communitybridge.assistance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
public class AssistanceApplication {
  public static void main(String[] args) {
    try {
      if (Arrays.asList(args).contains("--app.mode=reset")) {
        Database.reset(Database.configuredPath());
        System.err.println("Synthetic database reset complete.");
        return;
      }
      // Force synchronous startup validation before any protocol transport is constructed.
      if (System.getenv("STAFF_ID") == null || System.getenv("STAFF_ID").isBlank())
        throw new IllegalArgumentException("STAFF_ID is required.");
      SpringApplication.run(AssistanceApplication.class, args);
    } catch (Exception failure) {
      System.err.println(
          "Startup failed. Check STAFF_ID, initialized database path, and stderr diagnostics.");
      System.exit(1);
    }
  }

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Bean(destroyMethod = "close")
  DataSource dataSource() {
    return Database.open(Database.configuredPath());
  }

  @Bean
  JdbcTemplate jdbc(DataSource ds) {
    return new JdbcTemplate(ds);
  }

  @Bean
  TransactionTemplate transactions(DataSource ds) {
    var tx = new TransactionTemplate(new JdbcTransactionManager(ds));
    tx.setIsolationLevelName("ISOLATION_READ_COMMITTED");
    return tx;
  }

  @Bean
  Domain.Caller caller(JdbcTemplate jdbc) {
    return IdentityResolver.resolve(jdbc, System.getenv("STAFF_ID"));
  }

  @Bean
  RequestService requests(JdbcTemplate jdbc, TransactionTemplate tx) {
    return new RequestService(jdbc, tx);
  }
}
