package edu.stanford.nlp.pipeline;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.regex.Pattern;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.ud.CoNLLUDocumentWriter;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Pair;

/**
 * <p>Write a subset of our CoreNLP output in CoNLL-U format.</p>
 *
 * <p>The fields currently output are:</p>
 *
 * <table>
 * <caption>Output fields</caption>
 *   <tr>
 *     <td>Field Number</td>
 *     <td>Field Name</td>
 *     <td>Description</td>
 *   </tr>
 *   <tr>
 *     <td>1</td>
 *     <td>ID</td>
 *     <td>Word index, integer starting at 1 for each new sentence; may be a range for tokens with multiple words.</td>
 *   </tr>
 *   <tr>
 *     <td>2</td>
 *     <td>FORM</td>
 *     <td>Word form or punctuation symbol.</td>
 *   </tr>
 *   <tr>
 *     <td>3</td>
 *     <td>LEMMA</td>
 *     <td>Lemma or stem of word form, or an underscore if not available.</td>
 *   </tr>
 *   <tr>
 *     <td>4</td>
 *     <td>CPOSTAG</td>
 *     <td>Universal part-of-speech tag, or underscore if not available.</td>
 *   </tr>
 *   <tr>
 *     <td>5</td>
 *     <td>POSTAG</td>
 *     <td>Language-specific part-of-speech tag, or underscore if not available.</td>
 *   </tr>
 *   <tr>
 *     <td>6</td>
 *     <td>FEATS</td>
 *     <td>List of morphological features from the universal feature inventory or from a defined language-specific extension; underscore if not available.</td>
 *   </tr>
 *   <tr>
 *     <td>7</td>
 *     <td>HEAD</td>
 *     <td>Head of the current token, which is either a value of ID or zero ('0').
 *         This is underscore if not available.</td>
 *   </tr>
 *   <tr>
 *     <td>8</td>
 *     <td>DEPREL</td>
 *     <td>Dependency relation to the HEAD, or underscore if not available.</td>
 *   </tr>
 *   <tr>
 *     <td>9</td>
 *     <td>DEPS</td>
 *     <td>List of secondary dependencies</td>
 *   </tr>
 *   <tr>
 *     <td>10</td>
 *     <td>MISC</td>
 *     <td>Any other annotation</td>
 *   </tr>
 * </table>
 *
 * @author Sebastian Schuster
 * @author Gabor Angeli
 *
 */
public class CoNLLUOutputter extends AnnotationOutputter {

  private static final CoNLLUDocumentWriter conllUWriter = new CoNLLUDocumentWriter();

  /**
   * The type of dependencies to print, options:
   * - basic
   * - enhanced
   * - enhancedPlusPlus
   *
   * basic is the default
   */
  private final SemanticGraphCoreAnnotations.DependenciesType dependenciesType;

  public CoNLLUOutputter() {
    this(new Properties());
  }

  public CoNLLUOutputter(String type) {
    this(new Properties() {{
           setProperty("output.dependenciesType", type);
    }});
  }

  public CoNLLUOutputter(Properties props) {
    dependenciesType = SemanticGraphCoreAnnotations.DependenciesType.valueOf(props.getProperty("output.dependenciesType", "basic").toUpperCase(Locale.ROOT));
  }

  @Override
  public void print(Annotation doc, OutputStream target, Options options) throws IOException {
    PrintWriter writer = new PrintWriter(IOUtils.encodedOutputStreamWriter(target, options.encoding));

    List<CoreMap> sentences = doc.get(CoreAnnotations.SentencesAnnotation.class);
    for (CoreMap sentence : sentences) {
      SemanticGraph sg = sentence.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
      List<String> comments = sentenceComments(sentence, sg);
      if (sg != null) {
        switch (dependenciesType) {
        case ENHANCED:
        case ENHANCEDPLUSPLUS:
          writer.print(conllUWriter.printSemanticGraph(sg, sentence.get(dependenciesType.annotation()), true, comments));
          break;
        case BASIC:
          writer.print(conllUWriter.printSemanticGraph(sg, null, true, comments));
          break;
        default:
          throw new IllegalArgumentException("CoNLLUOutputter: unknown dependencies type " + dependenciesType);
        }
      } else {
        writer.print(conllUWriter.printPOSAnnotations(sentence, options.printFakeDeps, comments));
      }
    }
    writer.flush();
  }

  /** A newdoc or newpar line, which a sent_id is written after rather than before */
  private static final Pattern DOCUMENT_OR_PARAGRAPH_LINE = Pattern.compile("^# new(doc|par)(\\s.*)?$");

  /**
   * The comments to write above a sentence.
   *<br>
   * These are the comments on its basic graph if it has any, as they
   * always have been, and otherwise the CommentsAnnotation the sentence
   * was given, such as the comments CoNLLUReader read for it.  Either way
   * the sent_id among them is made to agree with the sentence's
   * SentenceIDAnnotation, if it has one.
   */
  static List<String> sentenceComments(CoreMap sentence, SemanticGraph basic) {
    List<String> comments = basic == null ? Collections.emptyList() : basic.getComments();
    if (comments.isEmpty()) {
      List<String> sentenceComments = sentence.get(CoreAnnotations.CommentsAnnotation.class);
      if (sentenceComments != null) {
        comments = sentenceComments;
      }
    }
    return withSentenceID(comments, sentence.get(CoreAnnotations.SentenceIDAnnotation.class));
  }

  /**
   * The comments with exactly one sent_id, the one given, or the comments unchanged if no id is given.
   *<br>
   * A sentence whose id has been changed since it was read would
   * otherwise be written with the id it was read with.  The sent_id line
   * is replaced where it stands, and kept as it was written if it already
   * has this id.  Any later sent_id is dropped, since it would contradict
   * the first.  If there is no sent_id line, one is put after any newdoc
   * and newpar lines at the start, which is where the UD treebanks have it,
   * and after a global.columns line, which has to be the first line of a file.
   *<br>
   * The list given is not changed.
   */
  static List<String> withSentenceID(List<String> comments, String sentenceID) {
    if (sentenceID == null) {
      return comments;
    }
    String sentIdLine = "# sent_id = " + sentenceID;
    List<String> result = new ArrayList<>(comments.size() + 1);
    boolean written = false;
    boolean leading = true;
    int insertAt = 0;
    for (String comment : comments) {
      Pair<String, String> keyValue = CoNLLUReader.parseSentenceDataLine(comment);
      if (keyValue != null && keyValue.first().equals("sent_id")) {
        if (!written) {
          result.add(keyValue.second().equals(sentenceID) ? comment : sentIdLine);
          written = true;
        }
        continue;
      }
      result.add(comment);
      boolean globalColumns = keyValue != null && keyValue.first().equals("global.columns");
      if (leading && (globalColumns || DOCUMENT_OR_PARAGRAPH_LINE.matcher(comment).matches())) {
        insertAt = result.size();
      } else {
        leading = false;
      }
    }
    if (!written) {
      result.add(insertAt, sentIdLine);
    }
    return result;
  }


  public static void conllUPrint(Annotation annotation, OutputStream os) throws IOException {
    new CoNLLUOutputter().print(annotation, os);
  }

  public static void conllUPrint(Annotation annotation, OutputStream os, StanfordCoreNLP pipeline) throws IOException {
    new CoNLLUOutputter().print(annotation, os, pipeline);
  }

  public static void conllUPrint(Annotation annotation, OutputStream os, Options options) throws IOException {
    new CoNLLUOutputter().print(annotation, os, options);
  }

}
