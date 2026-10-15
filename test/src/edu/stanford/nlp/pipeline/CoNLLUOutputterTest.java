package edu.stanford.nlp.pipeline;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.ud.CoNLLUDocumentWriter;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Pair;

/**
 * Tests that the sent_id written for a sentence is the one in its
 * SentenceIDAnnotation, so that changing the annotation of a sentence read
 * from CoNLL-U does not leave the old sent_id comment behind, and that the
 * rest of the comments come out as they went in.
 *
 * None of this needs a model: the sentences are read from small CoNLL-U
 * strings, or built by hand.
 */
public class CoNLLUOutputterTest {

  private static final String TWO_SENTENCES = String.join("\n",
      "# newdoc id = doc-1",
      "# sent_id = s1",
      "# text = Hola mundo",
      "# custom = keep me",
      "1\tHola\thola\tINTJ\t_\t_\t0\troot\t_\t_",
      "2\tmundo\tmundo\tNOUN\t_\t_\t1\tdep\t_\tSpaceAfter=No",
      "",
      "# sent_id = s2",
      "# text = Adiós",
      "1\tAdiós\tadiós\tINTJ\t_\t_\t0\troot\t_\tSpaceAfter=No",
      "",
      "");

  private static final String NO_SENT_ID = String.join("\n",
      "# newdoc id = doc-2",
      "# newpar",
      "# text = Hola mundo",
      "# custom = keep me",
      "1\tHola\thola\tINTJ\t_\t_\t0\troot\t_\t_",
      "2\tmundo\tmundo\tNOUN\t_\t_\t1\tdep\t_\tSpaceAfter=No",
      "",
      "");

  /** Gapping, so that there is an enhanced graph with an empty word in it */
  private static final String ENHANCED = String.join("\n",
      "# sent_id = e1",
      "# text = Ella come manzanas y él peras",
      "1\tElla\tella\tPRON\t_\t_\t2\tnsubj\t2:nsubj\t_",
      "2\tcome\tcomer\tVERB\t_\t_\t0\troot\t0:root\t_",
      "3\tmanzanas\tmanzana\tNOUN\t_\t_\t2\tobj\t2:obj\t_",
      "4\ty\ty\tCCONJ\t_\t_\t6\tcc\t6:cc\t_",
      "5\tél\tél\tPRON\t_\t_\t6\tnsubj\t6:nsubj\t_",
      "6\tperas\tpera\tNOUN\t_\t_\t2\tconj\t6.1:obj\tSpaceAfter=No",
      "6.1\tcome\tcomer\tVERB\t_\t_\t_\t_\t2:conj\t_",
      "",
      "");

  private static File writeTempFile(String conllu) throws Exception {
    File file = IOUtils.writeStringToTempFile(conllu, "conllutest", "UTF-8");
    file.deleteOnExit();
    return file;
  }

  private static Annotation read(String conllu) throws Exception {
    File file = writeTempFile(conllu);
    try {
      List<Annotation> documents = new CoNLLUReader().readCoNLLUFile(file.getPath());
      assertEquals(1, documents.size());
      return documents.get(0);
    } finally {
      file.delete();
    }
  }

  private static List<CoreMap> sentences(Annotation document) {
    return document.get(CoreAnnotations.SentencesAnnotation.class);
  }

  /** The comment lines written for each sentence, in order */
  private static List<List<String>> commentsBySentence(String conllu) {
    List<List<String>> comments = new ArrayList<>();
    List<String> current = new ArrayList<>();
    boolean inSentence = false;
    for (String line : conllu.split("\\R", -1)) {
      if (line.isEmpty()) {
        if (inSentence) {
          comments.add(current);
          current = new ArrayList<>();
          inSentence = false;
        }
      } else {
        inSentence = true;
        if (line.startsWith("#")) {
          current.add(line);
        }
      }
    }
    if (inSentence) {
      comments.add(current);
    }
    return comments;
  }

  @Test
  public void testUnchangedSentenceKeepsItsComments() throws Exception {
    Annotation document = read(TWO_SENTENCES);
    String output = new CoNLLUOutputter().print(document);
    assertEquals(Arrays.asList(
                     Arrays.asList("# newdoc id = doc-1", "# sent_id = s1", "# text = Hola mundo", "# custom = keep me"),
                     Arrays.asList("# sent_id = s2", "# text = Adiós")),
                 commentsBySentence(output));

    // and reading what was written gives back the same ids
    Annotation reread = read(output);
    assertEquals("s1", sentences(reread).get(0).get(CoreAnnotations.SentenceIDAnnotation.class));
    assertEquals("s2", sentences(reread).get(1).get(CoreAnnotations.SentenceIDAnnotation.class));
  }

