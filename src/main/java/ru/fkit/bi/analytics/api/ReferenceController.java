package ru.fkit.bi.analytics.api;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReferenceController {
  private final JdbcTemplate jdbc;

  public ReferenceController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/api/v1/reference/measurement-units")
  public List<MeasurementUnit> measurementUnits() {
    return jdbc.query("SELECT code,name,symbol,quantity_code FROM equipment.measurement_unit WHERE status='ACTIVE' ORDER BY quantity_code,name,code",
        (rs, row) -> new MeasurementUnit(rs.getString("code"), rs.getString("name"), rs.getString("symbol"), rs.getString("quantity_code")));
  }

  public record MeasurementUnit(String code, String name, String symbol, String quantityCode) {}
}
