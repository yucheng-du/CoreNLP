package edu.stanford.nlp.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.Test;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Pair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Tests for quotations in curly double quotes which go on over several paragraphs, where
 * each paragraph of the quotation begins with “ and only the last one is closed with ”
 * (github issue 1139).  The texts are made up.
 */
public class QuoteAnnotatorMultiParagraphTest {

  /** A quotation which begins in the middle of a paragraph and goes on over two more */
  private static final String SPEECH = "“We will act this week.\n\n" +
      "“Families have waited long enough.\n\n" +
      "“The plan starts on Monday,”";

  /** An ordinary quotation, then SPEECH from 50 to 139, then narration and another ordinary quotation */
  private static final String NEWS = "“Prices will rise,” the minister said. She added, " + SPEECH +
      " she said, and left. Her aide said, “No more questions.”";

  /** “ at 0, a blank line, “ at 8, ” at 13 */
  private static final String TWO_PARAGRAPHS = "“alpha\n\n“beta”";

  /** The same, but with the first “ closed at 20 */
  private static final String CLOSED_OUTER = "“alpha\n\n“beta” gamma”";

  private static final String[][] SETTINGS = {
    { "allowEmbeddedSame", "false", "extractUnclosedQuotes", "false" },
    { "allowEmbeddedSame", "false", "extractUnclosedQuotes", "true" },
    { "allowEmbeddedSame", "true", "extractUnclosedQuotes", "false" },
    { "allowEmbeddedSame", "true", "extractUnclosedQuotes", "true" },
  };

  @Test
  public void testQuotationOverThreeParagraphs() {
    assertEquals(195, NEWS.length());
    assertEquals(SPEECH, NEWS.substring(50, 139));
    assertEquals("“Prices will rise,”", NEWS.substring(0, 19));
    assertEquals("“No more questions.”", NEWS.substring(175, 195));
    for (String[] settings : SETTINGS) {
      checkQuotes(NEWS, list("0-19", "50-139", "175-195"), list(), settings);
    }
  }

  @Test
  public void testTwoContinuedQuotations() {
    checkQuotes("“a\n\n“b” x. “c\n\n“d” y.", list("0-7", "11-18"), list(), "extractUnclosedQuotes", "true");
  }

  @Test
  public void testTwoParagraphs() {
    assertEquals(14, TWO_PARAGRAPHS.length());
    // the “ at 8 could also open a quotation inside one which is never closed,
    // but it is taken to carry the first quotation on into a new paragraph
    for (String[] settings : SETTINGS) {
      checkQuotes(TWO_PARAGRAPHS, list("0-14"), list(), settings);
    }
  }

  @Test
  public void testClosedOuterQuoteIsKept() {
    assertEquals(21, CLOSED_OUTER.length());
    // the first “ is closed in the usual way, so the “ at 8 opens an embedded
    // quotation even though it begins a paragraph
    for (String[] settings : SETTINGS) {
      boolean embedded = settings[1].equals("true");
      checkQuotes(CLOSED_OUTER, embedded ? list("0-21", "8-14") : list("0-21"), list(), settings);
    }
  }

  @Test
  public void testQuotationsInsideContinuedQuotation() {
    for (String[] settings : SETTINGS) {
      boolean embedded = settings[1].equals("true");
      // a “ which does not begin a paragraph opens an embedded quotation as usual
      checkQuotes("“a “b” c\n\n“d”", embedded ? list("0-13", "3-6") : list("0-13"), list(), settings);
      // and so does a quotation mark of another kind
      checkQuotes("“a ‘b’ c\n\n“d”", list("0-13", "3-6"), list(), settings);
    }
  }

  @Test
  public void testParagraphBreaks() {
    checkQuotes("“alpha\r\n\r\n“beta”", list("0-16"), list(), "extractUnclosedQuotes", "true");
    checkQuotes("“alpha \t\n \t\n\t “beta”", list("0-20"), list(), "extractUnclosedQuotes", "true");
    checkQuotes("“alpha\r\n \t\r\n  “beta”", list("0-20"), list(), "extractUnclosedQuotes", "true");
    // a single line break does not begin a new paragraph
    checkQuotes("“alpha\n“beta”", list("7-13"), list("0-13"), "extractUnclosedQuotes", "true");
    checkQuotes("“alpha\r\n“beta”", list("8-14"), list("0-14"), "extractUnclosedQuotes", "true");
  }

  @Test
  public void testNotContinued() {
    // a paragraph in between which does not begin with “
    checkQuotes("“alpha\n\nNarration.\n\n“beta”", list("20-26"), list("0-26"), "extractUnclosedQuotes", "true");
    // no closing ”
    checkQuotes("“alpha\n\n“beta", list(), list("0-13", "8-13"), "extractUnclosedQuotes", "true");
  }

