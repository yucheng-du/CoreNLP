package edu.stanford.nlp.pipeline;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.util.ArrayCoreMap;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Pair;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Exercises custom sentence annotations through the normal document readers. */
public class ProtobufAnnotationSerializerExtensionTest {
  private static final Descriptors.FieldDescriptor FIRST = extension(101);
  private static final Descriptors.FieldDescriptor SECOND = extension(102);

  public static class MarkerAnnotation implements CoreAnnotation<Integer> {
    @Override
    public Class<Integer> getType() {
      return Integer.class;
    }
  }

  private static class ExtensionSerializer extends ProtobufAnnotationSerializer {
    private final Descriptors.FieldDescriptor field;
    private final ExtensionRegistry registry = ExtensionRegistry.newInstance();
    private CoreNLPProtos.Sentence lastReadSentence;

    ExtensionSerializer(Descriptors.FieldDescriptor field) {
      this.field = field;
      registry.add(field);
    }

    protected ExtensionRegistry getExtensionRegistry() {
      return registry;
    }

    @Override
    protected CoreNLPProtos.Sentence.Builder toProtoBuilder(CoreMap sentence, Set<Class<?>> keys) {
      CoreNLPProtos.Sentence.Builder builder = super.toProtoBuilder(sentence, keys);
      Integer marker = sentence.get(MarkerAnnotation.class);
      if (marker != null) {
        builder.setField(field, marker);
        keys.remove(MarkerAnnotation.class);
      }
      return builder;
    }

    @Override
    protected CoreMap fromProtoNoTokens(CoreNLPProtos.Sentence proto) {
      lastReadSentence = proto;
      CoreMap sentence = super.fromProtoNoTokens(proto);
      if (proto.hasField(field)) {
        sentence.set(MarkerAnnotation.class, (Integer) proto.getField(field));
      }
      return sentence;
    }
  }

  @Test
  public void testDelimitedRoundTripPreservesFramingAndStreamOwnership() throws Exception {
    ExtensionSerializer serializer = new ExtensionSerializer(FIRST);
    TrackingOutputStream output = new TrackingOutputStream();
    assertSame(output, serializer.write(document(17), output));
    assertSame(output, serializer.write(document(29), output));
    assertFalse(output.closed);
    byte[] bytes = output.toByteArray();

    ByteArrayInputStream control = new ByteArrayInputStream(bytes);
    assertWireMarker(CoreNLPProtos.Document.parseDelimitedFrom(control, serializer.registry), FIRST, 17);
    assertWireMarker(CoreNLPProtos.Document.parseDelimitedFrom(control, serializer.registry), FIRST, 29);
    assertEquals(-1, control.read());

    TrackingInputStream input = new TrackingInputStream(bytes);
    Pair<Annotation, InputStream> first = serializer.read(input);
    assertSame(input, first.second());
    assertFalse(input.closed);
    Pair<Annotation, InputStream> second = serializer.read(first.second());
    assertSame(input, second.second());
    assertFalse(input.closed);
    assertEquals(-1, input.read());
    assertDocument(first.first(), 17);
    assertDocument(second.first(), 29);
  }

  @Test
  public void testUndelimitedFileRoundTrip() throws Exception {
    ExtensionSerializer serializer = new ExtensionSerializer(FIRST);
    byte[] bytes = serializer.toProto(document(17)).toByteArray();
    assertWireMarker(CoreNLPProtos.Document.parseFrom(bytes, serializer.registry), FIRST, 17);
    assertDocument(readFile(serializer, bytes), 17);
  }

