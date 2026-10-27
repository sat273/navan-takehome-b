package com.example.travel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TravelApplication {
  public static void main(String[] args) {
    createDbDirectory();
    SpringApplication.run(TravelApplication.class, args);
  }

  // SQLite creates the file but not its parent folder, so a fresh clone would fail on ./data.
  private static void createDbDirectory() {
    String configured = System.getenv("DB_PATH");
    Path parent = Path.of(configured != null ? configured : "./data/travel.db").toAbsolutePath().getParent();
    try {
      Files.createDirectories(parent);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
