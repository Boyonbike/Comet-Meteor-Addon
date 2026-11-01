/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.tester;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildInfoTest {
    @Test
    void missingValuesFallBackSafely() {
        BuildInfo info = new BuildInfo(null, null, null);
        assertEquals("dev", info.version());
        assertEquals("unknown", info.commit());
        assertEquals("", info.testerSuffix());
        assertFalse(info.tester());
        assertEquals("release", info.testerLabel());
        assertEquals("vdev unknown", info.display());
        assertEquals(BuildInfo.UNKNOWN, info);
    }

    @Test
    void testerBuildsExposeTheirSuffixAndMarker() {
        BuildInfo info = new BuildInfo("0.27.0-tester.2", "fb59152-dirty", "tester.2");
        assertTrue(info.tester());
        assertEquals("tester.2", info.testerLabel());
        assertEquals("v0.27.0-tester.2 fb59152-dirty", info.display());
    }

    @Test
    void propertiesParsingTreatsUnexpandedPlaceholdersAsAbsent() {
        Properties properties = new Properties();
        properties.setProperty("version", "0.27.0-tester.1");
        properties.setProperty("commit", "fb59152");
        properties.setProperty("tester", "tester.1");
        BuildInfo info = BuildInfo.parse(properties);
        assertEquals("0.27.0-tester.1", info.version());
        assertEquals("fb59152", info.commit());
        assertEquals("tester.1", info.testerLabel());

        Properties unexpanded = new Properties();
        unexpanded.setProperty("version", "${build_version}");
        unexpanded.setProperty("commit", "${git_commit}");
        unexpanded.setProperty("tester", "${tester_suffix}");
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.parse(unexpanded));

        assertThrows(NullPointerException.class, () -> BuildInfo.parse(null));
    }

    @Test
    void embeddedBuildStampIsExpandedByTheBuild() throws Exception {
        BuildInfo info = BuildInfo.load();
        assertNotNull(info);
        // processResources must not leave raw Gradle placeholders in the tested classpath.
        assertFalse(info.version().startsWith("${"));
        assertFalse(info.commit().startsWith("${"));
        assertFalse(info.version().isBlank());

        try (InputStream in = BuildInfo.class.getResourceAsStream("/comet-build.properties")) {
            assertNotNull(in, "comet-build.properties must ship in the jar");
            Properties properties = new Properties();
            properties.load(in);
            assertEquals(info.version(), properties.getProperty("version").trim());
        }
    }
}
