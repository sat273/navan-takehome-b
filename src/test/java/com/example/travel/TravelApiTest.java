package com.example.travel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.travel.ApiTestSupport.Resp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TravelApiTest {
  private static final Path DB;

  static {
    try {
      DB = Files.createTempFile("travel-test", ".db");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("DB_PATH", DB::toString);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  ApiTestSupport api;

  @BeforeEach
  void reset() {
    api = new ApiTestSupport(port);
    jdbc.update("DELETE FROM invoices");
    jdbc.update("DELETE FROM transactions");
  }

  private String create(String seedName) {
    Resp r = api.post("/transactions", ApiTestSupport.seed(seedName));
    assertThat(r.status()).as("create %s: %s", seedName, r.body()).isEqualTo(201);
    return r.body().get("id").asText();
  }

  private List<String> createTrip() {
    return List.of(
        create("flight"), create("hotel"), create("rail"), create("trip_fee"), create("agent_call_fee"));
  }

  private Resp invoiceTrip(List<String> ids) {
    ObjectNode body = ApiTestSupport.MAPPER.createObjectNode();
    body.set("transaction_ids", ApiTestSupport.MAPPER.valueToTree(ids));
    return api.post("/invoices", body);
  }

  private static BigDecimal dec(JsonNode n) {
    return n.decimalValue();
  }

  private static void assertMoney(JsonNode n, String expected) {
    assertThat(dec(n)).isEqualByComparingTo(expected);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  @Test
  void healthIsUp() {
    assertThat(api.get("/health").status()).isEqualTo(200);
  }

  @Test
  void createsAllTypesAndRoundTripsMetadata() {
    Resp flight = api.get("/transactions/" + create("flight"));
    assertThat(flight.body().get("type").asText()).isEqualTo("flight");
    JsonNode coupon = flight.body().at("/metadata/coupons/0");
    assertThat(coupon.get("flight_number").asText()).isEqualTo("BA982");
    assertThat(flight.body().at("/metadata/origin").asText()).isEqualTo("TXL");
    assertMoney(flight.body().get("total"), "220.00");
    assertThat(flight.body().get("tax_lines").get(0).get("name").asText()).isEqualTo("Airport tax");
    assertThat(flight.body().get("fee_lines").get(0).get("name").asText()).isEqualTo("YQ");

    Resp hotel = api.get("/transactions/" + create("hotel"));
    assertThat(hotel.body().at("/metadata/name").asText()).isEqualTo("Hotel Mitte London");
    assertThat(hotel.body().at("/metadata/address/city").asText()).isEqualTo("London");
    assertThat(hotel.body().at("/metadata/address/street").asText()).isEqualTo("12 Kingsway");
    assertMoney(hotel.body().get("tax_lines").get(0).get("rate"), "0.07");

    Resp rail = api.get("/transactions/" + create("rail"));
    assertThat(rail.body().at("/metadata/legs")).hasSize(2);
    assertThat(rail.body().at("/metadata/legs/1/train_number").asText()).isEqualTo("EST9122");
    assertThat(rail.body().get("tax_lines")).isEmpty();

    Resp trip = api.get("/transactions/" + create("trip_fee"));
    assertThat(trip.body().at("/metadata/fee_kind").asText()).isEqualTo("trip_fee");
    Resp agent = api.get("/transactions/" + create("agent_call_fee"));
    assertThat(agent.body().at("/metadata/fee_kind").asText()).isEqualTo("agent_call_fee");
  }

  @Test
  void rejectsUnknownTypeAndFeeKindAndEmptyMetadata() {
    ObjectNode bad = (ObjectNode) ApiTestSupport.seed("flight");
    bad.put("type", "boat");
    assertThat(api.post("/transactions", bad).status()).isEqualTo(400);

    ObjectNode badKind = (ObjectNode) ApiTestSupport.seed("trip_fee");
    ((ObjectNode) badKind.get("metadata")).put("fee_kind", "mystery_fee");
    assertThat(api.post("/transactions", badKind).status()).isEqualTo(400);

    ObjectNode empty = (ObjectNode) ApiTestSupport.seed("flight");
    empty.set("metadata", ApiTestSupport.MAPPER.createObjectNode());
    assertThat(api.post("/transactions", empty).status()).isEqualTo(400);

    ObjectNode wrongShape = (ObjectNode) ApiTestSupport.seed("flight");
    wrongShape.set("metadata", ApiTestSupport.seed("hotel").get("metadata"));
    assertThat(api.post("/transactions", wrongShape).status()).isEqualTo(400);
    assertThat(count("transactions")).isZero();
  }

  @Test
  void duplicateExternalIdIsRejectedWithExistingId() {
    String id = create("flight");
    Resp dup = api.post("/transactions", ApiTestSupport.seed("flight"));
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().get("id").asText()).isEqualTo(id);
    assertThat(dup.body().get("existing_id").asText()).isEqualTo(id);

    // The fixture's duplicate body (different metadata) is also rejected as a duplicate.
    Resp dup2 = api.post("/transactions", ApiTestSupport.seed("duplicate_flight"));
    assertThat(dup2.status()).isEqualTo(409);
    assertThat(count("transactions")).isEqualTo(1);
  }

  @Test
  void tripInvoiceMatchesExpectedTotals() {
    List<String> ids = createTrip();
    Resp inv = invoiceTrip(ids);
    assertThat(inv.status()).isEqualTo(201);
    JsonNode body = api.get("/invoices/" + inv.body().get("id").asText()).body();
    assertThat(body.get("currency").asText()).isEqualTo("EUR");
    assertMoney(body.get("total"), "685.70");
    assertThat(body.get("lines")).hasSize(5);
    assertMoney(body.at("/lines/0/total"), "220.00");
    assertMoney(body.at("/lines/1/total"), "331.70");
    assertMoney(body.at("/lines/2/total"), "94.00");
    assertMoney(body.at("/lines/3/total"), "25.00");
    assertMoney(body.at("/lines/4/total"), "15.00");

    JsonNode tax = body.get("tax_rollup");
    assertThat(tax).hasSize(2);
    assertThat(tax.get(0).get("name").asText()).isEqualTo("Airport tax");
    assertMoney(tax.get(0).get("amount"), "15.00");
    assertThat(tax.get(1).get("name").asText()).isEqualTo("VAT");
    assertMoney(tax.get(1).get("rate"), "0.07");
    assertMoney(tax.get(1).get("amount"), "21.70");
    JsonNode fees = body.get("fee_rollup");
    assertThat(fees).hasSize(2);
    assertThat(fees.get(0).get("name").asText()).isEqualTo("YQ");
    assertMoney(fees.get(0).get("amount"), "25.00");
    assertThat(fees.get(1).get("name").asText()).isEqualTo("Booking fee");
    assertMoney(fees.get(1).get("amount"), "4.00");

    assertThat(body.at("/lines/0/metadata/coupons/0/flight_number").asText()).isEqualTo("BA982");
    assertThat(body.at("/lines/1/metadata/address/city").asText()).isEqualTo("London");
    assertThat(body.at("/lines/2/metadata/legs")).hasSize(2);
    assertThat(body.at("/lines/3/metadata/fee_kind").asText()).isEqualTo("trip_fee");
    assertThat(body.at("/lines/4/metadata/fee_kind").asText()).isEqualTo("agent_call_fee");
  }

  @Test
  void singleTransactionIdInvoiceWorks() {
    String id = create("flight");
    ObjectNode body = ApiTestSupport.MAPPER.createObjectNode().put("transaction_id", id);
    Resp inv = api.post("/invoices", body);
    assertThat(inv.status()).isEqualTo(201);
    assertMoney(inv.body().get("total"), "220.00");
  }

  @Test
  void invoiceIsAFrozenSnapshot() {
    List<String> ids = createTrip();
    String invId = invoiceTrip(ids).body().get("id").asText();

    // Bypass the API guard (which would 409) to prove the snapshot is a copy, not a live join.
    jdbc.update("UPDATE transactions SET total_minor = 99999 WHERE id = ?", ids.get(0));
    jdbc.update(
        "UPDATE transaction_tax_lines SET amount_minor = 1 WHERE transaction_seq ="
            + " (SELECT seq FROM transactions WHERE id = ?)",
        ids.get(0));

    JsonNode inv = api.get("/invoices/" + invId).body();
    assertMoney(inv.at("/lines/0/total"), "220.00");
    assertMoney(inv.at("/lines/0/tax_lines/0/amount"), "15.00");
    assertMoney(inv.get("total"), "685.70");
    assertMoney(inv.at("/tax_rollup/0/amount"), "15.00");
  }

  @Test
  void mixedCurrencyInvoiceIs409AndStoresNothing() {
    String eur = create("flight");
    ObjectNode usd = (ObjectNode) ApiTestSupport.seed("rail");
    usd.put("currency", "USD");
    String usdId = api.post("/transactions", usd).body().get("id").asText();
    Resp r = invoiceTrip(List.of(eur, usdId));
    assertThat(r.status()).isEqualTo(409);
    assertThat(count("invoices")).isZero();
  }

  @Test
  void invoiceErrors() {
    String id = create("flight");
    assertThat(invoiceTrip(List.of(id, "txn_missing")).status()).isEqualTo(404);
    assertThat(invoiceTrip(List.of(id, id)).status()).isEqualTo(409);
    assertThat(api.post("/invoices", ApiTestSupport.MAPPER.createObjectNode()).status()).isEqualTo(400);
    assertThat(count("invoices")).isZero();
  }

  @Test
  void concurrentDuplicateCreatesLeaveOneRow() throws Exception {
    for (int round = 0; round < 5; round++) {
      String externalId = "race-" + round;
      int threads = 8;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Resp>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<Resp> call =
            () -> {
              ObjectNode body = (ObjectNode) ApiTestSupport.seed("flight");
              body.put("external_id", externalId);
              start.await();
              return api.post("/transactions", body);
            };
        futures.add(pool.submit(call));
      }
      start.countDown();
      int created = 0;
      int conflicts = 0;
      String winnerId = null;
      List<String> conflictIds = new ArrayList<>();
      for (Future<Resp> f : futures) {
        Resp r = f.get();
        if (r.status() == 201) {
          created++;
          winnerId = r.body().get("id").asText();
        } else if (r.status() == 409) {
          conflicts++;
          conflictIds.add(r.body().get("id").asText());
        }
      }
      pool.shutdown();
      assertThat(created).isEqualTo(1);
      assertThat(conflicts).isEqualTo(threads - 1);
      assertThat(conflictIds).allMatch(winnerId::equals);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transactions WHERE external_id = ?", Integer.class, externalId))
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentPatchesNeverTearLineLists() throws Exception {
    String id = create("flight");
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<Resp>> futures = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      int n = i % 2 == 0 ? 1 : 3;
      futures.add(
          pool.submit(
              () -> {
                ObjectNode body = ApiTestSupport.MAPPER.createObjectNode();
                var lines = body.putArray("tax_lines");
                for (int k = 0; k < n; k++) {
                  lines.addObject().put("name", "T" + n + "-" + k).put("amount", 1.00);
                }
                return api.patch("/transactions/" + id, body);
              }));
    }
    for (Future<Resp> f : futures) {
      assertThat(f.get().status()).isEqualTo(200);
    }
    pool.shutdown();
    JsonNode taxes = api.get("/transactions/" + id).body().get("tax_lines");
    assertThat(taxes.size()).isIn(1, 3);
    String prefix = taxes.get(0).get("name").asText().substring(0, 2);
    taxes.forEach(t -> assertThat(t.get("name").asText()).startsWith(prefix));
  }

  @Test
  void patchBeforeInvoicingWorksAndAfterInvoicingIs409() {
    String id = create("flight");
    ObjectNode patch = ApiTestSupport.MAPPER.createObjectNode().put("total", 230.5);
    Resp ok = api.patch("/transactions/" + id, patch);
    assertThat(ok.status()).isEqualTo(200);
    assertMoney(ok.body().get("total"), "230.50");

    String invId =
        api.post("/invoices", ApiTestSupport.MAPPER.createObjectNode().put("transaction_id", id))
            .body()
            .get("id")
            .asText();

    Resp blocked = api.patch("/transactions/" + id, ApiTestSupport.MAPPER.createObjectNode().put("total", 1.0));
    assertThat(blocked.status()).isEqualTo(409);
    assertMoney(api.get("/transactions/" + id).body().get("total"), "230.50");
    assertMoney(api.get("/invoices/" + invId).body().get("total"), "230.50");
    assertThat(api.patch("/transactions/txn_missing", patch).status()).isEqualTo(404);
  }

  @Test
  void deleteTransactionBlockedWhenInvoicedOtherwiseRemovesRowAndLines() {
    String billed = create("flight");
    api.post("/invoices", ApiTestSupport.MAPPER.createObjectNode().put("transaction_id", billed));
    assertThat(api.delete("/transactions/" + billed).status()).isEqualTo(409);
    assertThat(api.get("/transactions/" + billed).status()).isEqualTo(200);

    String free = create("rail");
    assertThat(api.delete("/transactions/" + free).status()).isEqualTo(204);
    assertThat(api.get("/transactions/" + free).status()).isEqualTo(404);
    assertThat(api.delete("/transactions/" + free).status()).isEqualTo(404);
    assertThat(count("transaction_fee_lines")).isEqualTo(1); // only the billed flight's YQ remains
  }

  @Test
  void deleteInvoiceKeepsTransactions() {
    List<String> ids = createTrip();
    String invId = invoiceTrip(ids).body().get("id").asText();
    assertThat(api.delete("/invoices/" + invId).status()).isEqualTo(204);
    assertThat(api.get("/invoices/" + invId).status()).isEqualTo(404);
    assertThat(api.delete("/invoices/" + invId).status()).isEqualTo(404);
    ids.forEach(id -> assertThat(api.get("/transactions/" + id).status()).isEqualTo(200));
    assertThat(count("invoice_lines")).isZero();
    // Once the invoice is gone the transaction is editable again.
    assertThat(api.delete("/transactions/" + ids.get(0)).status()).isEqualTo(204);
  }

  @Test
  void patchInvoiceRebuildsSnapshotFromCurrentTransactions() {
    String flight = create("flight");
    String rail = create("rail");
    String invId =
        api.post("/invoices", ApiTestSupport.MAPPER.createObjectNode().put("transaction_id", flight))
            .body()
            .get("id")
            .asText();

    ObjectNode body = ApiTestSupport.MAPPER.createObjectNode();
    body.set("transaction_ids", ApiTestSupport.MAPPER.valueToTree(List.of(flight, rail)));
    Resp r = api.patch("/invoices/" + invId, body);
    assertThat(r.status()).isEqualTo(200);
    assertMoney(r.body().get("total"), "314.00");
    assertThat(r.body().get("lines")).hasSize(2);

    ObjectNode usd = (ObjectNode) ApiTestSupport.seed("hotel");
    usd.put("currency", "USD");
    String usdId = api.post("/transactions", usd).body().get("id").asText();
    ObjectNode mixed = ApiTestSupport.MAPPER.createObjectNode();
    mixed.set("transaction_ids", ApiTestSupport.MAPPER.valueToTree(List.of(flight, usdId)));
    assertThat(api.patch("/invoices/" + invId, mixed).status()).isEqualTo(409);
    assertMoney(api.get("/invoices/" + invId).body().get("total"), "314.00");

    ObjectNode missing = ApiTestSupport.MAPPER.createObjectNode().put("transaction_id", "txn_nope");
    assertThat(api.patch("/invoices/" + invId, missing).status()).isEqualTo(404);
    assertThat(api.patch("/invoices/inv_nope", body).status()).isEqualTo(404);

    // Rail dropped from the invoice becomes editable again.
    ObjectNode only = ApiTestSupport.MAPPER.createObjectNode().put("transaction_id", flight);
    assertThat(api.patch("/invoices/" + invId, only).status()).isEqualTo(200);
    assertThat(api.delete("/transactions/" + rail).status()).isEqualTo(204);
  }

  @Test
  void transactionListIsPaginatedAndBounded() {
    for (int i = 0; i < 55; i++) {
      ObjectNode b = (ObjectNode) ApiTestSupport.seed("trip_fee");
      b.put("external_id", "bulk-" + i);
      assertThat(api.post("/transactions", b).status()).isEqualTo(201);
    }
    JsonNode def = api.get("/transactions").body();
    assertThat(def.get("limit").asInt()).isEqualTo(50);
    assertThat(def.get("items")).hasSize(50);
    assertThat(def.get("has_more").asBoolean()).isTrue();

    JsonNode second = api.get("/transactions?limit=50&offset=50").body();
    assertThat(second.get("items")).hasSize(5);
    assertThat(second.get("has_more").asBoolean()).isFalse();

    assertThat(api.get("/transactions?limit=1000").status()).isEqualTo(400);
    assertThat(api.get("/transactions?limit=0").status()).isEqualTo(400);
    assertThat(api.get("/transactions?offset=-1").status()).isEqualTo(400);
    assertThat(api.get("/transactions?limit=1").body().get("items")).hasSize(1);
  }

  @Test
  void invoiceListIsPaginatedAndBounded() {
    String id = create("flight");
    for (int i = 0; i < 3; i++) {
      api.post("/invoices", ApiTestSupport.MAPPER.createObjectNode().put("transaction_id", id));
    }
    JsonNode page = api.get("/invoices?limit=2").body();
    assertThat(page.get("items")).hasSize(2);
    assertThat(page.get("has_more").asBoolean()).isTrue();
    assertThat(page.at("/items/0/line_count").asInt()).isEqualTo(1);
    assertThat(api.get("/invoices?limit=101").status()).isEqualTo(400);
  }

  @Test
  void schemaHasRequiredIndexes() {
    List<String> idx =
        jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='index'", String.class);
    assertThat(idx).contains("idx_invoice_lines_tx");
    List<String> uniques =
        jdbc.queryForList(
            "SELECT name FROM pragma_index_list('transactions') WHERE \"unique\" = 1", String.class);
    assertThat(uniques).isNotEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT ii.name FROM pragma_index_list('transactions') il,"
                    + " pragma_index_info(il.name) ii WHERE il.\"unique\" = 1",
                String.class))
        .contains("external_id");
  }
}
