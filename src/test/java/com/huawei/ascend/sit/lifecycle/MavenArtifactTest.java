package com.huawei.ascend.sit.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MavenArtifact} — local-repository path resolution and
 * coordinate parsing. Pure logic; no process or filesystem access required.
 */
class MavenArtifactTest {

    @Test
    void parsesGroupArtifactVersion() {
        MavenArtifact a = MavenArtifact.parse("com.huawei.ascend:agent-travel-mainplan-a2a:0.1.0-SNAPSHOT");

        assertThat(a.groupId()).isEqualTo("com.huawei.ascend");
        assertThat(a.artifactId()).isEqualTo("agent-travel-mainplan-a2a");
        assertThat(a.version()).isEqualTo("0.1.0-SNAPSHOT");
    }

    @Test
    void rejectsMalformedCoordinates() {
        assertThatThrownBy(() -> MavenArtifact.parse("only:two"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MavenArtifact.parse("a:b:c:d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jarPathFollowsMavenLayout() {
        MavenArtifact a = new MavenArtifact("com.huawei.ascend", "agent-travel-mainplan-a2a", "0.1.0-SNAPSHOT");

        assertThat(a.jarPath("/home/u/.m2/repository").toString())
                .isEqualTo("/home/u/.m2/repository/com/huawei/ascend/"
                        + "agent-travel-mainplan-a2a/0.1.0-SNAPSHOT/"
                        + "agent-travel-mainplan-a2a-0.1.0-SNAPSHOT.jar");
    }

    @Test
    void jarPathConvertsDottedGroupToSlashedPath() {
        MavenArtifact a = MavenArtifact.parse("io.github.x:my-art:1.2.3");

        assertThat(a.jarPath("~/.m2/repository").toString())
                .isEqualTo("~/.m2/repository/io/github/x/my-art/1.2.3/my-art-1.2.3.jar");
    }
}
