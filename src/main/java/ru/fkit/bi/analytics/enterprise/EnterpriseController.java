package ru.fkit.bi.analytics.enterprise;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.analytics.enterprise.EnterpriseModels.*;

@RestController
@RequestMapping("/api/v1/enterprises")
public class EnterpriseController {
  private final EnterpriseService service;
  EnterpriseController(EnterpriseService service) { this.service=service; }
  @GetMapping public Page<Enterprise> page(@RequestParam(defaultValue="ACTIVE") String status,
      @RequestParam(required=false) String search, @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="50") int size) { return service.page(status,search,page,size); }
  @PostMapping public ResponseEntity<Enterprise> create(@Valid @RequestBody Create input) {
    Enterprise result=service.create(input);
    return ResponseEntity.created(URI.create("/api/v1/enterprises/"+result.id())).body(result);
  }
  @GetMapping("/{enterpriseId}") public Enterprise get(@PathVariable UUID enterpriseId) { return service.get(enterpriseId); }
  @PutMapping("/{enterpriseId}") public Enterprise update(@PathVariable UUID enterpriseId,@Valid @RequestBody Update input) { return service.update(enterpriseId,input); }
  @PutMapping("/{enterpriseId}/status") public Enterprise status(@PathVariable UUID enterpriseId,@Valid @RequestBody StatusUpdate input) { return service.status(enterpriseId,input); }
}
