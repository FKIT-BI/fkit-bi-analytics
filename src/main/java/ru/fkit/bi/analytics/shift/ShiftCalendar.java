package ru.fkit.bi.analytics.shift;

import java.time.*;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.UUID;

/** Pure event-time resolver for recurring local shift definitions. */
public final class ShiftCalendar {
  private ShiftCalendar() {}
  public record Definition(UUID id, LocalTime start, LocalTime end, LocalDate validFrom, LocalDate validTo) {}
  public record Occurrence(UUID shiftId, LocalDate shiftDate, Instant start, Instant end) {}

  public static Occurrence resolve(Instant observedAt, ZoneId zone, List<Definition> definitions) {
    LocalDate localDate=observedAt.atZone(zone).toLocalDate();
    Occurrence match=null;
    for (Definition d : definitions) {
      for (LocalDate candidate : List.of(localDate,localDate.minusDays(1))) {
        if (candidate.isBefore(d.validFrom()) || (d.validTo()!=null && !candidate.isBefore(d.validTo()))) continue;
        LocalDateTime localStart=candidate.atTime(d.start());
        LocalDate endDate=d.end().isAfter(d.start()) ? candidate : candidate.plusDays(1);
        LocalDateTime localEnd=endDate.atTime(d.end());
        Instant start=boundary(localStart,zone), end=boundary(localEnd,zone);
        if (!observedAt.isBefore(start) && observedAt.isBefore(end)) {
          if (match!=null) throw new IllegalStateException("Overlapping shift configuration produced ambiguous occurrence");
          match=new Occurrence(d.id(),candidate,start,end);
        }
      }
    }
    return match;
  }

  public static boolean overlaps(Definition a, Definition b) {
    LocalDate from=a.validFrom().isAfter(b.validFrom())?a.validFrom():b.validFrom();
    LocalDate aTo=a.validTo(),bTo=b.validTo();
    LocalDate to=aTo==null?bTo:bTo==null?aTo:aTo.isBefore(bTo)?aTo:bTo;
    if (to!=null && !from.isBefore(to)) return false;
    return intervals(a.start(),a.end()).stream().anyMatch(x->intervals(b.start(),b.end()).stream().anyMatch(y->x[0]<y[1]&&y[0]<x[1]));
  }
  private static List<int[]> intervals(LocalTime start,LocalTime end) {
    int s=start.toSecondOfDay()/60,e=end.toSecondOfDay()/60;
    if(s==e)return List.of(new int[]{0,1440});
    if(s<e)return List.of(new int[]{s,e});
    return List.of(new int[]{s,1440},new int[]{0,e});
  }
  public static Instant boundary(LocalDateTime local, ZoneId zone) {
    ZoneRules rules=zone.getRules();
    List<ZoneOffset> offsets=rules.getValidOffsets(local);
    if(offsets.size()==1)return local.toInstant(offsets.getFirst());
    if(offsets.size()>1)return local.toInstant(offsets.getFirst()); // earlier offset gives earlier instant
    ZoneOffsetTransition transition=rules.getTransition(local);
    return transition.getDateTimeAfter().toInstant(transition.getOffsetAfter());
  }
}
