package com.vm.nornir.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Static contract checks on the merged source manifest for the Nornir home
 * launcher (issue #12). Runs on the JVM (no Android runtime).
 *
 * Complements {@link HomeLauncherContractTest}: Robolectric cannot discriminate
 * the <queries> declaration at runtime (only the test package is installed), so
 * the declaration is asserted directly against the source manifest, which is
 * absent on the RED manifest and present on the GREEN one.
 */
@RunWith(JUnit4.class)
public final class HomeLauncherManifestTest {

  private String manifest() throws IOException {
    // Gradle unit tests run with the module directory as the working directory.
    Path path = Paths.get("src/main/AndroidManifest.xml");
    assertTrue("Expected launcher manifest at " + path.toAbsolutePath(), Files.exists(path));
    return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Returns just MainActivity's <activity ...> ... </activity> block from the manifest. */
  private String mainActivityBlock() throws IOException {
    String xml = manifest();
    Matcher m =
        Pattern.compile(
                "<activity[\\s>].*?</activity>", Pattern.DOTALL)
            .matcher(xml);
    assertTrue("Manifest must declare a MainActivity <activity> block", m.find());
    return m.group();
  }

  /**
   * MainActivity MUST be exported and host the MAIN/HOME/DEFAULT filter, and MUST NOT carry
   * the LAUNCHER category (no drawer entry). The check is scoped to the MainActivity element:
   * a separate <queries> block declaring MAIN/LAUNCHER for package visibility is allowed and
   * is asserted by {@link #manifest_declaresMainLauncherQueries()}.
   */
  @Test
  public void manifest_registersHomeActivity() throws IOException {
    String activity = mainActivityBlock();
    assertTrue("MainActivity must be exported", activity.contains("android:exported=\"true\""));
    assertTrue("MainActivity must carry the MAIN action", activity.contains("android.intent.action.MAIN"));
    assertTrue("MainActivity must carry the HOME category", activity.contains("android.intent.category.HOME"));
    assertFalse(
        "MainActivity must NOT carry the LAUNCHER category (no drawer entry)",
        activity.contains("android.intent.category.LAUNCHER"));
  }

  /**
   * The MAIN/LAUNCHER <queries> block MUST be declared so Nornir can resolve the
   * real launcher activity without QUERY_ALL_PACKAGES.
   */
  @Test
  public void manifest_declaresMainLauncherQueries() throws IOException {
    String xml = manifest();
    Pattern queries =
        Pattern.compile(
            "<queries>.*?</queries>", Pattern.DOTALL);
    Matcher m = queries.matcher(xml);
    assertTrue("Manifest must declare a <queries> block", m.find());
    String block = m.group();
    assertTrue(
        "queries block must include the MAIN action",
        block.contains("android.intent.action.MAIN"));
    assertTrue(
        "queries block must include the LAUNCHER category",
        block.contains("android.intent.category.LAUNCHER"));
  }

  /** Sanity: single root <manifest> element. */
  @Test
  public void manifest_hasSingleRoot() throws IOException {
    String xml = manifest();
    assertEquals("Expected exactly one <manifest> root", 1, count(xml, "<manifest"));
  }

  private static int count(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) != -1) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
