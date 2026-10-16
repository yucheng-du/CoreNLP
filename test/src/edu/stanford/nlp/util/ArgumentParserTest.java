package edu.stanford.nlp.util;

import edu.stanford.nlp.util.logging.LogRecordHandler;
import edu.stanford.nlp.util.logging.Redwood;
import edu.stanford.nlp.util.logging.RedwoodConfiguration;
import org.junit.Before;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * A small test for the {@link ArgumentParser} class for loading command line options
 *
 * @author Gabor Angeli
 */
public class ArgumentParserTest {

  public static class StaticClass {
    @ArgumentParser.Option(name="option.static")
    public static int staticOption = -1;

    private StaticClass() { }
  }

  public static class NonstaticClass {
    @ArgumentParser.Option(name="option.nonstatic")
    public int staticOption = -1;
  }

  public static class MixedClass {
    @ArgumentParser.Option(name="option.static")
    public static int staticOption = -1;
    @ArgumentParser.Option(name="option.nonstatic")
    public int nonstaticOption = -1;
  }

  public static class SensitiveClass {
    @ArgumentParser.Option(name="option.secret", sensitive=true)
    public static String secretOption;
    @ArgumentParser.Option(name="option.secretNumber", sensitive=true)
    public static Integer secretNumber;
    @ArgumentParser.Option(name="option.visible")
    public static String visibleOption;
  }

  /** Capture raw log records while preserving the caller's parser and logging state. */
  private static String captureParserLogs(boolean verbose, boolean strict, Runnable action) throws Exception {
    Field verboseField = ArgumentParser.class.getDeclaredField("verbose");
    Field strictField = ArgumentParser.class.getDeclaredField("strict");
    Field handlersField = Redwood.class.getDeclaredField("handlers");
    verboseField.setAccessible(true);
    strictField.setAccessible(true);
    handlersField.setAccessible(true);
    boolean savedVerbose = verboseField.getBoolean(null);
    boolean savedStrict = strictField.getBoolean(null);
    Object savedHandlers = handlersField.get(null);
    String savedSecret = SensitiveClass.secretOption;
    Integer savedNumber = SensitiveClass.secretNumber;
    String savedVisible = SensitiveClass.visibleOption;
    StringWriter output = new StringWriter();
    LogRecordHandler capture = new LogRecordHandler() {
      @Override
      public List<Redwood.Record> handle(Redwood.Record record) {
        if (record.content instanceof Throwable) {
          ((Throwable) record.content).printStackTrace(new PrintWriter(output));
        } else {
          output.append(String.valueOf(record.content)).append('\n');
        }
        return EMPTY;
      }
    };
    Constructor<?> rootConstructor = handlersField.getType().getDeclaredConstructor();
    rootConstructor.setAccessible(true);
    try {
      // Replace the tree instead of clearing it, so existing handlers and streams survive.
      handlersField.set(null, rootConstructor.newInstance());
      RedwoodConfiguration.current().handlers(
          RedwoodConfiguration.Handlers.chain(capture, RedwoodConfiguration.Handlers.noop)).apply();
      verboseField.setBoolean(null, verbose);
      strictField.setBoolean(null, strict);
      action.run();
      return output.toString();
    } finally {
      handlersField.set(null, savedHandlers);
      verboseField.setBoolean(null, savedVerbose);
      strictField.setBoolean(null, savedStrict);
      SensitiveClass.secretOption = savedSecret;
      SensitiveClass.secretNumber = savedNumber;
      SensitiveClass.visibleOption = savedVisible;
    }
  }

  @Before
  public void setUp() {
    StaticClass.staticOption = -1;
    MixedClass.staticOption = -1;
  }

  @Test
  public void testFillStaticField() {
    assertEquals(-1, StaticClass.staticOption);
    ArgumentParser.fillOptions(StaticClass.class, "-option.static", "42");
    assertEquals(42, StaticClass.staticOption);
  }

  @Test
  public void testFillStaticFieldFromProperties() {
    assertEquals(-1, StaticClass.staticOption);
    Properties props = new Properties();
    props.setProperty("option.static", "42");
    ArgumentParser.fillOptions(StaticClass.class, props);
    assertEquals(42, StaticClass.staticOption);
  }