  @Test
  public void testChangedSentenceIDReplacesTheOldOne() throws Exception {
    Annotation document = read(TWO_SENTENCES);
    CoreMap first = sentences(document).get(0);
    List<String> originalComments = new ArrayList<>(first.get(CoreAnnotations.CommentsAnnotation.class));
    first.set(CoreAnnotations.SentenceIDAnnotation.class, "renamed-1");

    String output = new CoNLLUOutputter().print(document);
    // the new id is written exactly once, where the old one was, and
    // the other comments stay where they were
    assertEquals(Arrays.asList(
                     Arrays.asList("# newdoc id = doc-1", "# sent_id = renamed-1", "# text = Hola mundo", "# custom = keep me"),
                     Arrays.asList("# sent_id = s2", "# text = Adiós")),
                 commentsBySentence(output));
    // writing the sentence does not change the comments it carries
    assertEquals(originalComments, first.get(CoreAnnotations.CommentsAnnotation.class));
    // so writing it a second time gives the same thing
    assertEquals(output, new CoNLLUOutputter().print(document));

    Annotation reread = read(output);
    assertEquals("renamed-1", sentences(reread).get(0).get(CoreAnnotations.SentenceIDAnnotation.class));
    assertEquals("s2", sentences(reread).get(1).get(CoreAnnotations.SentenceIDAnnotation.class));
  }

  @Test
  public void testSentenceIDAddedToASentenceWithoutOne() throws Exception {
    Annotation document = read(NO_SENT_ID);
    CoreMap sentence = sentences(document).get(0);
    assertNull(sentence.get(CoreAnnotations.SentenceIDAnnotation.class));
    // with no id given, nothing is made up
    assertEquals(Collections.singletonList(
                     Arrays.asList("# newdoc id = doc-2", "# newpar", "# text = Hola mundo", "# custom = keep me")),
                 commentsBySentence(new CoNLLUOutputter().print(document)));

    sentence.set(CoreAnnotations.SentenceIDAnnotation.class, "added-1");
    // the sent_id goes after the newdoc and newpar, as in the UD treebanks
    assertEquals(Collections.singletonList(
                     Arrays.asList("# newdoc id = doc-2", "# newpar", "# sent_id = added-1",
                                   "# text = Hola mundo", "# custom = keep me")),
                 commentsBySentence(new CoNLLUOutputter().print(document)));
  }

  private static final String GLOBAL_COLUMNS = "# global.columns = ID FORM LEMMA UPOS XPOS FEATS HEAD DEPREL DEPS MISC";

  @Test
  public void testSentenceIDAddedAfterGlobalColumns() throws Exception {
    // global.columns has to stay the first line of the file, so a sent_id
    // added to the first sentence goes after it, as well as after the newdoc
    List<String> expected = Arrays.asList(GLOBAL_COLUMNS, "# newdoc id = d1", "# sent_id = new-id", "# text = Hola");

    // the comments on the graph, as the graphs from graphIterator carry them
    Annotation document = read(String.join("\n",
        "1\tHola\thola\tINTJ\t_\t_\t0\troot\t_\t_",
        "",
        ""));
    CoreMap sentence = sentences(document).get(0);
    SemanticGraph graph = sentence.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
    graph.addComment(GLOBAL_COLUMNS);
    graph.addComment("# newdoc id = d1");
    graph.addComment("# text = Hola");
    sentence.set(CoreAnnotations.SentenceIDAnnotation.class, "new-id");
    assertEquals(Collections.singletonList(expected), commentsBySentence(new CoNLLUOutputter().print(document)));
    assertEquals(Arrays.asList(GLOBAL_COLUMNS, "# newdoc id = d1", "# text = Hola"), graph.getComments());

    // and the comments the reader keeps for the first sentence of a file
    document = read(String.join("\n",
        GLOBAL_COLUMNS,
        "# newdoc id = d1",
        "# text = Hola",
        "1\tHola\thola\tINTJ\t_\t_\t0\troot\t_\t_",
        "",
        ""));
    sentence = sentences(document).get(0);
    sentence.set(CoreAnnotations.SentenceIDAnnotation.class, "new-id");
    assertEquals(Collections.singletonList(expected), commentsBySentence(new CoNLLUOutputter().print(document)));
    assertEquals(Arrays.asList(GLOBAL_COLUMNS, "# newdoc id = d1", "# text = Hola"),
                 sentence.get(CoreAnnotations.CommentsAnnotation.class));
  }

