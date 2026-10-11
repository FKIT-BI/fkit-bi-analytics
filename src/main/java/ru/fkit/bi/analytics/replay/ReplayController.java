package ru.fkit.bi.analytics.replay;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static ru.fkit.bi.analytics.replay.ReplayModels.*;

@RestController
public class ReplayController {
  private final ReplayService service;
  public ReplayController(ReplayService service) { this.service = service; }

  @PostMapping("/api/v1/machines/{machineId}/state-recalculations")
  public ResponseEntity<Job> create(@PathVariable UUID machineId, @RequestBody Create request) {
    Job job = service.create(machineId, request);
    return ResponseEntity.accepted().location(URI.create("/api/v1/state-recalculations/" + job.id())).body(job);
  }

  @GetMapping("/api/v1/state-recalculations/{recalculationId}")
  public ResponseEntity<?> get(@PathVariable UUID recalculationId) {
    Job job = service.get(recalculationId);
    return job == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NOT_FOUND")) : ResponseEntity.ok(job);
  }

  @DeleteMapping("/api/v1/state-recalculations/{recalculationId}")
  public Job cancel(@PathVariable UUID recalculationId) { return service.cancel(recalculationId); }

  @GetMapping("/api/v1/machines/{machineId}/state-recalculations")
  public Map<String,Object> list(@PathVariable UUID machineId, @RequestParam(required=false) String status,
      @RequestParam(defaultValue="100") int limit, @RequestParam(required=false) String cursor) {
    if(limit<1||limit>100)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"limit must be between 1 and 100");
    java.time.Instant beforeAt=null;UUID beforeId=null;
    if(cursor!=null&&!cursor.isBlank())try{
      String[] parts=new String(java.util.Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
      if(parts.length!=2)throw new IllegalArgumentException();beforeAt=java.time.Instant.parse(parts[0]);beforeId=UUID.fromString(parts[1]);
    }catch(RuntimeException malformed){throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid cursor");}
    List<Job> page = service.list(machineId, status, limit+1,beforeAt,beforeId);
    boolean hasMore=page.size()>limit;List<Job> items=hasMore?page.subList(0,limit):page;
    Map<String,Object> cursorMetadata = new LinkedHashMap<>();
    cursorMetadata.put("limit", limit);
    cursorMetadata.put("hasMore", hasMore);
    String next=null;if(hasMore&&!items.isEmpty()){Job last=items.getLast();next=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt()+"|"+last.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    cursorMetadata.put("nextCursor", next);
    return Map.of("items", items, "cursor", cursorMetadata);
  }
}
