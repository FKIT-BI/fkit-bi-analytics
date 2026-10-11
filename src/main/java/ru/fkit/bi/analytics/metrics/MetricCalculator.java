package ru.fkit.bi.analytics.metrics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Metric formulas operate on additive totals so ratios are never averaged across buckets or machines. */
public final class MetricCalculator {
  private MetricCalculator() { }

  public record Totals(double coveredMs, double runMs, double idleMs, double downMs, double setupMs,
      double plannedStopMs, double effectiveOfflineMs, double underlyingOfflineMs,
      Double output, Double scrap, Double nominalRatePerHour, String productionUnit,
      long stopCount, long failureCount, long recoveryCount) { }

  public static Result calculate(Totals t, boolean productionUnitsCompatible, boolean outputSignalPresent,
      boolean scrapSignalPresent, boolean nominalRatePresent) {
    double plannedMs = t.runMs() + t.idleMs() + t.downMs() + t.setupMs();
    double operatingMs = t.runMs() + t.idleMs();
    Double availability = ratio(operatingMs, plannedMs);
    Double coverage = t.coveredMs() <= 0 ? null : (t.coveredMs() - t.underlyingOfflineMs()) / t.coveredMs();
    Double ideal = !productionUnitsCompatible || t.nominalRatePerHour() == null || operatingMs <= 0
        ? null : t.nominalRatePerHour() * operatingMs / 3_600_000d;
    Double performance = !productionUnitsCompatible || t.output() == null || ideal == null || ideal <= 0
        ? null : t.output() / ideal;
    Double quality = !productionUnitsCompatible || t.output() == null || t.scrap() == null || t.output() <= 0 || t.scrap() > t.output()
        ? null : (t.output() - t.scrap()) / t.output();
    Double oee = availability == null || performance == null || quality == null ? null : availability * performance * quality;
    Double mtbf = t.failureCount() == 0 ? null : operatingMs / t.failureCount() / 1000d;
    Double mttr = t.failureCount() == 0 ? null : t.downMs() / t.failureCount() / 1000d;
    LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
    if (t.coveredMs() <= 0 || t.underlyingOfflineMs() > 0) diagnostics.add("PARTIAL_COVERAGE");
    if (!productionUnitsCompatible) diagnostics.add("PRODUCTION_UNIT_MISMATCH");
    if (!outputSignalPresent) diagnostics.add("OUTPUT_SIGNAL_MISSING");
    if (!scrapSignalPresent) diagnostics.add("SCRAP_SIGNAL_MISSING");
    if (!nominalRatePresent) diagnostics.add("NOMINAL_RATE_MISSING");
    if (t.output() != null && t.scrap() != null && t.scrap() > t.output()) diagnostics.add("SCRAP_EXCEEDS_OUTPUT");
    return new Result(t.coveredMs()/1000d,t.runMs()/1000d,t.idleMs()/1000d,t.downMs()/1000d,t.setupMs()/1000d,
        t.plannedStopMs()/1000d,t.effectiveOfflineMs()/1000d,t.underlyingOfflineMs()/1000d,
        plannedMs/1000d,operatingMs/1000d,t.runMs()/1000d,t.output(),t.scrap(),
        t.output()==null||t.scrap()==null?null:t.output()-t.scrap(),ideal,t.productionUnit(),t.stopCount(),t.failureCount(),t.recoveryCount(),
        availability,performance,quality,oee,coverage,mtbf,mttr,List.copyOf(diagnostics));
  }

  private static Double ratio(double numerator, double denominator) { return denominator <= 0 ? null : numerator / denominator; }

  public record Result(double coveredSec,double runSec,double idleSec,double downSec,double setupSec,double plannedStopSec,
      double effectiveOfflineSec,double underlyingOfflineSec,double plannedProductionSec,double operatingSec,double productiveSec,
      Double output,Double scrap,Double goodOutput,Double idealOutput,String productionUnit,long stopCount,long failureCount,
      long recoveryCount,Double availability,Double performance,Double quality,Double oee,Double dataCoverage,
      Double mtbfSec,Double mttrSec,List<String> diagnosticCodes) { }
}
