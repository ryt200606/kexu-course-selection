package edu.ustb.coursev2;

import com.zaxxer.hikari.*;
import java.sql.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseConfig {
  public record Identity(String database, String serverVersion, boolean surrogate) {}

  @Bean(destroyMethod = "close")
  HikariDataSource dataSource(Environment env) {
    String type = env.getProperty("app.database.type", "opengauss");
    if (!type.equals("opengauss") && !type.equals("h2"))
      throw new IllegalArgumentException(
          "Only openGauss6.0.2 or explicitly labelled H2 surrogate is supported");
    String url = env.getRequiredProperty("app.database.url");
    if (type.equals("opengauss")
        && (!url.startsWith("jdbc:opengauss:")
            || !url.matches(
                ".*\\/(course_app_v2|course_app_v2_test|course_app_v2_perf|course_app_v2_perf_test)(?:\\?.*)?$")))
      throw new IllegalArgumentException("Explicit dedicated course_app_v2 database name required");
    String password = env.getProperty("app.database.password", "");
    if (type.equals("opengauss") && password.isBlank())
      throw new IllegalArgumentException("DB_PASSWORD required");
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(url);
    cfg.setUsername(env.getRequiredProperty("app.database.user"));
    cfg.setPassword(password);
    cfg.setDriverClassName(type.equals("opengauss") ? "org.opengauss.Driver" : "org.h2.Driver");
    cfg.setMaximumPoolSize(12);
    cfg.setMinimumIdle(1);
    cfg.setConnectionTimeout(5000);
    cfg.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    if (type.equals("opengauss")) cfg.setConnectionInitSql("SET TIME ZONE 'UTC'");
    return new HikariDataSource(cfg);
  }

  @Bean
  Identity databaseIdentity(Environment env, DataSource ds) throws SQLException {
    boolean h2 = env.getProperty("app.database.type", "opengauss").equals("h2");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(h2 ? "SELECT H2VERSION()" : "SELECT version()")) {
      r.next();
      String v = r.getString(1);
      if (!h2 && (!v.toLowerCase().contains("opengauss") || !v.contains("6.0.2")))
        throw new IllegalStateException("Actual database must be openGauss6.0.2");
      return new Identity(h2 ? "h2" : "opengauss", v, h2);
    }
  }

  @Bean
  JdbcTemplate jdbcTemplate(DataSource ds) {
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }
}