  @Test
  public void testMaxLength() {
    // the text between the outer quotation marks of TWO_PARAGRAPHS is 12 characters long
    checkQuotes(TWO_PARAGRAPHS, list("0-14"), list(), "extractUnclosedQuotes", "true", "maxLength", "12");
    // with maxLength 11 it is too long, and the result is the same as with « and »
    checkQuotes(TWO_PARAGRAPHS, list(), list("8-14"), "extractUnclosedQuotes", "true", "maxLength", "11");
    checkQuotes("«alpha\n\n«beta»", list(), list("8-14"), "extractUnclosedQuotes", "true", "maxLength", "11");
  }

  @Test
  public void testOtherQuotationMarks() {
    checkQuotes("«alpha\n\n«beta»", list("8-14"), list("0-14"), "extractUnclosedQuotes", "true");
    checkQuotes("‘alpha\n\n‘beta’", list("8-14"), list("0-14"), "extractUnclosedQuotes", "true");
    checkQuotes("„alpha\n\n„beta”", list("8-14"), list("0-14"), "extractUnclosedQuotes", "true");
    checkQuotes("\"alpha\n\n\"beta\"", list("0-14"), list(), "extractUnclosedQuotes", "true");
  }

  @Test
  public void testQuotationOverThreeParagraphsInPipeline() {
    Annotation doc = annotate(NEWS, "extractUnclosedQuotes", "true");
    assertEquals(5, doc.get(CoreAnnotations.SentencesAnnotation.class).size());
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(3, quotes.size());
    checkQuote(quotes.get(0), doc, 0, 0, 19, 0, 5, 0, 0);
    checkQuote(quotes.get(1), doc, 1, 50, 139, 13, 34, 1, 3);
    checkQuote(quotes.get(2), doc, 2, 175, 195, 45, 50, 4, 4);
    for (CoreMap quote : quotes) {
      assertEquals(0, quote.get(CoreAnnotations.QuotationsAnnotation.class).size());
    }
    assertEquals(Collections.emptyList(), doc.get(CoreAnnotations.UnclosedQuotationsAnnotation.class));

    // the two “ which carry the quotation on are among its tokens
    List<CoreLabel> tokens = doc.get(CoreAnnotations.TokensAnnotation.class);
    assertEquals("“", tokens.get(20).originalText());
    assertEquals(75, tokens.get(20).beginPosition());
    assertEquals("“", tokens.get(27).originalText());
    assertEquals(111, tokens.get(27).beginPosition());

    List<Integer> indices = new ArrayList<>();
    indices.addAll(Collections.nCopies(6, 0));
    indices.addAll(Collections.<Integer>nCopies(7, null));
    indices.addAll(Collections.nCopies(22, 1));
    indices.addAll(Collections.<Integer>nCopies(10, null));
    indices.addAll(Collections.nCopies(6, 2));
    assertEquals(indices, tokenQuoteIndices(doc));
  }

  @Test
  public void testTwoParagraphsInPipeline() {
    for (String allowEmbeddedSame : new String[] { "false", "true" }) {
      Annotation doc = annotate(TWO_PARAGRAPHS, "allowEmbeddedSame", allowEmbeddedSame, "extractUnclosedQuotes", "true");
      List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
      assertEquals(1, quotes.size());
      checkQuote(quotes.get(0), doc, 0, 0, 14, 0, 4, 0, 0);
      assertEquals(0, quotes.get(0).get(CoreAnnotations.QuotationsAnnotation.class).size());
      assertEquals(Collections.emptyList(), doc.get(CoreAnnotations.UnclosedQuotationsAnnotation.class));
      assertEquals(Arrays.asList(0, 0, 0, 0, 0), tokenQuoteIndices(doc));
    }
  }

  @Test
  public void testClosedOuterQuoteInPipeline() {
    Annotation doc = annotate(CLOSED_OUTER, "allowEmbeddedSame", "true");
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(1, quotes.size());
    checkQuote(quotes.get(0), doc, 0, 0, 21, 0, 6, 0, 0);
    List<CoreMap> embedded = quotes.get(0).get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(1, embedded.size());
    checkQuote(embedded.get(0), doc, 1, 8, 14, 2, 4, 0, 0);
    assertEquals(Arrays.asList(0, 0, 1, 1, 1, 0, 0), tokenQuoteIndices(doc));

    doc = annotate(CLOSED_OUTER, "allowEmbeddedSame", "false");
    quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(1, quotes.size());
    checkQuote(quotes.get(0), doc, 0, 0, 21, 0, 6, 0, 0);
    assertEquals(0, quotes.get(0).get(CoreAnnotations.QuotationsAnnotation.class).size());
    assertEquals(Arrays.asList(0, 0, 0, 0, 0, 0, 0), tokenQuoteIndices(doc));
  }