  @Test
  public void testUndelimitedReaderFallsBackToDelimitedFile() throws Exception {
    ExtensionSerializer serializer = new ExtensionSerializer(FIRST);
    Annotation document = document(17);
    StringBuilder docId = new StringBuilder("fallback");
    for (int padding = 0; padding < 8; ++padding) {
      document.set(CoreAnnotations.DocIDAnnotation.class, docId.toString());
      if ((serializer.toProto(document).getSerializedSize() & 7) == 6) {
        break;
      }
      docId.append('x');
    }
    byte[] bytes = delimited(serializer, document);
    // The legal length prefix is an invalid wire type when read as an unframed message.
    assertEquals(6, bytes[0] & 7);
    try {
      CoreNLPProtos.Document.parseFrom(bytes, serializer.registry);
      fail("The fixture must exercise the delimited fallback");
    } catch (InvalidProtocolBufferException expected) {
      // The same bytes are a valid delimited document, checked below.
    }
    assertWireMarker(CoreNLPProtos.Document.parseDelimitedFrom(
        new ByteArrayInputStream(bytes), serializer.registry), FIRST, 17);
    Annotation restored = readFile(serializer, bytes);
    assertDocument(restored, 17);
    assertEquals(document.get(CoreAnnotations.DocIDAnnotation.class),
        restored.get(CoreAnnotations.DocIDAnnotation.class));
  }

  @Test
  public void testRegistriesAreSpecificToEachSerializer() throws Exception {
    ExtensionSerializer first = new ExtensionSerializer(FIRST);
    ExtensionSerializer second = new ExtensionSerializer(SECOND);
    byte[] firstBytes = delimited(first, document(17));
    byte[] secondBytes = delimited(second, document(29));

    assertDocument(read(first, firstBytes), 17);
    assertDocument(read(second, firstBytes), null);
    assertFalse(second.lastReadSentence.hasField(FIRST));
    assertEquals(Collections.singletonList(17L),
        second.lastReadSentence.getUnknownFields().getField(FIRST.getNumber()).getVarintList());
    assertDocument(read(second, secondBytes), 29);
    assertDocument(read(first, secondBytes), null);
    assertFalse(first.lastReadSentence.hasField(SECOND));
    assertEquals(Collections.singletonList(29L),
        first.lastReadSentence.getUnknownFields().getField(SECOND.getNumber()).getVarintList());
    assertDocument(read(first, firstBytes), 17);
  }

  @Test
  public void testDefaultConstructorsReadOrdinaryAnnotations() throws Exception {
    ProtobufAnnotationSerializer[] serializers = {
        new ProtobufAnnotationSerializer(),
        new ProtobufAnnotationSerializer(true),
        new ProtobufAnnotationSerializer(false)
    };
    for (ProtobufAnnotationSerializer serializer : serializers) {
      assertDocument(read(serializer, delimited(serializer, document(null))), null);
      assertDocument(readFile(serializer, serializer.toProto(document(null)).toByteArray()), null);
    }
  }

  @Test
  public void testUnregisteredExtensionRemainsAnUnknownWireField() throws Exception {
    ExtensionSerializer writer = new ExtensionSerializer(FIRST);
    byte[] bytes = delimited(writer, document(17));
    CoreNLPProtos.Document unknown = CoreNLPProtos.Document.parseDelimitedFrom(
        new ByteArrayInputStream(bytes));
    CoreNLPProtos.Sentence sentence = unknown.getSentence(0);
    assertFalse(sentence.hasField(FIRST));
    assertEquals(Collections.singletonList(17L),
        sentence.getUnknownFields().getField(101).getVarintList());
    assertWireMarker(CoreNLPProtos.Document.parseFrom(unknown.toByteArray(), writer.registry), FIRST, 17);
    assertDocument(read(new ProtobufAnnotationSerializer(), bytes), null);
  }

