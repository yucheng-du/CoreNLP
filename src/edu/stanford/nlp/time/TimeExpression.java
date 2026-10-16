package edu.stanford.nlp.time;

import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.tokensregex.Env;
import edu.stanford.nlp.ling.tokensregex.MatchedExpression;
import edu.stanford.nlp.ling.tokensregex.types.Expressions;
import edu.stanford.nlp.ling.tokensregex.types.Tags;
import edu.stanford.nlp.ling.tokensregex.types.Value;
import edu.stanford.nlp.pipeline.CoreMapAggregator;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.ErasureUtils;
import edu.stanford.nlp.util.Interval;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Time Expression.
 *
 * @author Angel Chang
 */
public class TimeExpression extends MatchedExpression {

  /**
   * The CoreMap key for storing a SUTime.TimeIndex (for looking up Timex Id).
   */
  public static class TimeIndexAnnotation implements CoreAnnotation<SUTime.TimeIndex> {
    @Override
    public Class<SUTime.TimeIndex> getType() {
      return SUTime.TimeIndex.class;
    }
  }

  // todo [cdm 2016]: Rename this class!
  /**
   * The CoreMap key for storing a TimeExpression annotation.
   */
  public static class Annotation implements CoreAnnotation<TimeExpression> {
    @Override
    public Class<TimeExpression> getType() {
      return TimeExpression.class;
    }
  }

  /**
   * The CoreMap key for storing a nested annotations.
   */
  public static class ChildrenAnnotation implements CoreAnnotation<List<? extends CoreMap>> {
    @Override
    public Class<List<? extends CoreMap>> getType() {
      return ErasureUtils.<Class<List<? extends CoreMap>>> uncheckedCast(List.class);
    }
  }

  //int tid;     // Time ID
  SUTime.Temporal origTemporal;  // todo [2013]: never read. Can delete? (Set in TimeExpressionExtractorImpl)
  //int anchorTimeId = -1;

  public TimeExpression(MatchedExpression expr)
  {
    super(expr);
    // The value of a match is often shared: every match of { (/tuesdays?/) => TUESDAY } gets the
    // one value that defs.sutime.txt binds to TUESDAY.  The tense rules at the end of
    // english.sutime.txt tag the value of a time expression with the direction to resolve it in,
    // so each time expression needs a value of its own, or the tense found for one Tuesday is used
    // for every Tuesday, in later sentences and documents too (issue #1061).
    if ( ! isValueOfNestedExpression()) {
      value = copyOfValue(value);
    }
  }

  /**
   * Whether the value is that of one of the time expressions nested in this one, as for a composite
   * rule with a result such as {@code $0[0].temporal.value}.  The nested expression got a value of
   * its own when it was made, and keeps sharing it with this one, so that a tense given to the whole
   * expression also holds for the nested one (which sutime.includeNested reports).
   */
  private boolean isValueOfNestedExpression() {
    if (value == null || annotation == null) {
      return false;
    }
    List<? extends CoreMap> children = annotation.get(ChildrenAnnotation.class);
    if (children != null) {
      for (CoreMap child : children) {
        TimeExpression nested = child.get(Annotation.class);
        if (nested != null && nested.value == value) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * A PrimitiveValue with the same type and the same object (usually a Temporal) as the given one,
   * and a copy of its tags.  Values of any other class are returned as they are.
   */
  private static Value copyOfValue(Value value) {
    if (value == null || value.getClass() != Expressions.PrimitiveValue.class) {
      return value;
    }
    Value copy = new Expressions.PrimitiveValue<>(value.getType(), value.get());
    Tags tags = value.getTags();
    copy.setTags((tags != null) ? tags.copy() : null);
    return copy;
  }

  public TimeExpression(Interval<Integer> charOffsets, Interval<Integer> tokenOffsets,
                        Function<CoreMap, SUTime.Temporal> temporalFunc, double priority, double weight)
  {
    super(charOffsets, tokenOffsets, getSingleAnnotationExtractor(temporalFunc), priority, weight);
  }

  protected static final Function<MatchedExpression, TimeExpression> TimeExpressionConverter = in -> {
    if (in == null) return null;
    if (in instanceof TimeExpression) return (TimeExpression) in;
    TimeExpression newExpr = new TimeExpression(in);
    if (newExpr.getAnnotation().get(Annotation.class) == in) {
      newExpr.getAnnotation().set(Annotation.class, newExpr);
    }
    return newExpr;
  };

  private static SingleAnnotationExtractor getSingleAnnotationExtractor(final Function<CoreMap, SUTime.Temporal> temporalFunc)
  {
    SingleAnnotationExtractor extractFunc = new SingleAnnotationExtractor();
    extractFunc.valueExtractor = in -> {
      SUTime.Temporal t = temporalFunc.apply(in);
      return new Expressions.PrimitiveValue<>("Temporal", t);
    };
    extractFunc.tokensAnnotationField = CoreAnnotations.NumerizedTokensAnnotation.class;
    extractFunc.resultAnnotationField = Collections.singletonList((Class) TimeExpression.Annotation.class);
    extractFunc.resultNestedAnnotationField = TimeExpression.ChildrenAnnotation.class;
    extractFunc.resultAnnotationExtractor = TimeExpressionConverter;
    extractFunc.tokensAggregator = CoreMapAggregator.DEFAULT_NUMERIC_TOKENS_AGGREGATOR;
    return extractFunc;
  }

  public boolean addMod()
  {
    SUTime.Temporal t = getTemporal();
    if (t != null) {
      if (t != SUTime.TIME_NONE_OK) {
        setTemporal(t);
        return true;
      } else {
        return false;
      }
    } else {
      return true;
    }
  }

  @Override
  public boolean extractAnnotation(Env env, CoreMap sourceAnnotation)
  {
    boolean okay = super.extractAnnotation(env, sourceAnnotation);
            //super.extractAnnotation(sourceAnnotation, CoreAnnotations.NumerizedTokensAnnotation.class,
            //CoreMapAttributeAggregator.DEFAULT_NUMERIC_TOKENS_AGGREGATORS,
            //TimeExpression.Annotation.class, TimeExpression.ChildrenAnnotation.class);
    if (okay) {
      return addMod();
    } else {
      return false;
    }
  }

  @Override
  public boolean extractAnnotation(Env env, List<? extends CoreMap> source)
  {
    boolean okay = super.extractAnnotation(env, source);
            //super.extractAnnotation(source, CoreMapAttributeAggregator.getDefaultAggregators(),
            //TimeExpression.Annotation.class, TimeExpression.ChildrenAnnotation.class);
    if (okay) {
      return addMod();
    } else {
      return false;
    }
  }

 /* public int getTid() {
    return tid;
  }*/

  public SUTime.Temporal getTemporal() {
    if (value != null && value.get() instanceof SUTime.Temporal) {
      return (SUTime.Temporal) value.get();
    }
    return null;
  }

  public void setTemporal(SUTime.Temporal temporal) {
    this.value = new Expressions.PrimitiveValue<>("Temporal", temporal);
  }

/*  public String toString()
  {
    return text;
  } */

/*  public Timex getTimex(SUTime.TimeIndex timeIndex) {
    Timex timex = temporal.getTimex(timeIndex);
    timex.text = text;
    timex.xml = timex
    assert(timex.tid == tid);
  } */


}
