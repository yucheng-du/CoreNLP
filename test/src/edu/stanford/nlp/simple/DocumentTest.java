package edu.stanford.nlp.simple;

import edu.stanford.nlp.pipeline.CoreNLPProtos;
import org.junit.Test;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * A test for aspects of {@link edu.stanford.nlp.simple.Document} which do not require loading the NLP models.
 *
 * @author Gabor Angeli
 */
public class DocumentTest {
  Document oneSentenceDocument;
  Document twoSentenceDocument;

  @Before
  public void prepareDocuments() {
    oneSentenceDocument = new Document("the quick brown fox jumped over the lazy dog");
    twoSentenceDocument = new Document("the quick brown fox jumped over the lazy dog. The lazy dog was not impressed.");
  }

  @Test
  public void testCreateFromText() {
    assertNotNull(oneSentenceDocument);
    assertNotNull(twoSentenceDocument);
  }

  @Test
  public void testText() {
    Document doc = oneSentenceDocument;
    assertEquals("the quick brown fox jumped over the lazy dog", doc.text());
  }

  @Test
  public void testDocid() {
    Document doc = oneSentenceDocument;
    assertEquals(Optional.<String>empty(), doc.docid());
    assertEquals(Optional.of("foo"), doc.setDocid("foo").docid());
  }

  @Test
  public void testSentences() {
    Document doc = twoSentenceDocument;
    List<Sentence> sentences = doc.sentences();
    assertEquals(2, sentences.size());
    assertEquals("the quick brown fox jumped over the lazy dog.", sentences.get(0).text());
    assertEquals("The lazy dog was not impressed.", sentences.get(1).text());
  }

  @Test
  public void testSentencesUseDocumentProperties() {
    Properties props = new Properties();
    props.setProperty("ssplit.isOneSentence", "true");
    Document doc = new Document(props, "One. Two.");

    List<Sentence> sentences = doc.sentences();
    assertEquals(1, sentences.size());
    assertEquals("One. Two.", sentences.get(0).text());
    assertEquals(Arrays.asList("One", ".", "Two", "."), sentences.get(0).words());
    assertSame(sentences, doc.sentences());
    assertSame(sentences, doc.sentences(new Properties()));
    assertEquals(1, doc.serialize().getSentenceCount());
  }

  @Test
  public void testSentenceUsesDocumentTokenizerProperties() {
    Properties props = whitespaceSingleSentenceProperties();
    Document doc = new Document(props, "alpha,beta");

    Sentence sentence = doc.sentence(0);
    assertEquals(Arrays.asList("alpha,beta"), sentence.words());
    assertEquals("alpha,beta", sentence.text());
    assertEquals(0, sentence.characterOffsetBegin(0));
    assertEquals(10, sentence.characterOffsetEnd(0));
  }

  @Test
  public void testSerializeUsesDocumentProperties() throws IOException {
    Document doc = new Document(whitespaceSingleSentenceProperties(), "One. Two.");

    CoreNLPProtos.Document serialized = doc.serialize();
    assertEquals("One. Two.", serialized.getText());
    assertEquals(1, serialized.getSentenceCount());
    assertEquals(2, serialized.getSentence(0).getTokenCount());
    assertEquals("One.", serialized.getSentence(0).getToken(0).getWord());
    assertEquals("Two.", serialized.getSentence(0).getToken(1).getWord());

    Document restored = new Document(CoreNLPProtos.Document.parseFrom(serialized.toByteArray()));
    assertEquals(1, restored.sentences().size());
    assertEquals(Arrays.asList("One.", "Two."), restored.sentence(0).words());
    assertEquals(serialized, doc.serialize());
  }

  @Test
  public void testExplicitPropertiesOverrideDocumentDefaultsAndRemainCached() {
    Document doc = new Document(whitespaceSingleSentenceProperties(), "One. Two.");

    List<Sentence> sentences = doc.sentences(new Properties());
    assertEquals(2, sentences.size());
    assertEquals(Arrays.asList("One", "."), sentences.get(0).words());
    assertEquals(Arrays.asList("Two", "."), sentences.get(1).words());
    assertSame(sentences, doc.sentences());
    assertSame(sentences.get(1), doc.sentence(1));
    assertEquals(2, doc.serialize().getSentenceCount());
  }

  @Test
  public void testExplicitCustomPropertiesRemainCached() {
    Document doc = new Document("One. Two.");

    List<Sentence> sentences = doc.sentences(whitespaceSingleSentenceProperties());
    assertEquals(1, sentences.size());
    assertEquals(Arrays.asList("One.", "Two."), sentences.get(0).words());
    assertSame(sentences, doc.sentences());
    assertSame(sentences.get(0), doc.sentence(0));
    assertEquals(1, doc.serialize().getSentenceCount());
  }

  @Test
  public void testCachedSentencesDoNotCreateTheDefaultTokenizer() {
    CoreNLPProtos.Document proto = new Document("alpha beta. gamma.").serialize();
    // Creating a tokenizer from these properties would fail: there is no such segmenter model
    Properties props = new Properties();
    props.setProperty("tokenize.language", "ar");
    props.setProperty("segment.model", "edu/stanford/nlp/simple/no-such-segmenter.ser.gz");
    Document doc = new Document(props, proto);

    List<Sentence> sentences = doc.sentences();
    assertEquals(2, sentences.size());
    assertEquals(Arrays.asList("alpha", "beta", "."), doc.sentence(0).words());
    assertSame(sentences, doc.sentences(props));
  }

  private static Properties whitespaceSingleSentenceProperties() {
    Properties props = new Properties();
    props.setProperty("tokenize.whitespace", "true");
    props.setProperty("ssplit.isOneSentence", "true");
    return props;
  }
}
