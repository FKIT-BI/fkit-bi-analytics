package ru.fkit.bi.analytics.shift;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShiftCalendarTest {
  private static final UUID ID=UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID NEXT_ID=UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static ShiftCalendar.Definition d(String start,String end,LocalDate from,LocalDate to){return new ShiftCalendar.Definition(ID,LocalTime.parse(start),LocalTime.parse(end),from,to);}
  private static ShiftCalendar.Definition d(UUID id,String start,String end,LocalDate from,LocalDate to){return new ShiftCalendar.Definition(id,LocalTime.parse(start),LocalTime.parse(end),from,to);}

  @Test void resolvesNightShiftToStartDateAndUsesHalfOpenEnd(){
    var shift=d("20:00","08:00",LocalDate.parse("2026-01-01"),null);
    var inside=ShiftCalendar.resolve(Instant.parse("2026-01-03T03:15:00Z"),ZoneId.of("UTC"),List.of(shift));
    assertThat(inside.shiftDate()).isEqualTo(LocalDate.parse("2026-01-02"));
    assertThat(inside.start()).isEqualTo(Instant.parse("2026-01-02T20:00:00Z"));
    assertThat(ShiftCalendar.resolve(Instant.parse("2026-01-04T08:00:00Z"),ZoneId.of("UTC"),List.of(shift))).isNull();
  }

  @Test void exactCrossMidnightAndSharedBoundariesBelongToCorrectOccurrences(){
    var night=d(ID,"20:00","08:00",LocalDate.parse("2026-01-01"),null);
    var exactStart=ShiftCalendar.resolve(Instant.parse("2026-01-02T20:00:00Z"),ZoneId.of("UTC"),List.of(night));
    assertThat(exactStart.shiftId()).isEqualTo(ID);
    assertThat(exactStart.shiftDate()).isEqualTo(LocalDate.parse("2026-01-02"));
    assertThat(ShiftCalendar.resolve(Instant.parse("2026-01-03T08:00:00Z"),ZoneId.of("UTC"),List.of(night))).isNull();

    var morning=d(ID,"08:00","16:00",LocalDate.parse("2026-01-01"),null);
    var evening=d(NEXT_ID,"16:00","20:00",LocalDate.parse("2026-01-01"),null);
    var shared=ShiftCalendar.resolve(Instant.parse("2026-01-02T16:00:00Z"),ZoneId.of("UTC"),List.of(morning,evening));
    assertThat(shared.shiftId()).isEqualTo(NEXT_ID);
    assertThat(shared.shiftDate()).isEqualTo(LocalDate.parse("2026-01-02"));
  }

  @Test void resolvesMidnightGapAndTwentyFourHourShift(){
    var morning=d("08:00","16:00",LocalDate.parse("2026-01-01"),null);
    var evening=d("16:00","20:00",LocalDate.parse("2026-01-01"),null);
    assertThat(ShiftCalendar.resolve(Instant.parse("2026-01-02T03:00:00Z"),ZoneId.of("UTC"),List.of(morning,evening))).isNull();
    var fullDay=d("07:00","07:00",LocalDate.parse("2026-01-01"),null);
    assertThat(ShiftCalendar.resolve(Instant.parse("2026-01-02T06:59:00Z"),ZoneId.of("UTC"),List.of(fullDay)).shiftDate()).isEqualTo(LocalDate.parse("2026-01-01"));
  }

  @Test void resolvesDstGapToFirstInstantAndOverlapWithEarlierOffset(){
    ZoneId london=ZoneId.of("Europe/London");
    Instant gap=ShiftCalendar.boundary(LocalDate.parse("2026-03-29").atTime(1,30),london);
    Instant overlap=ShiftCalendar.boundary(LocalDate.parse("2026-10-25").atTime(1,30),london);
    assertThat(gap).isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
    assertThat(overlap).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
  }

  @Test void detectsDateAndCircularTimeOverlapButAllowsGaps(){
    var overnight=d("20:00","04:00",LocalDate.parse("2026-01-01"),null);
    var overlapping=d("03:00","08:00",LocalDate.parse("2026-02-01"),null);
    var gap=d("08:00","16:00",LocalDate.parse("2026-01-01"),null);
    assertThat(ShiftCalendar.overlaps(overnight,overlapping)).isTrue();
    assertThat(ShiftCalendar.overlaps(overnight,gap)).isFalse();
  }
}
