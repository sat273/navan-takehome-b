package com.example.travel.api;

import com.example.travel.model.Invoice;
import com.example.travel.model.InvoiceSummary;
import com.example.travel.model.Page;
import com.example.travel.service.InvoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {
  private final InvoiceService service;

  public InvoiceController(InvoiceService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<Invoice> create(@RequestBody JsonNode body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
  }

  @GetMapping("/{id}")
  public Invoice get(@PathVariable String id) {
    return service.get(id);
  }

  @GetMapping
  public Page<InvoiceSummary> list(
      @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
    Paging.check(limit, offset);
    return service.list(limit, offset);
  }

  @PatchMapping("/{id}")
  public Invoice replace(@PathVariable String id, @RequestBody JsonNode body) {
    return service.replace(id, body);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
