package edu.stanford.nlp.pipeline;

import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.Assert.*;

/** Tests the Spanish number rules without statistical NER, POS, or SUTime models. */
public class NERCombinerAnnotatorTest {

  private static final String MAPPING = "spanish.number.regexner.mapping";
  private static final String DEFAULT_RESOURCE =
      "edu/stanford/nlp/models/kbp/spanish/gazetteers/kbp_regexner_number_sp.tag";

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ClassLoader previousClassLoader;
  private URLClassLoader rulesClassLoader;

  @Before
  public void provideDefaultRules() throws IOException {
    Path resourceRoot = temporaryFolder.newFolder("resources").toPath();
    Path defaultRules = resourceRoot.resolve(DEFAULT_RESOURCE);
    Files.createDirectories(defaultRules.getParent());
    Files.write(defaultRules, "veinticinco\tORDINAL\n".getBytes(StandardCharsets.UTF_8));
    previousClassLoader = Thread.currentThread().getContextClassLoader();
    // No parent: use this small resource even when a models jar is on the classpath.
    rulesClassLoader = new URLClassLoader(new URL[] {resourceRoot.toUri().toURL()}, null);
    Thread.currentThread().setContextClassLoader(rulesClassLoader);
  }

  @After
  public void restoreClassLoader() throws IOException {
    if (rulesClassLoader != null) {
      Thread.currentThread().setContextClassLoader(previousClassLoader);
      rulesClassLoader.close();
    }
  }

  private Path rules(String name, String label) throws IOException {
    Path path = temporaryFolder.newFile(name).toPath();
    Files.write(path, ("veinticinco\t" + label + "\n").getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private static Properties properties() {
    Properties props = new Properties();
    props.setProperty("ner.language", "es");
    props.setProperty("ner.rulesOnly", "true");
    props.setProperty("ner.usePresetNERTags", "true");
    props.setProperty("ner.applyNumericClassifiers", "true");
    props.setProperty("ner.useSUTime", "false");
    props.setProperty("ner.applyFineGrained", "false");
    props.setProperty("ner.buildEntityMentions", "false");
    return props;
  }

  private static NERCombinerAnnotator annotator(Properties props) throws IOException {
    Properties original = new Properties();
    original.putAll(props);
    NERCombinerAnnotator annotator = new NERCombinerAnnotator(props);
    assertEquals("Construction must not change the caller's properties", original, props);
    return annotator;
  }

  private static void checkTags(NERCombinerAnnotator annotator, String text,
                               String[] pos, String... expectedTags) {
    Annotation document = new Annotation(text);
    // TokenizerAnnotator also runs the real sentence splitter.
    new TokenizerAnnotator("es").annotate(document);
    List<CoreLabel> tokens = document.get(CoreAnnotations.TokensAnnotation.class);
    assertEquals(pos.length, tokens.size());
    assertEquals(1, document.get(CoreAnnotations.SentencesAnnotation.class).size());
    List<String> originalWords = new ArrayList<>();
    List<Integer> originalBegins = new ArrayList<>();
    List<Integer> originalEnds = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      CoreLabel token = tokens.get(i);
      originalWords.add(token.word());
      originalBegins.add(token.beginPosition());
      originalEnds.add(token.endPosition());
      // Known inputs to the rules, consumed by the real PresetSequenceClassifier.
      token.setTag(pos[i]);
      token.setLemma(token.word().toLowerCase(Locale.ROOT));
      token.set(CoreAnnotations.PresetAnswerAnnotation.class, "O");
    }
    annotator.annotate(document);
    List<CoreLabel> output = document.get(CoreAnnotations.TokensAnnotation.class);
    assertEquals(tokens.size(), output.size());
    List<String> actualTags = new ArrayList<>();
    for (int i = 0; i < output.size(); i++) {
      CoreLabel token = output.get(i);
      assertSame(tokens.get(i), token);
      assertEquals(originalWords.get(i), token.word());
      assertEquals(originalBegins.get(i).intValue(), token.beginPosition());
      assertEquals(originalEnds.get(i).intValue(), token.endPosition());
      assertEquals(token.word(), text.substring(token.beginPosition(), token.endPosition()));
      actualTags.add(token.ner());
    }
    assertEquals(Arrays.asList(expectedTags), actualTags);
  }

  @Test
  public void testCustomRulesKeepCaseAndPosConstraints() throws IOException {
    Properties props = properties();
    props.setProperty(MAPPING, rules("custom.tag", "NUMBER").toString());
    checkTags(annotator(props), "veinticinco VEINTICINCO otro veinticinco",
        new String[] {"NUM", "NUM", "NUM", "NC"}, "NUMBER", "NUMBER", "O", "O");
  }

  @Test
  public void testDefaultRulesWhenMappingIsAbsent() throws IOException {
    checkTags(annotator(properties()), "veinticinco", new String[] {"NUM"}, "ORDINAL");
  }

  @Test
  public void testInstancesKeepIndependentMappings() throws IOException {
    Properties firstProps = properties();
    firstProps.setProperty(MAPPING, rules("first.tag", "NUMBER").toString());
    Properties secondProps = properties();
    secondProps.setProperty(MAPPING, rules("second.tag", "PERCENT").toString());
    NERCombinerAnnotator first = annotator(firstProps);
    NERCombinerAnnotator second = annotator(secondProps);
    checkTags(first, "veinticinco", new String[] {"NUM"}, "NUMBER");
    checkTags(second, "veinticinco", new String[] {"NUM"}, "PERCENT");
    checkTags(first, "veinticinco", new String[] {"NUM"}, "NUMBER");
  }

  @Test
  public void testMissingMappingReportsCustomPath() throws IOException {
    String missing = temporaryFolder.getRoot().toPath().resolve("missing.tag").toString();
    for (String numericEnabled : Arrays.asList("true", "false")) {
      Properties props = properties();
      props.setProperty(MAPPING, missing);
      props.setProperty("ner.applyNumericClassifiers", numericEnabled);
      try {
        annotator(props);
        fail("The explicit missing file must fail even when numeric annotation is disabled");
      } catch (RuntimeIOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains(missing));
      }
    }
  }

