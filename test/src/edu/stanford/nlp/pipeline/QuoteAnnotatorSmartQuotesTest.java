package edu.stanford.nlp.pipeline;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.util.CoreMap;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

public class QuoteAnnotatorSmartQuotesTest {

  private static Annotation annotate(String text, boolean smart, boolean single,
                                     boolean ascii, boolean unclosed) {
    Properties props = new Properties();
    props.setProperty("annotators", "tokenize,ssplit,quote");
    props.setProperty("quote.attributeQuotes", "false");
    props.setProperty("quote.smartQuotes", Boolean.toString(smart));
    props.setProperty("quote.singleQuotes", Boolean.toString(single));
    props.setProperty("quote.asciiQuotes", Boolean.toString(ascii));
    props.setProperty("quote.extractUnclosedQuotes", Boolean.toString(unclosed));
    return annotate(text, props);
  }

  private static Annotation annotate(String text, Properties props) {
    // Each case starts with a fresh quote annotator: smartQuotes changes USE_SINGLE.
    StanfordCoreNLP.clearAnnotatorPool();
    try {
      Annotation doc = new Annotation(text);
      new StanfordCoreNLP(props).annotate(doc);
      return doc;
    } finally {
      StanfordCoreNLP.clearAnnotatorPool();
    }
  }

  private static CoreLabel tokenAt(Annotation doc, int begin, int end) {
    for (CoreLabel token : doc.get(CoreAnnotations.TokensAnnotation.class)) {
      if (token.beginPosition() == begin && token.endPosition() == end) {
        return token;
      }
    }
    throw new AssertionError("Missing token at [" + begin + "," + end + ")");
  }

