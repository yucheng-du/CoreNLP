package edu.stanford.nlp.time;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.PropertiesUtils;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Checks that the tense SUTime finds for one time expression is used for that expression only
 * (issue #1061).
 *
 * <p>The last composite rules in english.sutime.txt tag the value of a time expression next to a
 * past tense verb ({@code VBD} or "have") or a future marker ("will", "going to", ...) with a
 * {@code resolveTo} direction, which decides whether "Tuesday" means the one before or the one
 * after the reference date. If the value they tag is the one the rule file binds to the weekday,
 * which every match of that weekday shares, the direction stays behind for the next "Tuesday",
 * whether in the same sentence, a later sentence or a later document.
 *
 * <p>Most cases run text through a {@link TimeAnnotator} that is reused, the way a pipeline reuses
 * one, and compare the result with what an annotator that has seen nothing else gives. Documents are
 * tokenized and split into sentences by the real tokenizer and sentence splitter; the part of
 * speech tags the tense rules look at are set by hand, so no tagger model is needed. The rules and
 * the holiday calendar are read from the source tree, so this expects the repository root as its
 * working directory.
 *
 * <p>The reference dates are chosen so that the tense changes the answer. 2012-09-22 is a
 * Saturday: the Tuesday before it is the 18th and the Tuesday after it, also the closest, is the
 * 25th. 2010-06-07 is a Monday: the Tuesday before is June 1 and the one after, also the closest,
 * is June 8. 2010-06-10 is a Thursday: the Tuesday before, also the closest, is June 8 and the one
 * after is June 15. So on a Monday only a past tense is visible, and on a Thursday only a future.
 */
public class SUTimeTenseTagITest {

  private static final String RULES = "src/edu/stanford/nlp/time/rules/";
  private static final String HOLIDAYS = "src/edu/stanford/nlp/time/holidays/Holidays_sutime.xml";

  private static final StanfordCoreNLP TOKENIZER =
          new StanfordCoreNLP(PropertiesUtils.asProperties("annotators", "tokenize,ssplit"));

  /** The part of speech of every word used below. */
  private static final Map<String, String> POS = new HashMap<>();
  static {
    String[][] tags = {
        {"He", "PRP"}, {"he", "PRP"}, {"said", "VBD"}, {"left", "VBD"}, {"will", "MD"},
        {"return", "VB"}, {"leave", "VB"}, {"on", "IN"}, {"about", "IN"}, {"near", "IN"},
        {"the", "DT"}, {"old", "JJ"}, {"city", "NN"}, {"park", "NN"}, {"and", "CC"},
        {"is", "VBZ"}, {"deadline", "NN"}, {"Tuesday", "NNP"}, {".", "."} };
    for (String[] tag : tags) {
      POS.put(tag[0], tag[1]);
    }
  }

  private static TimeAnnotator newTimeAnnotator(boolean includeNested) {
    Properties props = new Properties();
    props.setProperty("sutime.rules", RULES + "defs.sutime.txt," + RULES + "english.sutime.txt,"
            + RULES + "english.holidays.sutime.txt");
    props.setProperty("sutime.binders", "1");
    props.setProperty("sutime.binder.1", "edu.stanford.nlp.time.JollyDayHolidays");
    props.setProperty("sutime.binder.1.xml", new File(HOLIDAYS).getAbsolutePath());
    props.setProperty("sutime.binder.1.pathtype", "file");
    if (includeNested) {
      props.setProperty("sutime.includeNested", "true");
    }
    return new TimeAnnotator("sutime", props);
  }

  /** A new tokenized, sentence split and tagged document. */
  private static Annotation document(String text, String docDate) {
    Annotation document = new Annotation(text);
    TOKENIZER.annotate(document);
    for (CoreLabel token : document.get(CoreAnnotations.TokensAnnotation.class)) {
      String tag = POS.get(token.word());
      assertNotNull("no part of speech for " + token.word(), tag);
      token.setTag(tag);
    }
    document.set(CoreAnnotations.DocDateAnnotation.class, docDate);
    return document;
  }

  /** One time expression: its text, TIMEX type and values, offsets in the document, and sentence. */
  private static final class Found {
    final String text;
    final String type;
    final String value;
    final String altValue;
    final int charBegin;
    final int charEnd;
    final int tokenBegin;
    final int tokenEnd;
    final int sentence;

    Found(String text, String type, String value, String altValue,
          int charBegin, int charEnd, int tokenBegin, int tokenEnd, int sentence) {
      this.text = text;
      this.type = type;
      this.value = value;
      this.altValue = altValue;
      this.charBegin = charBegin;
      this.charEnd = charEnd;
      this.tokenBegin = tokenBegin;
      this.tokenEnd = tokenEnd;
      this.sentence = sentence;
    }

    /** The same expression, in a sentence that starts at the given offsets of a longer document. */
    Found movedTo(int chars, int tokens, int sentence) {
      return new Found(text, type, value, altValue, charBegin + chars, charEnd + chars,
              tokenBegin + tokens, tokenEnd + tokens, sentence);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Found)) return false;
      Found found = (Found) o;
      return charBegin == found.charBegin && charEnd == found.charEnd
              && tokenBegin == found.tokenBegin && tokenEnd == found.tokenEnd
              && sentence == found.sentence && text.equals(found.text) && Objects.equals(type, found.type)
              && Objects.equals(value, found.value) && Objects.equals(altValue, found.altValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(text, type, value, altValue, charBegin, charEnd, tokenBegin, tokenEnd, sentence);
    }

    @Override
    public String toString() {
      return text + " " + type + " " + value + (altValue != null ? " alt_value=" + altValue : "")
              + " chars " + charBegin + '-' + charEnd + " tokens " + tokenBegin + '-' + tokenEnd
              + " sentence " + sentence;
    }
  }

  /** A "Tuesday" that resolves to a single date, with no alt_value. */
  private static Found tuesday(String value, int charBegin, int tokenBegin, int sentence) {
    return new Found("Tuesday", "DATE", value, null, charBegin, charBegin + 7, tokenBegin, tokenBegin + 1, sentence);
  }

  /** Annotate the document and list what was found, checking the offsets against the document itself. */
  private static List<Found> annotate(TimeAnnotator annotator, Annotation document) {
    annotator.annotate(document);
    String text = document.get(CoreAnnotations.TextAnnotation.class);
    List<CoreLabel> tokens = document.get(CoreAnnotations.TokensAnnotation.class);
    List<Found> found = new ArrayList<>();
    for (CoreMap cm : document.get(TimeAnnotations.TimexAnnotations.class)) {
      Timex timex = cm.get(TimeAnnotations.TimexAnnotation.class);
      Found f = new Found(cm.get(CoreAnnotations.TextAnnotation.class), timex.timexType(), timex.value(), timex.altVal(),
              cm.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class),
              cm.get(CoreAnnotations.CharacterOffsetEndAnnotation.class),
              cm.get(CoreAnnotations.TokenBeginAnnotation.class), cm.get(CoreAnnotations.TokenEndAnnotation.class),
              cm.get(CoreAnnotations.SentenceIndexAnnotation.class));
      assertEquals(f.text, text.substring(f.charBegin, f.charEnd));
      assertEquals(f.charBegin, tokens.get(f.tokenBegin).beginPosition());
      assertEquals(f.charEnd, tokens.get(f.tokenEnd - 1).endPosition());
      found.add(f);
    }
    return found;
  }

  /** The index in the document of the token that starts at the given character offset. */
  private static int tokenAt(Annotation document, int charOffset) {
    List<CoreLabel> tokens = document.get(CoreAnnotations.TokensAnnotation.class);
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i).beginPosition() == charOffset) {
        return i;
      }
    }
    throw new IllegalArgumentException("no token starts at " + charOffset);
  }

  // --------------------------------------------------------- one document after another

  private static final String A = "Tuesday";
  private static final String A_DATE = "2012-09-22";
  private static final String B = "said on Tuesday";
  private static final String B_DATE = "2010-06-07";

  @Test
  public void testTenseOfOneDocumentDoesNotCarryOverToTheNext() {
    List<Found> freshA = annotate(newTimeAnnotator(false), document(A, A_DATE));
    List<Found> freshB = annotate(newTimeAnnotator(false), document(B, B_DATE));
    // No tense: the closest Tuesday.  "said": the Tuesday before, not the closest one, June 8.
    assertEquals(Collections.singletonList(tuesday("2012-09-25", 0, 0, 0)), freshA);
    assertEquals(Collections.singletonList(tuesday("2010-06-01", 8, 2, 0)), freshB);

    TimeAnnotator annotator = newTimeAnnotator(false);
    List<Found> firstA = annotate(annotator, document(A, A_DATE));
    List<Found> b = annotate(annotator, document(B, B_DATE));
    List<Found> secondA = annotate(annotator, document(A, A_DATE));
    assertEquals(freshA, firstA);
    assertEquals(freshB, b);
    // Taking the past tense of B along would make this 2012-09-18.
    assertEquals(firstA, secondA);
    assertEquals(freshA, secondA);
  }

  // ------------------------------------------------- two tenses in one sentence

  /**
   * Each cue has the one or two tokens before "Tuesday" that the tense rules allow, and the cues are
   * far enough apart that neither reaches the other Tuesday.
   */
  private static final String TWO_TENSES = "He left on Tuesday near the old city park and he will return on Tuesday.";
  private static final String PAST_CLAUSE = "He left on Tuesday near the old city park.";
  private static final String FUTURE_CLAUSE = "he will return on Tuesday.";

  private static void checkTwoTensesInOneSentence(String docDate, String past, String future) {
    // Each clause alone, each on an annotator of its own.
    List<Found> pastClause = annotate(newTimeAnnotator(false), document(PAST_CLAUSE, docDate));
    List<Found> futureClause = annotate(newTimeAnnotator(false), document(FUTURE_CLAUSE, docDate));
    assertEquals(Collections.singletonList(tuesday(past, 11, 3, 0)), pastClause);
    assertEquals(Collections.singletonList(tuesday(future, 18, 4, 0)), futureClause);

    // Twice through one annotator: the second document must not see what the first one tagged either.
    TimeAnnotator annotator = newTimeAnnotator(false);
    for (int i = 0; i < 2; i++) {
      Annotation document = document(TWO_TENSES, docDate);
      int chars = TWO_TENSES.indexOf(FUTURE_CLAUSE);
      int tokens = tokenAt(document, chars);
      assertEquals(Arrays.asList(pastClause.get(0), futureClause.get(0).movedTo(chars, tokens, 0)),
              annotate(annotator, document));
    }
  }

  @Test
  public void testTwoTensesOfTheSameWeekdayInOneSentence() {
    // On a Monday the past tense of the first Tuesday is visible ...
    assertEquals(Collections.singletonList(tuesday("2010-06-08", 0, 0, 0)),
            annotate(newTimeAnnotator(false), document("Tuesday", "2010-06-07")));
    checkTwoTensesInOneSentence("2010-06-07", "2010-06-01", "2010-06-08");
    // ... and on a Thursday the future tense of the second one.
    assertEquals(Collections.singletonList(tuesday("2010-06-08", 0, 0, 0)),
            annotate(newTimeAnnotator(false), document("Tuesday", "2010-06-10")));
    checkTwoTensesInOneSentence("2010-06-10", "2010-06-08", "2010-06-15");
  }

  // --------------------------------------------------- one sentence after another

  /** Each sentence of the document should come out as it does in a document of its own. */
  private static void checkSentencesOfOneDocument(String text, String docDate, String... values) {
    Annotation document = document(text, docDate);
    List<Found> found = annotate(newTimeAnnotator(false), document);
    List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
    assertEquals(values.length, sentences.size());
    List<Found> expected = new ArrayList<>();
    for (int i = 0; i < sentences.size(); i++) {
      CoreMap sentence = sentences.get(i);
      List<Found> alone = annotate(newTimeAnnotator(false),
              document(sentence.get(CoreAnnotations.TextAnnotation.class), docDate));
      assertEquals(1, alone.size());
      assertEquals(values[i], alone.get(0).value);
      expected.add(alone.get(0).movedTo(sentence.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class),
              sentence.get(CoreAnnotations.TokenBeginAnnotation.class), i));
    }
    assertEquals(expected, found);
  }

  @Test
  public void testTenseOfOneSentenceDoesNotCarryOverToTheNext() {
    // The last Tuesday has no tense.  After a past tense it would become June 1 ...
    checkSentencesOfOneDocument("He will return on Tuesday. He left on Tuesday. Tuesday is the deadline.",
            "2010-06-07", "2010-06-08", "2010-06-01", "2010-06-08");
    // ... and after a future tense June 15.
    checkSentencesOfOneDocument("He left on Tuesday. He will return on Tuesday. Tuesday is the deadline.",
            "2010-06-10", "2010-06-08", "2010-06-15", "2010-06-08");
  }

  // ------------------------------------------------ an expression inside another

  /**
   * "about Tuesday" (temporal-composite-6c) takes over the value of the "Tuesday" inside it, and the
   * tense rules then tag "about Tuesday". That tense should hold for the nested "Tuesday" too, which
   * is reported when sutime.includeNested is set.
   */
  private static final String SAID_ABOUT = "He said about Tuesday.";
  private static final List<Found> SAID_ABOUT_FOUND = Arrays.asList(tuesday("2010-06-01", 14, 3, 0),
          new Found("about Tuesday", "DATE", "2010-06-01", null, 8, 21, 2, 4, 0));

  @Test
  public void testNestedExpressionHasTheTenseOfTheExpressionAroundIt() {
    assertEquals(SAID_ABOUT_FOUND, annotate(newTimeAnnotator(true), document(SAID_ABOUT, "2010-06-07")));
    assertEquals(Arrays.asList(tuesday("2010-06-15", 20, 4, 0),
                    new Found("about Tuesday", "DATE", "2010-06-15", null, 14, 27, 3, 5, 0)),
            annotate(newTimeAnnotator(true), document("He will leave about Tuesday.", "2010-06-10")));
  }

  @Test
  public void testTenseOfAnExpressionAroundAnotherDoesNotCarryOver() {
    List<Found> fresh = annotate(newTimeAnnotator(true), document(A, B_DATE));
    assertEquals(Collections.singletonList(tuesday("2010-06-08", 0, 0, 0)), fresh);
    TimeAnnotator annotator = newTimeAnnotator(true);
    assertEquals(SAID_ABOUT_FOUND, annotate(annotator, document(SAID_ABOUT, B_DATE)));
    assertEquals(fresh, annotate(annotator, document(A, B_DATE)));
  }

}
