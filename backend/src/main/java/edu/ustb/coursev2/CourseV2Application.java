package edu.ustb.coursev2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CourseV2Application {
  public static void main(String[] args) {
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    ConfigurableApplicationContext context = SpringApplication.run(CourseV2Application.class, args);
    String mode = context.getEnvironment().getProperty("app.mode", "serve");
    if (mode.equals("init") || mode.equals("verify") || mode.equals("migrate")) context.close();
  }
}