  private static void assertQuote(Annotation doc, CoreMap quote, int begin, int end, int index) {
    assertEquals(doc.get(CoreAnnotations.TextAnnotation.class).substring(begin, end),
        quote.get(CoreAnnotations.TextAnnotation.class));
    assertEquals(Integer.valueOf(begin), quote.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class));
    assertEquals(Integer.valueOf(end), quote.get(CoreAnnotations.CharacterOffsetEndAnnotation.class));
    assertEquals(Integer.valueOf(index), quote.get(CoreAnnotations.QuotationIndexAnnotation.class));
    assertEquals(Integer.valueOf(0), quote.get(CoreAnnotations.SentenceBeginAnnotation.class));
    assertEquals(Integer.valueOf(0), quote.get(CoreAnnotations.SentenceEndAnnotation.class));
    List<CoreLabel> quoteTokens = quote.get(CoreAnnotations.TokensAnnotation.class);
    List<CoreLabel> docTokens = doc.get(CoreAnnotations.TokensAnnotation.class);
    int tokenBegin = quote.get(CoreAnnotations.TokenBeginAnnotation.class);
    assertFalse(quoteTokens.isEmpty());
    assertEquals(Integer.valueOf(tokenBegin + quoteTokens.size() - 1),
        quote.get(CoreAnnotations.TokenEndAnnotation.class));
    for (int i = 0; i < quoteTokens.size(); i++) {
      assertSame(docTokens.get(tokenBegin + i), quoteTokens.get(i));
    }
  }

  @Test
  public void testSelectedSingleQuoteCandidateOwnsTokenIndices() {
    Annotation doc = annotate("'a' \"b\"", true, true, false, false);
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(2, quotes.size());
    assertQuote(doc, quotes.get(0), 0, 3, 0);
    assertQuote(doc, quotes.get(1), 4, 7, 1);
    assertEquals(Integer.valueOf(0), tokenAt(doc, 1, 2).get(CoreAnnotations.QuotationIndexAnnotation.class));
    assertEquals(Integer.valueOf(1), tokenAt(doc, 5, 6).get(CoreAnnotations.QuotationIndexAnnotation.class));
  }

  @Test
  public void testSelectedUnicodeCandidatePreservesInnermostIndices() {
    Annotation doc = annotate("“ a « b » c ”", true, false, true, false);
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(1, quotes.size());
    CoreMap outer = quotes.get(0);
    assertQuote(doc, outer, 0, 13, 0);
    List<CoreMap> children = outer.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(1, children.size());
    assertQuote(doc, children.get(0), 4, 9, 1);
    assertEquals(Integer.valueOf(1), tokenAt(doc, 6, 7).get(CoreAnnotations.QuotationIndexAnnotation.class));
    assertEquals(Integer.valueOf(0), tokenAt(doc, 10, 11).get(CoreAnnotations.QuotationIndexAnnotation.class));
  }

  @Test
  public void testDiscardedCandidateLeavesNoTokenIndicesOutsideSelectedQuotes() {
    Annotation doc = annotate("“a”x“b”", true, false, true, false);
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(2, quotes.size());
    assertQuote(doc, quotes.get(0), 0, 3, 0);
    assertQuote(doc, quotes.get(1), 4, 7, 1);
    // ASCII recognizes one [0,7) span, but the selected Unicode roots exclude x.
    assertNull(tokenAt(doc, 3, 4).get(CoreAnnotations.QuotationIndexAnnotation.class));
    assertEquals(Integer.valueOf(0), tokenAt(doc, 1, 2).get(CoreAnnotations.QuotationIndexAnnotation.class));
    assertEquals(Integer.valueOf(1), tokenAt(doc, 5, 6).get(CoreAnnotations.QuotationIndexAnnotation.class));
  }

  private static void assertUnclosedIndices(boolean smart) {
    Annotation doc = annotate("\"a\" 'bbb \"c\"", smart, true, false, true);
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(2, quotes.size());
    assertQuote(doc, quotes.get(0), 0, 3, 0);
    assertQuote(doc, quotes.get(1), 9, 12, 1);
    List<CoreMap> unclosed = doc.get(CoreAnnotations.UnclosedQuotationsAnnotation.class);
    assertEquals(1, unclosed.size());
    assertQuote(doc, unclosed.get(0), 4, 12, 0);
    // Closed and unclosed lists have independent indices; unclosed is applied last.
    assertEquals(Integer.valueOf(0), tokenAt(doc, 10, 11).get(CoreAnnotations.QuotationIndexAnnotation.class));
    assertEquals(Integer.valueOf(0), tokenAt(doc, 1, 2).get(CoreAnnotations.QuotationIndexAnnotation.class));
  }

  @Test
  public void testSelectedUnclosedCandidateIsAppliedAfterClosedQuotes() {
    assertUnclosedIndices(true);
  }

  @Test
  public void testNonSmartUnclosedOrderIsUnchanged() {
    assertUnclosedIndices(false);
  }

  @Test
  public void testNonSmartSingleQuotesAreUnchanged() {
    Annotation doc = annotate("'a' \"b\"", false, true, false, false);
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(2, quotes.size());
    assertQuote(doc, quotes.get(0), 0, 3, 0);
    assertQuote(doc, quotes.get(1), 4, 7, 1);
    assertEquals(Integer.valueOf(1), tokenAt(doc, 5, 6).get(CoreAnnotations.QuotationIndexAnnotation.class));
  }

  @Test
  public void testSingleQuotesDisabledAreUnchanged() {
    Annotation doc = annotate("'a' \"b\"", true, false, false, false);
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(1, quotes.size());
    assertQuote(doc, quotes.get(0), 4, 7, 0);
    assertNull(tokenAt(doc, 1, 2).get(CoreAnnotations.QuotationIndexAnnotation.class));
    assertEquals(Integer.valueOf(0), tokenAt(doc, 5, 6).get(CoreAnnotations.QuotationIndexAnnotation.class));
  }

  @Test
  public void testNoQuotesLeavesTokensUnindexed() {
    Annotation doc = annotate("plain text", true, false, true, true);
    assertTrue(doc.get(CoreAnnotations.QuotationsAnnotation.class).isEmpty());
    assertTrue(doc.get(CoreAnnotations.UnclosedQuotationsAnnotation.class).isEmpty());
    for (CoreLabel token : doc.get(CoreAnnotations.TokensAnnotation.class)) {
      assertNull(token.get(CoreAnnotations.QuotationIndexAnnotation.class));
    }
  }

  @Test
  public void testPublicGetCoreMapQuotesStillAssignsSharedTokenIndices() {
    Properties props = new Properties();
    props.setProperty("annotators", "tokenize,ssplit");
    props.setProperty("quote.attributeQuotes", "false");
    Annotation doc = annotate("\"a\" \"b\"", props);
    String text = doc.get(CoreAnnotations.TextAnnotation.class);
    QuoteAnnotator annotator = new QuoteAnnotator(props);
    List<CoreMap> quotes = QuoteAnnotator.getCoreMapQuotes(annotator.getQuotes(text).first(),
        doc.get(CoreAnnotations.TokensAnnotation.class), doc.get(CoreAnnotations.SentencesAnnotation.class),
        text, null, false);
    assertEquals(2, quotes.size());
    assertQuote(doc, quotes.get(0), 0, 3, 0);
    assertQuote(doc, quotes.get(1), 4, 7, 1);
    assertEquals(Integer.valueOf(1), tokenAt(doc, 5, 6).get(CoreAnnotations.QuotationIndexAnnotation.class));
  }

  @Test
  public void testSelectedIndicesSurviveProtobufRoundTrip() throws IOException {
    Annotation doc = annotate("'a' \"b\"", true, true, false, false);
    ProtobufAnnotationSerializer serializer = new ProtobufAnnotationSerializer();
    byte[] bytes = serializer.toProto(doc).toByteArray();
    Annotation restored = serializer.fromProto(CoreNLPProtos.Document.parseFrom(bytes));
    List<CoreMap> quotes = restored.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(2, quotes.size());
    assertQuote(restored, quotes.get(0), 0, 3, 0);
    assertQuote(restored, quotes.get(1), 4, 7, 1);
    assertEquals(Integer.valueOf(1), tokenAt(restored, 5, 6).get(CoreAnnotations.QuotationIndexAnnotation.class));
  }
}