  private static Descriptors.FieldDescriptor extension(int number) {
    Descriptors.FileDescriptor core = CoreNLPProtos.getDescriptor();
    DescriptorProtos.FileDescriptorProto descriptor = DescriptorProtos.FileDescriptorProto.newBuilder()
        .setName("serializer_test_extension_" + number + ".proto")
        .setPackage("serializer.test")
        .setSyntax("proto2")
        .addDependency(core.getName())
        .addExtension(DescriptorProtos.FieldDescriptorProto.newBuilder()
            .setName("sentence_marker_" + number)
            .setExtendee("." + CoreNLPProtos.Sentence.getDescriptor().getFullName())
            .setNumber(number)
            .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32))
        .build();
    try {
      return Descriptors.FileDescriptor.buildFrom(descriptor,
          new Descriptors.FileDescriptor[] {core}).getExtensions().get(0);
    } catch (Descriptors.DescriptorValidationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Annotation document(Integer marker) {
    CoreLabel token = new CoreLabel();
    token.setWord("hello");
    token.setOriginalText("hello");
    token.setBeginPosition(0);
    token.setEndPosition(5);
    token.setIndex(1);
    List<CoreLabel> tokens = Collections.singletonList(token);
    CoreMap sentence = new ArrayCoreMap();
    sentence.set(CoreAnnotations.TextAnnotation.class, "hello");
    sentence.set(CoreAnnotations.TokensAnnotation.class, tokens);
    sentence.set(CoreAnnotations.TokenBeginAnnotation.class, 0);
    sentence.set(CoreAnnotations.TokenEndAnnotation.class, 1);
    sentence.set(CoreAnnotations.CharacterOffsetBeginAnnotation.class, 0);
    sentence.set(CoreAnnotations.CharacterOffsetEndAnnotation.class, 5);
    sentence.set(CoreAnnotations.SentenceIndexAnnotation.class, 0);
    if (marker != null) {
      sentence.set(MarkerAnnotation.class, marker);
    }
    Annotation document = new Annotation("hello");
    document.set(CoreAnnotations.TokensAnnotation.class, tokens);
    document.set(CoreAnnotations.SentencesAnnotation.class, Collections.singletonList(sentence));
    return document;
  }

  private static void assertDocument(Annotation document, Integer marker) {
    assertEquals("hello", document.get(CoreAnnotations.TextAnnotation.class));
    assertEquals(1, document.get(CoreAnnotations.SentencesAnnotation.class).size());
    CoreMap sentence = document.get(CoreAnnotations.SentencesAnnotation.class).get(0);
    assertEquals("hello", sentence.get(CoreAnnotations.TextAnnotation.class));
    assertEquals(Integer.valueOf(0), sentence.get(CoreAnnotations.TokenBeginAnnotation.class));
    assertEquals(Integer.valueOf(1), sentence.get(CoreAnnotations.TokenEndAnnotation.class));
    List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
    assertEquals(1, tokens.size());
    CoreLabel token = tokens.get(0);
    assertEquals("hello", token.word());
    assertEquals("hello", token.originalText());
    assertEquals(0, token.beginPosition());
    assertEquals(5, token.endPosition());
    assertEquals(1, token.index());
    assertSame(token, document.get(CoreAnnotations.TokensAnnotation.class).get(0));
    assertEquals(marker, sentence.get(MarkerAnnotation.class));
  }

  private static void assertWireMarker(CoreNLPProtos.Document document,
                                       Descriptors.FieldDescriptor field, int marker) {
    assertSame(CoreNLPProtos.Sentence.getDescriptor(), field.getContainingType());
    assertTrue(document.getSentence(0).hasField(field));
    assertEquals(Integer.valueOf(marker), document.getSentence(0).getField(field));
    assertFalse(document.getSentence(0).getUnknownFields().hasField(field.getNumber()));
  }

  private static byte[] delimited(ProtobufAnnotationSerializer serializer, Annotation document)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    serializer.write(document, output);
    return output.toByteArray();
  }

  private static Annotation read(ProtobufAnnotationSerializer serializer, byte[] bytes) throws Exception {
    return serializer.read(new ByteArrayInputStream(bytes)).first();
  }

  private static Annotation readFile(ProtobufAnnotationSerializer serializer, byte[] bytes)
      throws IOException {
    Path path = Files.createTempFile("corenlp-extension-", ".protobuf");
    try {
      Files.write(path, bytes);
      return serializer.readUndelimited(path.toFile());
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static class TrackingInputStream extends ByteArrayInputStream {
    boolean closed;

    TrackingInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static class TrackingOutputStream extends ByteArrayOutputStream {
    boolean closed;

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
