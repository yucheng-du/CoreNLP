package edu.stanford.nlp.time;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.util.ArrayCoreMap;
import edu.stanford.nlp.util.CoreMap;
import org.joda.time.DateTimeFieldType;
import org.joda.time.Partial;
import org.joda.time.Period;
import org.junit.Test;

import java.io.File;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

/** ISO week boundaries through the temporal API and the source-tree English rules. */
public class SUTimeWeekRolloverTest {
  @Test
  public void testNextWeekAfter53WeekYear() {
    assertWeek(SUTime.TemporalOp.NEXT, new SUTime.IsoDate(2020, 12, 30),
        "2021-W01", "2021-01-04/2021-01-10");
  }

  @Test
  public void testNextWeekAfter52WeekYear() {
    assertWeek(SUTime.TemporalOp.NEXT, new SUTime.IsoDate(2019, 12, 25),
        "2020-W01", "2019-12-30/2020-01-05");
  }

  @Test
  public void testPreviousWeekAcrossYearBoundary() {
    assertWeek(SUTime.TemporalOp.PREV, new SUTime.IsoDate(2021, 1, 6),
        "2020-W53", "2020-12-28/2021-01-03");
    assertWeek(SUTime.TemporalOp.PREV, new SUTime.IsoDate(2020, 1, 1),
        "2019-W52", "2019-12-23/2019-12-29");
  }

  @Test
  public void testWeekyearCanDifferFromCalendarYear() {
    assertWeek(SUTime.TemporalOp.THIS, new SUTime.IsoDate(2021, 1, 1),
        "2020-W53", "2020-12-28/2021-01-03");
    assertWeek(SUTime.TemporalOp.THIS, new SUTime.IsoDate(2014, 12, 30),
        "2015-W01", "2014-12-29/2015-01-04");
    assertWeek(SUTime.TemporalOp.NEXT, new SUTime.IsoDate(2021, 1, 1),
        "2021-W01", "2021-01-04/2021-01-10");
    assertWeek(SUTime.TemporalOp.NEXT, new SUTime.IsoDate(2014, 12, 30),
        "2015-W02", "2015-01-05/2015-01-11");
  }

  @Test
  public void testOrdinaryWeekOperators() {
    SUTime.Time reference = new SUTime.IsoDate(2017, 6, 15);
    assertWeek(SUTime.TemporalOp.THIS, reference, "2017-W24", "2017-06-12/2017-06-18");
    assertWeek(SUTime.TemporalOp.NEXT, reference, "2017-W25", "2017-06-19/2017-06-25");
    assertWeek(SUTime.TemporalOp.PREV, reference, "2017-W23", "2017-06-05/2017-06-11");
    assertWeek(SUTime.TemporalOp.THIS, new SUTime.IsoDate(2020, 12, 30),
        "2020-W53", "2020-12-28/2021-01-03");
    assertWeek(SUTime.TemporalOp.PREV, new SUTime.IsoDate(2020, 12, 30),
        "2020-W52", "2020-12-21/2020-12-27");
  }

  @Test
  public void testLegacyYearAndWeekPartials() {
    SUTime.PartialTime last = week(2020, 53);
    SUTime.Time next = last.add(SUTime.WEEK);
    assertEquals("2021-W01", next.getTimexValue());
    assertEquals("2021-01-04/2021-01-10", next.getRange().toISOString());
    assertTrue(next.getJodaTimePartial().isSupported(DateTimeFieldType.year()));
    assertFalse(next.getJodaTimePartial().isSupported(DateTimeFieldType.weekyear()));
    assertEquals("2020-W53", last.getTimexValue());
    assertEquals("2020-W53", next.subtract(SUTime.WEEK).getTimexValue());
    assertEquals("2018-W24", week(2017, 24).add(SUTime.YEAR).getTimexValue());
    assertEquals("2020-W53", week(2019, 52).add(new SUTime.DurationWithFields(
        Period.years(1).withWeeks(1))).getTimexValue());
    assertEquals("2021-W52", last.add(new SUTime.DurationWithFields(
        Period.years(1).withWeeks(-1))).getTimexValue());
  }

  @Test
  public void testMixedWeekAndDayPeriodPreservesFieldOrder() {
    SUTime.PartialTime sunday = new SUTime.PartialTime(week(2020, 53).getJodaTimePartial()
        .with(DateTimeFieldType.dayOfWeek(), 7));
    assertEquals("2020-W53-1", sunday.add(new SUTime.DurationWithFields(
        Period.weeks(-1).withDays(1))).getTimexValue());
    assertEquals("2020-W53-7", sunday.getTimexValue());
  }

