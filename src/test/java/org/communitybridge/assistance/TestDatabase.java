package org.communitybridge.assistance;

import static org.communitybridge.assistance.Domain.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class TestDatabase implements AutoCloseable {
  final Path path;
  final HikariDataSource ds;
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;
  final RequestService service;
  final ObjectMapper mapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  final ToolCatalog catalog;
  final Caller cw, manager, finance, other;

  TestDatabase(Path path) throws Exception {
    this.path = path;
    Database.reset(path);
    ds = Database.open(path);
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new JdbcTransactionManager(ds));
    tx.setIsolationLevelName("ISOLATION_READ_COMMITTED");
    service = new RequestService(jdbc, tx);
    catalog = new ToolCatalog(mapper);
    cw = caller("cb-case-worker");
    manager = caller("cb-program-manager");
    finance = caller("cb-finance");
    other = caller("hs-case-worker");
  }

  Caller caller(String id) {
    return IdentityResolver.resolve(jdbc, id);
  }

  Submission submission(String key, long cents) {
    return new Submission("cb-p1", "cb-emergency", "Rent", cents, key);
  }

  String create(String key, long cents) {
    return service.create(cw, submission(key, cents)).request().id();
  }

  java.util.List<Map<String, Object>> snapshot() {
    return jdbc.queryForList("SELECT * FROM assistance_request ORDER BY id");
  }

  @Override
  public void close() {
    ds.close();
  }
}