  @Test
  public void testDisabledNumericAndOtherLanguageSkipSpanishRules() throws IOException {
    Properties disabled = properties();
    disabled.setProperty(MAPPING, rules("disabled.tag", "NUMBER").toString());
    disabled.setProperty("ner.applyNumericClassifiers", "false");
    checkTags(annotator(disabled), "veinticinco", new String[] {"NUM"}, "O");

    Properties english = properties();
    english.setProperty("ner.language", "en");
    english.setProperty(MAPPING, temporaryFolder.getRoot().toPath().resolve("unused.tag").toString());
    checkTags(annotator(english), "veinticinco", new String[] {"NUM"}, "O");
  }

  private Properties pipelineProperties(String file, String label) throws IOException {
    Properties props = properties();
    props.setProperty("annotators", "tokenize,ssplit,ner");
    props.setProperty("tokenize.language", "es");
    props.setProperty(MAPPING, rules(file, label).toString());
    return props;
  }

  private static NERCombinerAnnotator ner(StanfordCoreNLP pipeline) {
    return (NERCombinerAnnotator) pipeline.pool.get("ner");
  }

  @Test
  public void testPipelinesWithDifferentMappingsDoNotShareTheAnnotator() throws IOException {
    Properties firstProps = pipelineProperties("first-pipeline.tag", "NUMBER");
    Properties secondProps = pipelineProperties("second-pipeline.tag", "PERCENT");
    StanfordCoreNLP.clearAnnotatorPool();
    try {
      StanfordCoreNLP first = new StanfordCoreNLP(firstProps, false);
      StanfordCoreNLP second = new StanfordCoreNLP(secondProps, false);
      checkTags(ner(first), "veinticinco", new String[] {"NUM"}, "NUMBER");
      checkTags(ner(second), "veinticinco", new String[] {"NUM"}, "PERCENT");
      assertNotSame(ner(first), ner(second));

      // The same properties still reuse the cached annotator
      StanfordCoreNLP third = new StanfordCoreNLP(secondProps, false);
      assertSame(ner(second), ner(third));
    } finally {
      StanfordCoreNLP.clearAnnotatorPool();
    }
  }

  @Test
  public void testPipelinesWithDifferentMappingsAfterClearingThePool() throws IOException {
    Properties firstProps = pipelineProperties("first-cleared.tag", "NUMBER");
    Properties secondProps = pipelineProperties("second-cleared.tag", "PERCENT");
    StanfordCoreNLP.clearAnnotatorPool();
    try {
      StanfordCoreNLP first = new StanfordCoreNLP(firstProps, false);
      StanfordCoreNLP.clearAnnotatorPool();
      StanfordCoreNLP second = new StanfordCoreNLP(secondProps, false);
      assertNotSame(ner(first), ner(second));
      checkTags(ner(first), "veinticinco", new String[] {"NUM"}, "NUMBER");
      checkTags(ner(second), "veinticinco", new String[] {"NUM"}, "PERCENT");
    } finally {
      StanfordCoreNLP.clearAnnotatorPool();
    }
  }
}
