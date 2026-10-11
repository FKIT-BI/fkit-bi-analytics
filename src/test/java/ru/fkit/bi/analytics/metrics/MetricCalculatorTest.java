package ru.fkit.bi.analytics.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricCalculatorTest {
  @Test void computesRatiosFromTotalsAndNeverAveragesBucketRatios() {
    var totals=new MetricCalculator.Totals(3_600_000,1_800_000,900_000,600_000,300_000,
        0,0,0,50d,5d,100d,"PCS",2,2,1);

    var result=MetricCalculator.calculate(totals,true,true,true,true);

    assertThat(result.availability()).isEqualTo(0.75d);
    assertThat(result.idealOutput()).isEqualTo(75d);
    assertThat(result.performance()).isCloseTo(2d/3d,org.assertj.core.data.Offset.offset(0.000001d));
    assertThat(result.quality()).isEqualTo(0.9d);
    assertThat(result.oee()).isCloseTo(0.45d,org.assertj.core.data.Offset.offset(0.000001d));
    assertThat(result.mtbfSec()).isEqualTo(1350d);
    assertThat(result.mttrSec()).isEqualTo(300d);
  }

  @Test void missingCoverageAndIncompatibleUnitsRemainExplicit() {
    var totals=new MetricCalculator.Totals(60_000,30_000,0,0,0,0,0,30_000,10d,1d,100d,"PCS",0,0,0);

    var result=MetricCalculator.calculate(totals,false,true,true,true);

    assertThat(result.dataCoverage()).isEqualTo(0.5d);
    assertThat(result.performance()).isNull();
    assertThat(result.quality()).isNull();
    assertThat(result.oee()).isNull();
    assertThat(result.diagnosticCodes()).contains("PARTIAL_COVERAGE","PRODUCTION_UNIT_MISMATCH");
  }
}