  @Test
  public void testTextRulesResolveWeekValues() {
    TimeExpressionExtractor extractor = newExtractor();
    assertTimex(extractor, "next week", "2020-12-30", "2021-W01");
    assertTimex(extractor, "this week", "2020-12-30", "2020-W53");
    assertTimex(extractor, "last week", "2020-12-30", "2020-W52");
    assertTimex(extractor, "next week", "2019-12-25", "2020-W01");
    assertTimex(extractor, "this week", "2021-01-01", "2020-W53");
    assertTimex(extractor, "this week", "2014-12-30", "2015-W01");
  }

  @Test
  public void testUnanchoredWeeksStayUnanchored() {
    assertNull(SUTime.TemporalOp.NEXT.apply(null, SUTime.WEEK).getTimexValue());
    assertTimex(newExtractor(), "next week", null, null);
    SUTime.PartialTime unknownYear = new SUTime.PartialTime(
        new Partial(DateTimeFieldType.weekOfWeekyear(), 24));
    assertEquals("XXXX-W25", unknownYear.add(SUTime.WEEK).getTimexValue());
    assertEquals("XXXX-W23", unknownYear.subtract(SUTime.WEEK).getTimexValue());
    assertFalse(unknownYear.add(SUTime.WEEK).getJodaTimePartial()
        .isSupported(DateTimeFieldType.year()));
  }

  private static SUTime.PartialTime week(int year, int week) {
    return new SUTime.PartialTime(new Partial()
        .with(DateTimeFieldType.year(), year)
        .with(DateTimeFieldType.weekOfWeekyear(), week));
  }

  private static void assertWeek(SUTime.TemporalOp op, SUTime.Time reference,
                                 String value, String range) {
    SUTime.Temporal result = op.apply(reference, SUTime.WEEK);
    assertEquals(value, result.getTimexValue());
    assertEquals(range, result.getRange().toISOString());
  }

  private static TimeExpressionExtractor newExtractor() {
    String rules = "src/edu/stanford/nlp/time/rules/";
    Properties props = new Properties();
    props.setProperty("sutime.rules", rules + "defs.sutime.txt," + rules + "english.sutime.txt,"
        + rules + "english.holidays.sutime.txt");
    props.setProperty("sutime.binders", "1");
    props.setProperty("sutime.binder.1", "edu.stanford.nlp.time.JollyDayHolidays");
    props.setProperty("sutime.binder.1.xml",
        new File("src/edu/stanford/nlp/time/holidays/Holidays_sutime.xml").getAbsolutePath());
    props.setProperty("sutime.binder.1.pathtype", "file");
    return new TimeExpressionExtractorImpl("sutime", props);
  }

  private static void assertTimex(TimeExpressionExtractor extractor, String text,
                                  String date, String value) {
    List<CoreLabel> tokens = new PTBTokenizer<>(new StringReader(text),
        new CoreLabelTokenFactory(), "splitHyphenated=false").tokenize();
    for (int i = 0; i < tokens.size(); ++i) {
      tokens.get(i).setIndex(i + 1);
    }
    CoreMap sentence = new ArrayCoreMap();
    sentence.set(CoreAnnotations.TextAnnotation.class, text);
    sentence.set(CoreAnnotations.TokensAnnotation.class, tokens);
    sentence.set(CoreAnnotations.CharacterOffsetBeginAnnotation.class, 0);
    sentence.set(CoreAnnotations.CharacterOffsetEndAnnotation.class, text.length());
    sentence.set(CoreAnnotations.TokenBeginAnnotation.class, 0);
    sentence.set(CoreAnnotations.TokenEndAnnotation.class, tokens.size());
    CoreMap document = new ArrayCoreMap();
    if (date != null) {
      document.set(CoreAnnotations.DocDateAnnotation.class, date);
    }
    List<CoreMap> found = extractor.extractTimeExpressionCoreMaps(sentence, document);
    assertEquals(1, found.size());
    CoreMap expression = found.get(0);
    assertEquals(text, expression.get(CoreAnnotations.TextAnnotation.class));
    assertEquals(Integer.valueOf(0), expression.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class));
    assertEquals(Integer.valueOf(text.length()), expression.get(CoreAnnotations.CharacterOffsetEndAnnotation.class));
    Timex timex = expression.get(TimeAnnotations.TimexAnnotation.class);
    assertNotNull(timex);
    assertEquals("DATE", timex.timexType());
    assertEquals(value, timex.value());
  }
}
