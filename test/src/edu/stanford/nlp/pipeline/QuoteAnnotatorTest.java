package edu.stanford.nlp.pipeline;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.util.CoreMap;
import org.junit.Test;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** Quote extraction and output tests which do not require attribution models. */
public class QuoteAnnotatorTest {
  private static final String NESTED = "“outer «middle ‹inner› tail» end”";

  private static Annotation annotate(String text, String... options) {
    Properties props = new Properties();
    props.setProperty("annotators", "tokenize,ssplit,quote");
    props.setProperty("quote.attributeQuotes", "false");
    for (int i = 0; i < options.length; i += 2) {
      props.setProperty("quote." + options[i], options[i + 1]);
    }
    Annotation document = new Annotation(text);
    new StanfordCoreNLP(props).annotate(document);
    return document;
  }

  private static List<CoreMap> children(CoreMap quote) {
    return quote.get(CoreAnnotations.QuotationsAnnotation.class);
  }

  private static List<String> texts(List<CoreMap> quotes) {
    return quotes.stream().map(q -> q.get(CoreAnnotations.TextAnnotation.class)).collect(Collectors.toList());
  }

  private static void assertQuote(CoreMap quote, String text, int begin, int end, int index) {
    assertEquals(text, quote.get(CoreAnnotations.TextAnnotation.class));
    assertEquals(begin, (int) quote.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class));
    assertEquals(end, (int) quote.get(CoreAnnotations.CharacterOffsetEndAnnotation.class));
    assertEquals(index, (int) quote.get(CoreAnnotations.QuotationIndexAnnotation.class));
  }

  private static void assertUniqueQuotes(Annotation document, int count) {
    List<CoreMap> quotes = QuoteAnnotator.gatherQuotes(document);
    assertEquals(count, quotes.size());
    Set<CoreMap> identities = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<List<Integer>> spans = new HashSet<>();
    Set<Integer> indices = new HashSet<>();
    for (CoreMap quote : quotes) {
      assertTrue("A quote object appeared more than once", identities.add(quote));
      assertTrue("A quote span appeared more than once", spans.add(Arrays.asList(
          quote.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class),
          quote.get(CoreAnnotations.CharacterOffsetEndAnnotation.class))));
      assertTrue("A quote index appeared more than once",
          indices.add(quote.get(CoreAnnotations.QuotationIndexAnnotation.class)));
    }
    for (int i = 0; i < count; i++) {
      assertTrue("Missing quote index " + i, indices.contains(i));
    }
  }

  @Test
  public void testOnlyImmediateChildrenAreEmbedded() {
    Annotation document = annotate(NESTED);
    List<CoreMap> roots = children(document);
    assertEquals(1, roots.size());
    CoreMap outer = roots.get(0);
    assertEquals(1, children(outer).size());
    CoreMap middle = children(outer).get(0);
    assertEquals(1, children(middle).size());
    CoreMap inner = children(middle).get(0);
    assertTrue(children(inner).isEmpty());
    assertQuote(outer, NESTED, 0, 33, 0);
    assertQuote(middle, "«middle ‹inner› tail»", 7, 28, 1);
    assertQuote(inner, "‹inner›", 15, 22, 2);
  }

  @Test
  public void testGatherQuotesHasUniqueObjectsSpansAndIndices() {
    Annotation document = annotate(NESTED);
    assertUniqueQuotes(document, 3);
    assertEquals(Arrays.asList(NESTED, "«middle ‹inner› tail»", "‹inner›"),
        texts(QuoteAnnotator.gatherQuotes(document)));
  }

  @Test
  public void testQuoteOutputsDoNotRepeatNestedQuotes() throws IOException {
    Annotation document = annotate(NESTED);
    StringWriter text = new StringWriter();
    TextOutputter.outputQuotes(document, new PrintWriter(text));
    List<String> lines = Arrays.stream(text.toString().split("\\R"))
        .filter(line -> line.startsWith("Unknown:\t")).collect(Collectors.toList());
    assertEquals(3, lines.size());
    assertEquals("Unknown:\t" + NESTED + "\t[index=0, charOffsetBegin=0]", lines.get(0));
    assertEquals("Unknown:\t«middle ‹inner› tail»\t[index=1, charOffsetBegin=7]", lines.get(1));
    assertEquals("Unknown:\t‹inner›\t[index=2, charOffsetBegin=15]", lines.get(2));
    ByteArrayOutputStream json = new ByteArrayOutputStream();
    new JSONOutputter().print(document, json);
    try (JsonReader reader = Json.createReader(new StringReader(
        new String(json.toByteArray(), StandardCharsets.UTF_8)))) {
      JsonArray quotes = reader.readObject().getJsonArray("quotes");
      assertEquals(3, quotes.size());
      int[] begins = {0, 7, 15};
      int[] ends = {33, 28, 22};
      List<String> expected = Arrays.asList(NESTED, "«middle ‹inner› tail»", "‹inner›");
      for (int i = 0; i < quotes.size(); i++) {
        JsonObject quote = quotes.getJsonObject(i);
        assertEquals(i, quote.getInt("id"));
        assertEquals(expected.get(i), quote.getString("text"));
        assertEquals(begins[i], quote.getInt("beginIndex"));
        assertEquals(ends[i], quote.getInt("endIndex"));
      }
    }
  }

  @Test
  public void testNestedTokenAndSentenceOffsets() {
    Annotation document = annotate(NESTED);
    List<CoreMap> quotes = QuoteAnnotator.gatherQuotes(document);
    // The original token objects must be preserved, including inclusive TokenEndAnnotation.
    List<CoreLabel> allTokens = document.get(CoreAnnotations.TokensAnnotation.class);
    for (CoreMap quote : quotes) {
      List<CoreLabel> tokens = quote.get(CoreAnnotations.TokensAnnotation.class);
      int begin = quote.get(CoreAnnotations.TokenBeginAnnotation.class);
      int end = quote.get(CoreAnnotations.TokenEndAnnotation.class);
      assertEquals(tokens.size(), end - begin + 1);
      for (int i = 0; i < tokens.size(); i++) {
        assertSame(allTokens.get(begin + i), tokens.get(i));
      }
      assertEquals(0, (int) quote.get(CoreAnnotations.SentenceBeginAnnotation.class));
      assertEquals(0, (int) quote.get(CoreAnnotations.SentenceEndAnnotation.class));
    }
    for (CoreLabel token : allTokens) {
      if (token.word().equals("outer") || token.word().equals("end")) {
        assertEquals(0, (int) token.get(CoreAnnotations.QuotationIndexAnnotation.class));
      } else if (token.word().equals("middle") || token.word().equals("tail")) {
        assertEquals(1, (int) token.get(CoreAnnotations.QuotationIndexAnnotation.class));
      } else if (token.word().equals("inner")) {
        assertEquals(2, (int) token.get(CoreAnnotations.QuotationIndexAnnotation.class));
      }
    }
    assertEquals(Arrays.asList("outer", "middle", "inner", "tail", "end"), allTokens.stream()
        .map(CoreLabel::word).filter(word -> word.matches("[a-z]+"))
        .collect(Collectors.toList()));
  }

  @Test
  public void testTwoLevelsAndMultipleRootsKeepBreadthFirstIndices() {
    String text = "“outer «inner» end” and “other”.";
    Annotation document = annotate(text);
    List<CoreMap> roots = children(document);
    assertEquals(2, roots.size());
    assertQuote(roots.get(0), "“outer «inner» end”", 0, 19, 0);
    assertQuote(roots.get(1), "“other”", 24, 31, 1);
    assertEquals(1, children(roots.get(0)).size());
    assertQuote(children(roots.get(0)).get(0), "«inner»", 7, 14, 2);
    assertUniqueQuotes(document, 3);
    assertEquals(Arrays.asList(0, 2, 1), QuoteAnnotator.gatherQuotes(document).stream()
        .map(q -> q.get(CoreAnnotations.QuotationIndexAnnotation.class)).collect(Collectors.toList()));
  }

  @Test
  public void testSiblingQuotesAndIdenticalTextAtDifferentOffsets() {
    String text = "“outer «same» and «same» end”";
    Annotation document = annotate(text);
    CoreMap outer = children(document).get(0);
    assertEquals(2, children(outer).size());
    assertQuote(children(outer).get(0), "«same»", 7, 13, 1);
    assertQuote(children(outer).get(1), "«same»", 18, 24, 2);
    assertNotSame(children(outer).get(0), children(outer).get(1));
    assertUniqueQuotes(document, 3);
  }

  @Test
  public void testMultipleNestedRootsKeepBreadthFirstIndices() {
    Annotation document = annotate("“a «b ‘c’ d» e” “f «g ‘h’ i» j”");
    List<CoreMap> roots = children(document);
    assertEquals(2, roots.size());
    assertUniqueQuotes(document, 6);
    assertEquals(Arrays.asList(0, 2, 4, 1, 3, 5), QuoteAnnotator.gatherQuotes(document).stream()
        .map(q -> q.get(CoreAnnotations.QuotationIndexAnnotation.class)).collect(Collectors.toList()));
    for (CoreMap root : roots) {
      assertEquals(1, children(root).size());
      assertEquals(1, children(children(root).get(0)).size());
    }
    String[] words = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j"};
    int[] indices = {0, 2, 4, 2, 0, 1, 3, 5, 3, 1};
    List<CoreLabel> tokens = document.get(CoreAnnotations.TokensAnnotation.class).stream()
        .filter(t -> Arrays.asList(words).contains(t.originalText())).collect(Collectors.toList());
    assertEquals(words.length, tokens.size());
    for (int i = 0; i < words.length; i++) {
      assertEquals(words[i], tokens.get(i).originalText());
      assertEquals(indices[i], (int) tokens.get(i).get(CoreAnnotations.QuotationIndexAnnotation.class));
    }
  }

  @Test
  public void testSameTypeNestedQuotes() {
    Annotation document = annotate("“outer “middle “inner” tail” end”", "allowEmbeddedSame", "true");
    CoreMap outer = children(document).get(0);
    assertEquals(1, children(outer).size());
    CoreMap middle = children(outer).get(0);
    assertEquals(1, children(middle).size());
    assertEquals("“inner”", children(middle).get(0).get(CoreAnnotations.TextAnnotation.class));
    assertUniqueQuotes(document, 3);
  }

  @Test
  public void testUnclosedNestedQuotesSharingEndOffset() {
    String text = "“outer «middle ‹inner";
    Annotation document = annotate(text, "extractUnclosedQuotes", "true");
    assertTrue(children(document).isEmpty());
    List<CoreMap> roots = document.get(CoreAnnotations.UnclosedQuotationsAnnotation.class);
    assertEquals(1, roots.size());
    CoreMap outer = roots.get(0);
    List<CoreMap> middleLevel = outer.get(CoreAnnotations.UnclosedQuotationsAnnotation.class);
    assertEquals(1, middleLevel.size());
    CoreMap middle = middleLevel.get(0);
    List<CoreMap> innerLevel = middle.get(CoreAnnotations.UnclosedQuotationsAnnotation.class);
    assertEquals(1, innerLevel.size());
    CoreMap inner = innerLevel.get(0);
    assertTrue(inner.get(CoreAnnotations.UnclosedQuotationsAnnotation.class).isEmpty());
    assertQuote(outer, text, 0, text.length(), 0);
    assertQuote(middle, "«middle ‹inner", 7, text.length(), 1);
    assertQuote(inner, "‹inner", 15, text.length(), 2);
    assertNull(outer.get(CoreAnnotations.QuotationsAnnotation.class));
    assertNull(middle.get(CoreAnnotations.QuotationsAnnotation.class));
    assertNull(inner.get(CoreAnnotations.QuotationsAnnotation.class));
  }

  @Test
  public void testSmartQuotesCountsAllNestingLevels() {
    // ASCII conversion gives three disjoint quotes; Unicode has three nested quotes.
    // Counting just the root and its immediate child would incorrectly select ASCII.
    String text = "“ a “ b “ c ” d ” e ”";
    Annotation document = annotate(text, "smartQuotes", "true", "asciiQuotes", "true",
        "allowEmbeddedSame", "true");
    List<CoreMap> roots = children(document);
    assertEquals(1, roots.size());
    assertQuote(roots.get(0), text, 0, 21, 0);
  }

  @Test
  public void testPlainTextHasNoQuotes() {
    Annotation document = annotate("There are no quoted words here.");
    assertTrue(children(document).isEmpty());
    assertUniqueQuotes(document, 0);
  }
}
