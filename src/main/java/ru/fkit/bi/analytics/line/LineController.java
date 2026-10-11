package ru.fkit.bi.analytics.line;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.analytics.line.LineModels.*;

@RestController
class LineController {
  private final LineService service;
  LineController(LineService service){this.service=service;}
  @GetMapping("/api/v1/enterprises/{enterpriseId}/lines") Page<Line> page(@PathVariable UUID enterpriseId,@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.page(enterpriseId,status,search,page,size);}
  @PostMapping("/api/v1/enterprises/{enterpriseId}/lines") ResponseEntity<Line> create(@PathVariable UUID enterpriseId,@Valid @RequestBody Create in){Line out=service.create(enterpriseId,in);return ResponseEntity.created(URI.create("/api/v1/lines/"+out.id())).body(out);}
  @GetMapping("/api/v1/lines/{lineId}") Line get(@PathVariable UUID lineId){return service.get(lineId);}
  @PutMapping("/api/v1/lines/{lineId}") Line update(@PathVariable UUID lineId,@Valid @RequestBody Update in){return service.update(lineId,in);}
  @PutMapping("/api/v1/lines/{lineId}/status") Line status(@PathVariable UUID lineId,@Valid @RequestBody StatusUpdate in){return service.status(lineId,in);}
}