  @Test
  public void testSentIdLineKeptAsWrittenWhenUnchanged() throws Exception {
    String text = String.join("\n",
        "#sent_id=compact",
        "1\tHola\thola\tINTJ\t_\t_\t0\troot\t_\t_",
        "",
        "");
    Annotation document = read(text);
    assertEquals(Collections.singletonList(Collections.singletonList("#sent_id=compact")),
                 commentsBySentence(new CoNLLUOutputter().print(document)));

    sentences(document).get(0).set(CoreAnnotations.SentenceIDAnnotation.class, "spaced");
    assertEquals(Collections.singletonList(Collections.singletonList("# sent_id = spaced")),
                 commentsBySentence(new CoNLLUOutputter().print(document)));
  }

  @Test
  public void testLookalikeCommentsAreNotTheSentId() throws Exception {
    String text = String.join("\n",
        "# sent_id_extra = x",
        "# this comment mentions sent_id",
        "1\tHola\thola\tINTJ\t_\t_\t0\troot\t_\t_",
        "",
        "");
    Annotation document = read(text);
    sentences(document).get(0).set(CoreAnnotations.SentenceIDAnnotation.class, "real");
    assertEquals(Collections.singletonList(
                     Arrays.asList("# sent_id = real", "# sent_id_extra = x", "# this comment mentions sent_id")),
                 commentsBySentence(new CoNLLUOutputter().print(document)));
  }

  @Test
  public void testRepeatedSentIdIsWrittenOnce() throws Exception {
    String text = String.join("\n",
        "# sent_id = first",
        "# text = Hola",
        "# sent_id = second",
        "1\tHola\thola\tINTJ\t_\t_\t0\troot\t_\t_",
        "",
        "");
    Annotation document = read(text);
    CoreMap sentence = sentences(document).get(0);
    // the reader keeps the last value it saw for a key
    assertEquals("second", sentence.get(CoreAnnotations.SentenceIDAnnotation.class));
    assertEquals(Collections.singletonList(Arrays.asList("# sent_id = second", "# text = Hola")),
                 commentsBySentence(new CoNLLUOutputter().print(document)));

    sentence.set(CoreAnnotations.SentenceIDAnnotation.class, "third");
    assertEquals(Collections.singletonList(Arrays.asList("# sent_id = third", "# text = Hola")),
                 commentsBySentence(new CoNLLUOutputter().print(document)));
  }

  @Test
  public void testWithoutSentenceIDTheCommentsAreWrittenAsTheyAre() throws Exception {
    Annotation document = read(TWO_SENTENCES);
    CoreMap first = sentences(document).get(0);
    first.remove(CoreAnnotations.SentenceIDAnnotation.class);
    // no id to go by, so the sent_id comment is left alone rather than dropped
    assertEquals(Arrays.asList("# newdoc id = doc-1", "# sent_id = s1", "# text = Hola mundo", "# custom = keep me"),
                 commentsBySentence(new CoNLLUOutputter().print(document)).get(0));
  }

  @Test
  public void testEnhancedOutput() throws Exception {
    Annotation document = read(ENHANCED);
    CoreMap sentence = sentences(document).get(0);
    sentence.set(CoreAnnotations.SentenceIDAnnotation.class, "e1-renamed");
    String output = new CoNLLUOutputter("enhanced").print(document);
    assertEquals(Collections.singletonList(
                     Arrays.asList("# sent_id = e1-renamed", "# text = Ella come manzanas y él peras")),
                 commentsBySentence(output));
    // the empty word of the enhanced graph is still written
    assertEquals(1, Arrays.stream(output.split("\\R")).filter(line -> line.startsWith("6.1\t")).count());
  }

