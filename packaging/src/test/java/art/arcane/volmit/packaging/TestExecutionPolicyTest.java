package art.arcane.volmit.packaging;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestExecutionPolicyTest {
    @TempDir
    Path directory;

    @org.junit.jupiter.api.Test
    void boundsExistingAndLaterTestTasksAcrossProjects() {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        Project child = ProjectBuilder.builder().withName("child").withParent(project).build();
        Test existing = project.getTasks().register("existingTest", Test.class).get();

        project.getPlugins().apply(PluginPackagingPlugin.class);

        Test later = project.getTasks().register("laterTest", Test.class).get();
        Test nested = child.getTasks().register("nestedTest", Test.class).get();
        assertEquals(Duration.ofMinutes(5), existing.getTimeout().get());
        assertEquals(Duration.ofMinutes(5), later.getTimeout().get());
        assertEquals(Duration.ofMinutes(5), nested.getTimeout().get());
    }

    @org.junit.jupiter.api.Test
    void preservesExplicitTestTimeouts() {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        Test existing = project.getTasks().register("existingTest", Test.class).get();
        existing.getTimeout().set(Duration.ofSeconds(45));

        project.getPlugins().apply(PluginPackagingPlugin.class);

        Test later = project.getTasks().register("laterTest", Test.class).get();
        later.getTimeout().set(Duration.ofMinutes(8));
        assertEquals(Duration.ofSeconds(45), existing.getTimeout().get());
        assertEquals(Duration.ofMinutes(8), later.getTimeout().get());
    }
}
