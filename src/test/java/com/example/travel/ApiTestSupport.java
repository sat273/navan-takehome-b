package com.example.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Real HTTP calls against the running Spring Boot server (java.net.http so PATCH works). */
final class ApiTestSupport {
  record Resp(int status, JsonNode body) {}

  static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static JsonNode seeds;

  private final String base;

  ApiTestSupport(int port) {
    this.base = "http://localhost:" + port;
  }

  static JsonNode seed(String name) {
    try {
      if (seeds == null) {
        Path p = Paths.get("fixtures/task-b/seed-payloads.json");
        seeds = MAPPER.readTree(Files.readString(p));
      }
      return seeds.get(name).deepCopy();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  Resp send(String method, String path, Object body) {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
      if (body == null) {
        b.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        b.header("Content-Type", "application/json");
        b.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
      }
      HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode json = r.body().isBlank() ? null : MAPPER.readTree(r.body());
      return new Resp(r.statusCode(), json);
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  Resp post(String path, Object body) {
    return send("POST", path, body);
  }

  Resp get(String path) {
    return send("GET", path, null);
  }

  Resp patch(String path, Object body) {
    return send("PATCH", path, body);
  }

  Resp delete(String path) {
    return send("DELETE", path, null);
  }
}