  @Test
  public void testQuotationInsideContinuedQuotationInPipeline() {
    Annotation doc = annotate("“a “b” c\n\n“d”", "allowEmbeddedSame", "true");
    List<CoreMap> quotes = doc.get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(1, quotes.size());
    checkQuote(quotes.get(0), doc, 0, 0, 13, 0, 8, 0, 0);
    List<CoreMap> embedded = quotes.get(0).get(CoreAnnotations.QuotationsAnnotation.class);
    assertEquals(1, embedded.size());
    checkQuote(embedded.get(0), doc, 1, 3, 6, 2, 4, 0, 0);
    // the “ at 10 is token 6, which belongs to the outer quotation only
    assertEquals(Arrays.asList(0, 0, 1, 1, 1, 0, 0, 0, 0), tokenQuoteIndices(doc));
  }

  private static List<String> list(String... spans) {
    return Arrays.asList(spans);
  }

  /** Compares the closed and the unclosed quotes from {@link QuoteAnnotator#getQuotes}, written as "begin-end" */
  private static void checkQuotes(String text, List<String> closed, List<String> unclosed, String... settings) {
    Properties props = new Properties();
    props.setProperty("quote.attributeQuotes", "false");
    for (int i = 0; i < settings.length; i += 2) {
      props.setProperty("quote." + settings[i], settings[i + 1]);
    }
    Pair<List<Pair<Integer, Integer>>, List<Pair<Integer, Integer>>> quotes = new QuoteAnnotator("quote", props).getQuotes(text);
    String message = Arrays.toString(settings) + " " + text;
    assertEquals("closed quotes " + message, closed, spans(quotes.first()));
    assertEquals("unclosed quotes " + message, unclosed, spans(quotes.second()));
  }

  private static List<String> spans(List<Pair<Integer, Integer>> quotes) {
    List<Pair<Integer, Integer>> sorted = new ArrayList<>(quotes);
    sorted.sort((a, b) -> a.first().equals(b.first()) ?
        Integer.compare(a.second(), b.second()) : Integer.compare(a.first(), b.first()));
    List<String> spans = new ArrayList<>();
    for (Pair<Integer, Integer> quote : sorted) {
      spans.add(quote.first() + "-" + quote.second());
    }
    return spans;
  }

  private static Annotation annotate(String text, String... settings) {
    Properties props = new Properties();
    props.setProperty("annotators", "tokenize,ssplit,quote");
    props.setProperty("quote.attributeQuotes", "false");
    props.setProperty("quote.asciiQuotes", "false");
    props.setProperty("quote.smartQuotes", "false");
    for (int i = 0; i < settings.length; i += 2) {
      props.setProperty("quote." + settings[i], settings[i + 1]);
    }
    Annotation doc = new Annotation(text);
    new StanfordCoreNLP(props).annotate(doc);
    return doc;
  }

  /**
   * Checks a quotation from the pipeline against the document.  As elsewhere in
   * QuoteAnnotator, tokenEnd is the index of the last token of the quotation.
   */
  private static void checkQuote(CoreMap quote, Annotation doc, int index, int begin, int end,
                                 int tokenBegin, int tokenEnd, int sentenceBegin, int sentenceEnd) {
    String text = doc.get(CoreAnnotations.TextAnnotation.class);
    List<CoreLabel> tokens = doc.get(CoreAnnotations.TokensAnnotation.class);
    assertEquals(begin, (int) quote.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class));
    assertEquals(end, (int) quote.get(CoreAnnotations.CharacterOffsetEndAnnotation.class));
    assertEquals(text.substring(begin, end), quote.get(CoreAnnotations.TextAnnotation.class));
    assertEquals(index, (int) quote.get(CoreAnnotations.QuotationIndexAnnotation.class));
    assertEquals(tokenBegin, (int) quote.get(CoreAnnotations.TokenBeginAnnotation.class));
    assertEquals(tokenEnd, (int) quote.get(CoreAnnotations.TokenEndAnnotation.class));
    List<CoreLabel> quoteTokens = quote.get(CoreAnnotations.TokensAnnotation.class);
    assertEquals(tokenEnd - tokenBegin + 1, quoteTokens.size());
    for (int i = 0; i < quoteTokens.size(); i++) {
      assertSame(tokens.get(tokenBegin + i), quoteTokens.get(i));
    }
    assertEquals(begin, quoteTokens.get(0).beginPosition());
    assertEquals(end, quoteTokens.get(quoteTokens.size() - 1).endPosition());
    assertEquals(sentenceBegin, (int) quote.get(CoreAnnotations.SentenceBeginAnnotation.class));
    assertEquals(sentenceEnd, (int) quote.get(CoreAnnotations.SentenceEndAnnotation.class));
  }

  private static List<Integer> tokenQuoteIndices(Annotation doc) {
    List<Integer> indices = new ArrayList<>();
    for (CoreLabel token : doc.get(CoreAnnotations.TokensAnnotation.class)) {
      indices.add(token.get(CoreAnnotations.QuotationIndexAnnotation.class));
    }
    return indices;
  }

}
