package edu.stanford.nlp.trees.ud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreNLPProtos;
import edu.stanford.nlp.pipeline.ProtobufAnnotationSerializer;
import edu.stanford.nlp.semgraph.SemanticGraph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CoNLLUDocumentWriterTest {
  private static SemanticGraph basicGraph() {
    return SemanticGraph.valueOf("[works-2 obl> [Paris-4 conj> London-6]]");
  }

  private static void setDependencies(SemanticGraph graph, int index, String deps) {
    graph.getNodeByIndex(index).set(CoreAnnotations.CoNLLUSecondaryDepsAnnotation.class,
                                  CoNLLUUtils.parseExtraDeps(deps));
  }

  private static void assertDependencies(String written, int index, String head, String relation, String deps) {
    for (String line : written.split("\\R")) {
      String[] columns = line.split("\t");
      if (columns[0].equals(Integer.toString(index))) {
        assertEquals(head, columns[6]);
        assertEquals(relation, columns[7]);
        assertEquals(deps, columns[8]);
        return;
      }
    }
    fail("Missing token " + index + " in " + written);
  }

  @Test
  public void testStoredRelationsWithBasicGovernor() {
    SemanticGraph graph = basicGraph();
    setDependencies(graph, 4, "2:obl:in");
    setDependencies(graph, 6, "2:obl:in|4:conj:and");

    String written = new CoNLLUDocumentWriter().printSemanticGraph(graph, null);
    assertDependencies(written, 4, "2", "obl", "2:obl:in");
    assertDependencies(written, 6, "4", "conj", "2:obl:in|4:conj:and");
    assertEquals(CoNLLUUtils.parseExtraDeps("2:obl:in|4:conj:and"),
                 graph.getNodeByIndex(6).get(CoreAnnotations.CoNLLUSecondaryDepsAnnotation.class));
  }

  @Test
  public void testBasicDependencyAddedWhenMissing() {
    SemanticGraph graph = basicGraph();
    setDependencies(graph, 6, "2:obl:in");

    String written = new CoNLLUDocumentWriter().printSemanticGraph(graph, null);
    assertDependencies(written, 6, "4", "conj", "2:obl:in|4:conj");
    assertEquals(CoNLLUUtils.parseExtraDeps("2:obl:in"),
                 graph.getNodeByIndex(6).get(CoreAnnotations.CoNLLUSecondaryDepsAnnotation.class));
  }

  @Test
  public void testMissingAndEmptyAnnotations() {
    SemanticGraph graph = basicGraph();
    setDependencies(graph, 6, "_");

    String written = new CoNLLUDocumentWriter().printSemanticGraph(graph, null);
    assertDependencies(written, 4, "2", "obl", "_");
    assertDependencies(written, 6, "4", "conj", "4:conj");
    assertEquals(new HashMap<String, String>(),
                 graph.getNodeByIndex(6).get(CoreAnnotations.CoNLLUSecondaryDepsAnnotation.class));
  }

  @Test
  public void testExplicitEnhancedGraphTakesPrecedence() {
    SemanticGraph graph = basicGraph();
    SemanticGraph enhanced = SemanticGraph.valueOf("[works-2 obl:in> [Paris-4 conj:and> London-6]]");
    setDependencies(enhanced, 4, "2:obl:at");
    setDependencies(enhanced, 6, "4:conj:or");

    String written = new CoNLLUDocumentWriter().printSemanticGraph(graph, enhanced);
    assertDependencies(written, 4, "2", "obl", "2:obl:in");
    assertDependencies(written, 6, "4", "conj", "4:conj:and");
  }

  @Test
  public void testSerializedSecondaryDependencies() {
    SemanticGraph graph = basicGraph();
    setDependencies(graph, 4, "2:obl:in");
    setDependencies(graph, 6, "2:obl:in|4:conj:and");
    ProtobufAnnotationSerializer serializer = new ProtobufAnnotationSerializer();
    CoreNLPProtos.DependencyGraph proto = serializer.toProto(graph, true);
    List<CoreLabel> tokens = new ArrayList<>();
    for (CoreNLPProtos.Token token : proto.getTokenList()) {
      tokens.add(serializer.fromProto(token));
    }
    SemanticGraph restored = ProtobufAnnotationSerializer.fromProto(proto, tokens, null);

    String written = new CoNLLUDocumentWriter().printSemanticGraph(restored, null);
    assertDependencies(written, 4, "2", "obl", "2:obl:in");
    assertDependencies(written, 6, "4", "conj", "2:obl:in|4:conj:and");
  }
}