  @Test
  public void testCommentsOnTheGraphAreStillUsed() throws Exception {
    // a sentence whose graph carries comments of its own, as the graphs
    // from CoNLLUReader.graphIterator do, has those written
    Annotation document = read(TWO_SENTENCES);
    CoreMap first = sentences(document).get(0);
    SemanticGraph graph = first.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
    graph.addComment("# sent_id = s1");
    graph.addComment("# from the graph");

    assertEquals(Arrays.asList("# sent_id = s1", "# from the graph"),
                 commentsBySentence(new CoNLLUOutputter().print(document)).get(0));

    first.set(CoreAnnotations.SentenceIDAnnotation.class, "graph-renamed");
    assertEquals(Arrays.asList("# sent_id = graph-renamed", "# from the graph"),
                 commentsBySentence(new CoNLLUOutputter().print(document)).get(0));
    // the graph's own comments are not changed by writing it
    assertEquals(Arrays.asList("# sent_id = s1", "# from the graph"), graph.getComments());
  }

  @Test
  public void testWriterGivenOnlyTheGraphs() throws Exception {
    // the writer handed just a pair of graphs has no sentence to ask for an
    // id, and writes the comments the graphs carry, as it always has
    File file = writeTempFile(TWO_SENTENCES);
    List<List<String>> written = new ArrayList<>();
    try (CoNLLUReader.GraphIterator graphs = new CoNLLUReader().graphIterator(file.getPath())) {
      CoNLLUDocumentWriter writer = new CoNLLUDocumentWriter();
      while (graphs.hasNext()) {
        Pair<SemanticGraph, SemanticGraph> pair = graphs.next();
        written.addAll(commentsBySentence(writer.printSemanticGraph(pair.first(), pair.second())));
      }
    } finally {
      file.delete();
    }
    assertEquals(Arrays.asList(
                     Arrays.asList("# newdoc id = doc-1", "# sent_id = s1", "# text = Hola mundo", "# custom = keep me"),
                     Arrays.asList("# sent_id = s2", "# text = Adiós")),
                 written);
  }

  /** A sentence with words and tags but no graph, as a tagger alone would leave it */
  private static Annotation posOnlyDocument(List<String> comments, String sentenceID) {
    List<CoreLabel> tokens = new ArrayList<>();
    String[] words = { "Hola", "mundo" };
    String[] tags = { "UH", "NN" };
    for (int i = 0; i < words.length; ++i) {
      CoreLabel token = new CoreLabel();
      token.setIndex(i + 1);
      token.setWord(words[i]);
      token.setValue(words[i]);
      token.setTag(tags[i]);
      tokens.add(token);
    }
    Annotation sentence = new Annotation("Hola mundo");
    sentence.set(CoreAnnotations.TokensAnnotation.class, tokens);
    if (comments != null) {
      sentence.set(CoreAnnotations.CommentsAnnotation.class, comments);
    }
    if (sentenceID != null) {
      sentence.set(CoreAnnotations.SentenceIDAnnotation.class, sentenceID);
    }
    Annotation document = new Annotation("Hola mundo");
    document.set(CoreAnnotations.SentencesAnnotation.class, Collections.singletonList(sentence));
    return document;
  }

  @Test
  public void testPOSOnlyOutputWithNothingToSayIsUnchanged() throws Exception {
    String output = new CoNLLUOutputter().print(posOnlyDocument(null, null));
    assertEquals("1\tHola\t_\t_\tUH\t_\t_\t_\t_\t_\n" +
                 "2\tmundo\t_\t_\tNN\t_\t_\t_\t_\t_\n" +
                 "\n",
                 output.replace(System.lineSeparator(), "\n"));
  }

  @Test
  public void testPOSOnlyOutputWritesTheSentenceID() throws Exception {
    List<String> comments = new ArrayList<>(Arrays.asList("# sent_id = old", "# text = Hola mundo"));
    Annotation document = posOnlyDocument(comments, "new");
    String output = new CoNLLUOutputter().print(document);
    assertEquals("# sent_id = new\n" +
                 "# text = Hola mundo\n" +
                 "1\tHola\t_\t_\tUH\t_\t_\t_\t_\t_\n" +
                 "2\tmundo\t_\t_\tNN\t_\t_\t_\t_\t_\n" +
                 "\n",
                 output.replace(System.lineSeparator(), "\n"));
    assertEquals(Arrays.asList("# sent_id = old", "# text = Hola mundo"), comments);

    // an id with no comments at all is still written
    assertEquals(Collections.singletonList(Collections.singletonList("# sent_id = only-id")),
                 commentsBySentence(new CoNLLUOutputter().print(posOnlyDocument(null, "only-id"))));
  }

}