  @Test
  public void fillNonstaticField() {
    NonstaticClass x = new NonstaticClass();
    assertEquals(-1, x.staticOption);
    ArgumentParser.fillOptions(x, "-option.nonstatic", "42");
    assertEquals(42, x.staticOption);
  }

  @Test
  public void fillNonstaticFieldFromProperties() {
    NonstaticClass x = new NonstaticClass();
    assertEquals(-1, x.staticOption);
    Properties props = new Properties();
    props.setProperty("option.nonstatic", "42");
    ArgumentParser.fillOptions(x, props);
    assertEquals(42, x.staticOption);
  }

  @Test
  public void fillMixedFieldsInstanceGiven() {
    MixedClass x = new MixedClass();
    assertEquals(-1, MixedClass.staticOption);
    assertEquals(-1, x.nonstaticOption);
    ArgumentParser.fillOptions(x, "-option.nonstatic", "42", "-option.static", "43");
    assertEquals(43, MixedClass.staticOption);
    assertEquals(42, x.nonstaticOption);
  }

  @Test
  public void fillMixedFieldsNoInstanceGiven() {
    MixedClass x = new MixedClass();
    assertEquals(-1, MixedClass.staticOption);
    assertEquals(-1, x.nonstaticOption);
    ArgumentParser.fillOptions(MixedClass.class, "-option.nonstatic", "42", "-option.static", "43");
    assertEquals(43, MixedClass.staticOption);
    assertEquals(-1, x.nonstaticOption);
  }

  /** Check that command-line arguments override properties. */
  @Test
  public void checkOptionsOverrideProperties() {
    NonstaticClass x = new NonstaticClass();
    assertEquals(-1, x.staticOption);
    Properties props = new Properties();
    props.setProperty("option.nonstatic", "78");
    ArgumentParser.fillOptions(x, props, "-option.nonstatic", "42");
    assertEquals(42, x.staticOption);
  }

  @Test
  public void testSensitiveValueIsAssignedButRedactedInVerboseLog() throws Exception {
    String secret = "private-string-option-value";
    String visible = "ordinary-string-option-value";
    Properties props = new Properties();
    props.setProperty("option.secret", secret);
    props.setProperty("option.visible", visible);
    String logs = captureParserLogs(true, false, () -> {
      ArgumentParser.fillOptions(SensitiveClass.class, props);
      assertEquals(secret, SensitiveClass.secretOption);
      assertEquals(visible, SensitiveClass.visibleOption);
    });
    assertTrue(logs.contains("option.secret"));
    assertTrue(logs.contains("<redacted>"));
    assertTrue(logs.contains(visible));
    assertFalse(logs.contains(secret));
  }

  @Test
  public void testSensitiveConversionFailureIsRedacted() throws Exception {
    String secret = "private-invalid-number-value";
    Properties props = new Properties();
    props.setProperty("option.secretNumber", secret);
    String logs = captureParserLogs(true, false, () -> {
      SensitiveClass.secretNumber = 17;
      try {
        ArgumentParser.fillOptions(SensitiveClass.class, props);
        fail("Expected an invalid integer option to fail");
      } catch (RuntimeException expected) {
        StringWriter exceptionText = new StringWriter();
        expected.printStackTrace(new PrintWriter(exceptionText));
        assertFalse(exceptionText.toString().contains(secret));
        assertTrue(expected.getMessage().contains("secretNumber"));
        assertTrue(expected.getMessage().contains("<redacted>"));
      }
      assertEquals(Integer.valueOf(17), SensitiveClass.secretNumber);
    });
    assertTrue(logs.contains("secretNumber"));
    assertTrue(logs.contains("<redacted>"));
    assertFalse(logs.contains(secret));
  }

  @Test
  public void testSensitiveFullyQualifiedOverrideIsRedacted() throws Exception {
    String secret = "private-fully-qualified-option-value";
    Properties props = new Properties();
    props.setProperty(SensitiveClass.class.getName() + ".secretOption", secret);
    String logs = captureParserLogs(false, true, () -> {
      ArgumentParser.fillOptions(SensitiveClass.class, props);
      assertEquals(secret, SensitiveClass.secretOption);
    });
    assertTrue(logs.contains("option overrides"));
    assertTrue(logs.contains("secretOption"));
    assertTrue(logs.contains("<redacted>"));
    assertFalse(logs.contains(secret));
  }

}
