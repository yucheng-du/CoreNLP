package edu.stanford.nlp.pipeline;

import com.sun.net.httpserver.HttpsServer;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.util.ArgumentParser;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.logging.LogRecordHandler;
import edu.stanford.nlp.util.logging.Redwood;
import edu.stanford.nlp.util.logging.RedwoodConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Model-free SSL configuration tests; the HTTPS servers are never bound or started. */
public class StanfordCoreNLPServerSSLTest {
  private static final String CUSTOM_PASSWORD = "test-keystore-password";
  private static final String HTTP_PASSWORD = "test-http-password";

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private String originalKey;
  private Field keyPasswordField;
  private Object originalKeyPassword;
  private Object originalVerbose;
  private Object originalHandlers;
  private final StringBuilder logs = new StringBuilder();

  private static Field field(Class<?> clazz, String name) throws Exception {
    Field field = clazz.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  @Before
  public void setUp() throws Exception {
    originalKey = StanfordCoreNLPServer.key;
    // Also let this regression test run against the version without the new option.
    try {
      keyPasswordField = field(StanfordCoreNLPServer.class, "keyPassword");
      originalKeyPassword = keyPasswordField.get(null);
    } catch (NoSuchFieldException ignored) {
      keyPasswordField = null;
    }
    originalVerbose = field(ArgumentParser.class, "verbose").get(null);
    field(ArgumentParser.class, "verbose").set(null, false);
    originalHandlers = field(Redwood.class, "handlers").get(null);
    Constructor<?> rootConstructor = originalHandlers.getClass().getDeclaredConstructor();
    rootConstructor.setAccessible(true);
    field(Redwood.class, "handlers").set(null, rootConstructor.newInstance());
    RedwoodConfiguration.current().handlers(RedwoodConfiguration.Handlers.chain(new LogRecordHandler() {
      @Override
      public List<Redwood.Record> handle(Redwood.Record record) {
        logs.append(record.content).append('\n');
        return Collections.emptyList();
      }
    }, RedwoodConfiguration.Handlers.noop)).apply();
  }

  @After
  public void tearDown() throws Exception {
    StanfordCoreNLPServer.key = originalKey;
    if (keyPasswordField != null) {
      keyPasswordField.set(null, originalKeyPassword);
    }
    field(ArgumentParser.class, "verbose").set(null, originalVerbose);
    field(Redwood.class, "handlers").set(null, originalHandlers);
  }

  private static String dummyKeystore() {
    String resource = "edu/stanford/nlp/pipeline/corenlp.jks";
    // Ant puts src on the classpath; Maven does not copy this resource.
    return IOUtils.existsInClasspathOrFileSystem(resource) ? resource : "src/" + resource;
  }

  private String customKeystore() throws Exception {
    KeyStore original = KeyStore.getInstance("JKS");
    try (InputStream input = IOUtils.getInputStreamFromURLOrClasspathOrFileSystem(dummyKeystore())) {
      original.load(input, "corenlp".toCharArray());
    }
    KeyStore custom = KeyStore.getInstance("JKS");
    custom.load(null, CUSTOM_PASSWORD.toCharArray());
    Enumeration<String> aliases = original.aliases();
    while (aliases.hasMoreElements()) {
      String alias = aliases.nextElement();
      if (original.isKeyEntry(alias)) {
        custom.setKeyEntry(alias, original.getKey(alias, "corenlp".toCharArray()),
            CUSTOM_PASSWORD.toCharArray(), original.getCertificateChain(alias));
      }
    }
    assertTrue("The fixture must contain a private key", custom.size() > 0);
    File file = temporaryFolder.newFile("custom.jks");
    try (FileOutputStream output = new FileOutputStream(file)) {
      custom.store(output, CUSTOM_PASSWORD.toCharArray());
    }
    return file.getPath();
  }

  private static void configureSSL() throws Exception {
    HttpsServer https = HttpsServer.create();
    try {
      Method method = StanfordCoreNLPServer.class.getDeclaredMethod("addSSLContext", HttpsServer.class);
      method.setAccessible(true);
      assertSame(https, method.invoke(null, https));
      assertNull("The test must not bind a listening socket", https.getAddress());
      assertEquals("TLS", https.getHttpsConfigurator().getSSLContext().getProtocol());
      assertNotNull(https.getHttpsConfigurator().getSSLContext().createSSLEngine());
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    } finally {
      https.stop(0);
    }
  }

  @Test
  public void testDefaultPassword() throws Exception {
    StanfordCoreNLPServer.key = dummyKeystore();
    configureSSL();
  }

  @Test
  public void testCustomStoreAndPrivateKeyPassword() throws Exception {
    ArgumentParser.fillOptions(StanfordCoreNLPServer.class,
        "-key", customKeystore(), "-keyPassword", CUSTOM_PASSWORD);
    configureSSL();
  }

  @Test
  public void testMissingOrIncorrectPasswordRejected() throws Exception {
    StanfordCoreNLPServer.key = customKeystore();
    for (String password : new String[]{"corenlp", "incorrect-password"}) {
      ArgumentParser.fillOptions(StanfordCoreNLPServer.class, "-keyPassword", password);
      try {
        configureSSL();
        fail("An incorrect keystore password must be rejected");
      } catch (RuntimeException e) {
        assertTrue(e.getCause() instanceof java.io.IOException);
      }
    }
  }

  private void checkServerOptions(String optionName, String key, String keyPassword) throws Exception {
    String[] args = {"-key", key, "-" + optionName, keyPassword,
        "-username", "test-user", "-password", HTTP_PASSWORD,
        "-server_id", UUID.randomUUID().toString(), "-annotators", "tokenize"};
    Properties props = StringUtils.argsToProperties(args);
    field(ArgumentParser.class, "verbose").set(null, true);
    ArgumentParser.fillOptions(StanfordCoreNLPServer.class, args);
    StanfordCoreNLPServer server = new StanfordCoreNLPServer(props);
    try {
      ArgumentParser.fillOptions(server, args);
      assertEquals("test-user", server.username);
      assertEquals(HTTP_PASSWORD, server.password);
      Properties defaults = (Properties) field(StanfordCoreNLPServer.class, "defaultProps").get(server);
      assertEquals("tokenize", defaults.getProperty("annotators"));
      assertFalse("Keystore password must not become a pipeline property", defaults.containsKey(optionName));
      assertFalse(defaults.containsKey("password"));
      if (!"corenlp".equals(keyPassword)) {
        assertFalse("Keystore password must not be logged", logs.toString().contains(keyPassword));
      }
      assertFalse("HTTP password must not be logged", logs.toString().contains(HTTP_PASSWORD));
      assertTrue("Non-sensitive verbose options should still be logged", logs.toString().contains("test-user"));
      configureSSL();
    } finally {
      for (String executor : new String[]{"serverExecutor", "corenlpExecutor"}) {
        ((ExecutorService) field(StanfordCoreNLPServer.class, executor).get(server)).shutdownNow();
      }
      Files.deleteIfExists(new File(System.getProperty("java.io.tmpdir"),
          "corenlp.shutdown." + props.getProperty("server_id")).toPath());
    }
  }

  @Test
  public void testCustomPasswordIndependentOfHttpAuthAndNotLogged() throws Exception {
    checkServerOptions("keyPassword", customKeystore(), CUSTOM_PASSWORD);
  }

  @Test
  public void testCaseInsensitivePasswordOptionNotLogged() throws Exception {
    checkServerOptions("KEYPASSWORD", customKeystore(), CUSTOM_PASSWORD);
  }

  @Test
  public void testDefaultPasswordIndependentOfHttpAuth() throws Exception {
    checkServerOptions("keyPassword", dummyKeystore(), "corenlp");
  }
}
