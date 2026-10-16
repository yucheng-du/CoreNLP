package edu.stanford.nlp.time;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.tokensregex.Env;
import edu.stanford.nlp.ling.tokensregex.MatchedExpression;
import edu.stanford.nlp.ling.tokensregex.TokenSequencePattern;
import edu.stanford.nlp.ling.tokensregex.types.Expressions;
import edu.stanford.nlp.ling.tokensregex.types.Tags;
import edu.stanford.nlp.ling.tokensregex.types.Value;
import edu.stanford.nlp.ling.tokensregex.types.ValueFunctions;
import edu.stanford.nlp.pipeline.CoreMapAggregator;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.util.ArrayCoreMap;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Interval;

import org.junit.Test;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests that each TimeExpression made from a match has a value it can tag without tagging any
 * other expression (issue #1061).
 *
 * <p>A rule such as {@code { (/tuesdays?/) => TUESDAY }} gives every match the one value that
 * defs.sutime.txt binds to TUESDAY. The matches here are annotated the way
 * {@code CoreMapExpressionExtractor} does it, through {@link MatchedExpression#extractAnnotation}
 * and the converter that SUTime installs to publish a {@link TimeExpression}, and the published
 * values are tagged with the same {@code VTag} function the tense rules of english.sutime.txt use.
 *
 * <p>SUTimeTenseTagITest checks the same thing through {@link TimeAnnotator} and the real rules.
 */
public class TimeExpressionTest {

  private static final Env ENV = TokenSequencePattern.getNewEnv();

  /** "Tuesday and Tuesday": token offsets are set as NumberNormalizer sets them. */
  private static CoreMap sentence() {
    String text = "Tuesday and Tuesday";
    List<CoreLabel> tokens = new PTBTokenizer<>(new StringReader(text), new CoreLabelTokenFactory(),
            "splitHyphenated=false").tokenize();
    for (int i = 0; i < tokens.size(); i++) {
      tokens.get(i).setIndex(i + 1);
      tokens.get(i).set(CoreAnnotations.TokenBeginAnnotation.class, i);
      tokens.get(i).set(CoreAnnotations.TokenEndAnnotation.class, i + 1);
    }
    CoreMap sentence = new ArrayCoreMap();
    sentence.set(CoreAnnotations.TextAnnotation.class, text);
    sentence.set(CoreAnnotations.TokensAnnotation.class, tokens);
    sentence.set(CoreAnnotations.CharacterOffsetBeginAnnotation.class, 0);
    sentence.set(CoreAnnotations.CharacterOffsetEndAnnotation.class, text.length());
    sentence.set(CoreAnnotations.TokenBeginAnnotation.class, 0);
    sentence.set(CoreAnnotations.TokenEndAnnotation.class, tokens.size());
    return sentence;
  }

  /** Extracts the given value for every match, and publishes a TimeExpression the way SUTime's rules do. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static MatchedExpression.SingleAnnotationExtractor extractor(Function<CoreMap, Value> value) {
    MatchedExpression.SingleAnnotationExtractor extractor = new MatchedExpression.SingleAnnotationExtractor();
    extractor.priority = 2.5;
    extractor.weight = 0.75;
    extractor.valueExtractor = value;
    extractor.tokensAnnotationField = CoreAnnotations.TokensAnnotation.class;
    extractor.resultAnnotationField = Collections.singletonList((Class) TimeExpression.Annotation.class);
    extractor.resultNestedAnnotationField = TimeExpression.ChildrenAnnotation.class;
    extractor.resultAnnotationExtractor = TimeExpression.TimeExpressionConverter;
    extractor.tokensAggregator = CoreMapAggregator.getDefaultAggregator();
    return extractor;
  }

  /** The TimeExpression published for a match, after checking that it describes the same match. */
  private static TimeExpression published(MatchedExpression matched) {
    TimeExpression te = matched.getAnnotation().get(TimeExpression.Annotation.class);
    assertNotNull(te);
    assertNotSame(matched, te);
    assertSame(matched.getAnnotation(), te.getAnnotation());
    assertEquals(matched.getText(), te.getText());
    assertEquals(matched.getCharOffsets(), te.getCharOffsets());
    assertEquals(matched.getTokenOffsets(), te.getTokenOffsets());
    assertEquals(matched.getPriority(), te.getPriority(), 0.0);
    assertEquals(matched.getWeight(), te.getWeight(), 0.0);
    assertEquals(matched.getOrder(), te.getOrder());
    assertEquals(matched.isIncludeNested(), te.isIncludeNested());
    return te;
  }

  private static TimeExpression match(MatchedExpression.SingleAnnotationExtractor extractor, CoreMap sentence,
                                      int tokenBegin, int tokenEnd) {
    MatchedExpression matched = extractor.createMatchedExpression(null, Interval.toInterval(tokenBegin, tokenEnd));
    assertTrue(matched.extractAnnotation(ENV, sentence));
    TimeExpression te = published(matched);
    assertEquals("Tuesday", te.getText());
    return te;
  }

  /** What defs.sutime.txt binds to TUESDAY, with the given tags. */
  private static Value tuesday(Tags tags) {
    Value value = new Expressions.PrimitiveValue<>(SUTime.StandardTemporalType.DAY_OF_WEEK.name(),
            SUTime.StandardTemporalType.DAY_OF_WEEK.createTemporal(2));
    value.setTags(tags);
    return value;
  }

  /** VTag(value, tag, tagValue), as the tense rules call it. */
  private static void vtag(Value value, String tag, int tagValue) {
    ValueFunctions.SET_VALUE_TAG_FUNCTION.apply(ENV, Arrays.asList(value,
            new Expressions.PrimitiveValue<>(Expressions.TYPE_STRING, tag),
            new Expressions.PrimitiveValue<>(Expressions.TYPE_NUMBER, tagValue)));
  }

  /** RemoveVTag(value, tag). */
  private static void removeVTag(Value value, String tag) {
    ValueFunctions.REMOVE_VALUE_TAG_FUNCTION.apply(ENV, Arrays.asList(value,
            new Expressions.PrimitiveValue<>(Expressions.TYPE_STRING, tag)));
  }

  private static Object tagValue(Value value, String tag) {
    Value v = value.getTags().getTag(tag);
    return (v != null) ? v.get() : null;
  }

  /** The value of the match is that of the rule, with tags of its own. */
  private static void assertCopyOf(Value template, TimeExpression te) {
    Value value = te.getValue();
    assertNotSame(template, value);
    assertSame(Expressions.PrimitiveValue.class, value.getClass());
    assertEquals(template.getType(), value.getType());
    assertSame(template.get(), value.get());
    assertSame(template.get(), te.getTemporal());
    if (template.getTags() == null) {
      assertNull(value.getTags());
    } else {
      assertNotSame(template.getTags(), value.getTags());
      assertEquals(template.getTags(), value.getTags());
    }
  }

  @Test
  public void testMatchesOfOneValueKeepItsTagsButEachTagItsOwn() {
    Tags tags = new Tags("flag");   // a tag without a value
    Value label = new Expressions.PrimitiveValue<>(Expressions.TYPE_STRING, "weekday");
    tags.setTag("label", label);
    Value template = tuesday(tags);
    MatchedExpression.SingleAnnotationExtractor extractor = extractor(cm -> template);
    CoreMap sentence = sentence();
    TimeExpression first = match(extractor, sentence, 0, 1);
    TimeExpression second = match(extractor, sentence, 2, 3);

    for (TimeExpression te : Arrays.asList(first, second)) {
      assertCopyOf(template, te);
      Tags copied = te.getValue().getTags();
      assertEquals(new HashSet<>(Arrays.asList("flag", "label")), new HashSet<>(copied.getTags()));
      assertTrue(copied.hasTag("flag"));
      assertNull(copied.getTag("flag"));
      assertSame(label, copied.getTag("label"));
    }
    assertNotSame(first.getValue(), second.getValue());
    assertNotSame(first.getValue().getTags(), second.getValue().getTags());

    vtag(first.getValue(), "resolveTo", SUTime.RESOLVE_TO_PAST);
    assertEquals(SUTime.RESOLVE_TO_PAST, tagValue(first.getValue(), "resolveTo"));
    assertFalse(second.getValue().getTags().hasTag("resolveTo"));
    assertFalse(tags.hasTag("resolveTo"));

    removeVTag(second.getValue(), "label");
    assertFalse(second.getValue().getTags().hasTag("label"));
    assertSame(label, first.getValue().getTags().getTag("label"));
    assertSame(label, tags.getTag("label"));

    // A later match starts from the rule's tags, not from what was done to the earlier ones.
    TimeExpression third = match(extractor, sentence(), 0, 1);
    assertCopyOf(template, third);
    assertFalse(third.getValue().getTags().hasTag("resolveTo"));
    assertSame(label, third.getValue().getTags().getTag("label"));
  }

  @Test
  public void testMatchesOfAValueWithEmptyTagsEachTagTheirOwn() {
    Value template = tuesday(new Tags());
    MatchedExpression.SingleAnnotationExtractor extractor = extractor(cm -> template);
    CoreMap sentence = sentence();
    TimeExpression first = match(extractor, sentence, 0, 1);
    TimeExpression second = match(extractor, sentence, 2, 3);
    assertCopyOf(template, first);
    assertCopyOf(template, second);
    assertTrue(first.getValue().getTags().getTags().isEmpty());
    assertNotSame(first.getValue().getTags(), second.getValue().getTags());

    vtag(second.getValue(), "resolveTo", SUTime.RESOLVE_TO_FUTURE);
    assertEquals(SUTime.RESOLVE_TO_FUTURE, tagValue(second.getValue(), "resolveTo"));
    assertTrue(first.getValue().getTags().getTags().isEmpty());
    assertTrue(template.getTags().getTags().isEmpty());
  }

  @Test
  public void testMatchesOfAValueWithoutTagsEachTagTheirOwn() {
    Value template = tuesday(null);
    MatchedExpression.SingleAnnotationExtractor extractor = extractor(cm -> template);
    CoreMap sentence = sentence();
    TimeExpression first = match(extractor, sentence, 0, 1);
    TimeExpression second = match(extractor, sentence, 2, 3);
    assertCopyOf(template, first);
    assertCopyOf(template, second);
    assertNotSame(first.getValue(), second.getValue());

    // VTag gives a value without tags a new Tags object: that of the first match only.
    vtag(first.getValue(), "resolveTo", SUTime.RESOLVE_TO_PAST);
    assertEquals(SUTime.RESOLVE_TO_PAST, tagValue(first.getValue(), "resolveTo"));
    assertNull(second.getValue().getTags());
    assertNull(template.getTags());
  }

  @Test
  public void testMatchesOfAValueWithTagsWithoutAMapEachTagTheirOwn() {
    // A Tags made from a null array has no map until something is tagged; getTags() fails on it.
    Value template = tuesday(new Tags((String[]) null));
    MatchedExpression.SingleAnnotationExtractor extractor = extractor(cm -> template);
    CoreMap sentence = sentence();
    TimeExpression first = match(extractor, sentence, 0, 1);
    TimeExpression second = match(extractor, sentence, 2, 3);
    assertCopyOf(template, first);
    assertCopyOf(template, second);
    assertNotSame(first.getValue().getTags(), second.getValue().getTags());

    vtag(first.getValue(), "resolveTo", SUTime.RESOLVE_TO_PAST);
    assertEquals(SUTime.RESOLVE_TO_PAST, tagValue(first.getValue(), "resolveTo"));
    assertFalse(second.getValue().getTags().hasTag("resolveTo"));
    assertFalse(template.getTags().hasTag("resolveTo"));
  }

  /**
   * A composite rule whose result is the value of one of its parts, such as
   * {@code result: $0[0].temporal.value}, keeps sharing that value with the part, as before: it
   * already belongs to this match alone, and a tense given to the whole should hold for the part.
   */
  @Test
  public void testCompositeSharesTheValueOfThePartItComesFrom() {
    Value template = tuesday(new Tags());
    MatchedExpression.SingleAnnotationExtractor extractor = extractor(cm -> template);
    CoreMap sentence = sentence();
    TimeExpression first = match(extractor, sentence, 0, 1);
    TimeExpression second = match(extractor, sentence, 2, 3);
    List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
    // What the composite rules match over: the expressions found so far, and the tokens between them.
    List<CoreMap> chunks = Arrays.asList(first.getAnnotation(), tokens.get(1), second.getAnnotation());

    MatchedExpression.SingleAnnotationExtractor composite = extractor(cm ->
            cm.get(TimeExpression.ChildrenAnnotation.class).get(0).get(TimeExpression.Annotation.class).getValue());
    MatchedExpression matched = composite.createMatchedExpression(null, Interval.toInterval(0, 2));
    assertTrue(matched.extractAnnotation(ENV, chunks));
    TimeExpression whole = published(matched);
    assertEquals("Tuesday and", whole.getText());
    assertSame(first.getValue(), whole.getValue());
    assertNotSame(template, whole.getValue());

    vtag(whole.getValue(), "resolveTo", SUTime.RESOLVE_TO_FUTURE);
    assertEquals(SUTime.RESOLVE_TO_FUTURE, tagValue(first.getValue(), "resolveTo"));
    assertFalse(second.getValue().getTags().hasTag("resolveTo"));
    assertFalse(template.getTags().hasTag("resolveTo"));
  }

}
