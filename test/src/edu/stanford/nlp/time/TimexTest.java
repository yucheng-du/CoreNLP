package edu.stanford.nlp.time;

import org.junit.Test;
import org.w3c.dom.Element;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class TimexTest {
  private static void assertFields(Timex expected, Timex actual) {
    // Timex.equals does not compare the alternate value, text, or tid.
    assertEquals(expected.altVal(), actual.altVal());
    assertEquals(expected.value(), actual.value());
    assertEquals(expected.timexType(), actual.timexType());
    assertEquals(expected.tid(), actual.tid());
    assertEquals(expected.text(), actual.text());
    assertEquals(expected.beginPoint(), actual.beginPoint());
    assertEquals(expected.endPoint(), actual.endPoint());
  }

  private static void assertXmlRoundTrip(Timex original) {
    Element element = original.toXmlElement();
    assertEquals(original.altVal(), XMLUtils.getAttribute(element, "alt_value"));
    assertFalse(element.hasAttribute("altVal"));
    assertFields(original, new Timex(element));
    assertFields(original, Timex.fromXml(XMLUtils.nodeToString(element, false)));
  }

  @Test
  public void testDurationRangeAlternateValue() {
    SUTime.DurationRange range = new SUTime.DurationRange(SUTime.DAY.multiplyBy(2), SUTime.DAY.multiplyBy(3));
    Timex timex = Timex.fromMap("two to three days", range.getTimexAttributes(new SUTime.TimeIndex()));

    assertNull(timex.value());
    assertEquals("P2D/P3D", timex.altVal());
    assertXmlRoundTrip(timex);
  }

  @Test
  public void testAlternateValueWithOtherAttributes() {
    Timex timex = Timex.fromXml("<TIMEX3 tid=\"t3\" type=\"DURATION\" value=\"P2D\" " +
                               "alt_value=\"P2D/P3D\" beginPoint=\"t1\" endPoint=\"t2\">" +
                               "&lt;two&gt; &amp; &quot;three&quot; days</TIMEX3>");
    assertXmlRoundTrip(timex);
  }

  @Test
  public void testNoAlternateValue() {
    Timex timex = Timex.fromXml("<TIMEX3 tid=\"t1\" type=\"DATE\" value=\"2017-06-15\">" +
                               "June 15, 2017</TIMEX3>");
    assertNull(timex.altVal());
    assertXmlRoundTrip(timex);
  }

  @Test
  public void testEmptyAlternateValue() {
    Timex timex = Timex.fromXml("<TIMEX3 tid=\"t1\" type=\"DURATION\" alt_value=\"\">days</TIMEX3>");
    assertEquals("", timex.altVal());
    assertXmlRoundTrip(timex);
  }
}
