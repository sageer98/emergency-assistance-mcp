package org.communitybridge.assistance;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

public final class Database {
  private Database() {}

  public static Path configuredPath() {
    return Path.of(System.getenv().getOrDefault("APP_DB_PATH", "./data/assistance"))
        .toAbsolutePath()
        .normalize();
  }

  public static String url(Path path, boolean existing) {
    // Do not let a path inject H2 URL options. No server/mixed mode is enabled.
    if (path.toString().contains(";") || path.toString().contains("\n"))
      throw new IllegalArgumentException("Invalid database path.");
    return "jdbc:h2:file:"
        + path.toAbsolutePath().normalize()
        + (existing ? ";IFEXISTS=TRUE" : "")
        // Persist acknowledged commits before a demo client can terminate this JVM.
        + ";LOCK_TIMEOUT=5000;WRITE_DELAY=0";
  }

  public static HikariDataSource open(Path path) {
    var ds = new HikariDataSource();
    ds.setJdbcUrl(url(path, true));
    ds.setUsername("sa");
    ds.setPassword("");
    ds.setMaximumPoolSize(8);
    ds.setMinimumIdle(1);
    ds.setConnectionTimeout(7000);
    return ds;
  }

  public static void reset(Path path) throws Exception {
    Files.createDirectories(path.toAbsolutePath().getParent());
    try (var connection = DriverManager.getConnection(url(path, false), "sa", "")) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }
  }
}
