package art.arcane.volmlib.util.director.runtime;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DirectorRouteTest {
    private Commands commands;
    private DirectorRuntimeEngine engine;
    private Sender sender;

    @Before
    public void setUp() {
        commands = new Commands();
        engine = DirectorEngineFactory.create(commands);
        sender = new Sender();
    }

    @Test
    public void exactPrefixTypoAndAliasFormsRouteToTheNodeExecutionRuns() {
        assertRoute(List.of("user", "grant"), "user", "grant", "Steve", "fly.use");
        assertRoute(List.of("user", "grant"), "use", "grant", "Steve", "fly.use");
        assertRoute(List.of("user", "grant"), "usr", "gran", "Steve", "fly.use");
        assertRoute(List.of("user", "grant"), "u", "give", "Steve", "fly.use");
        assertRoute(List.of("user", "info"), "USER", "Info", "Steve");
        assertRoute(List.of("group", "command", "add"), "group", "comm", "add", "vip", "added");
        assertRoute(List.of("status"), "stat");

        assertEquals(List.of("Steve", "fly.use"), route("use", "grant", "Steve", "fly.use").remainingArgs());
        assertTrue(engine.execute(new DirectorInvocation(sender, "test", List.of("use", "grant", "Steve", "fly.use"))).isSuccess());
        assertEquals(1, commands.user.grants);
    }

    @Test
    public void unresolvedInputStopsAtTheDeepestMatchedNode() {
        DirectorRoute group = route("user", "zzzzzzzz");
        assertEquals(List.of("user"), group.path());
        assertFalse(group.invocable());
        assertEquals(List.of("zzzzzzzz"), group.remainingArgs());

        DirectorRoute root = route("zzzzzzzz");
        assertEquals(List.of(), root.path());
        assertFalse(root.invocable());

        DirectorRoute empty = route();
        assertEquals(List.of(), empty.path());
        assertFalse(empty.invocable());
    }

    @Test
    public void routingTokenizesQuotedArgumentsLikeExecution() {
        DirectorRoute quoted = route("\"user\"", "grant", "\"Steve", "Smith\"");
        assertEquals(List.of("user", "grant"), quoted.path());
        assertEquals(List.of("Steve Smith"), quoted.remainingArgs());

        DirectorRoute pretokenized = engine.route(DirectorInvocation.pretokenized(sender, "test", List.of("user", "grant", "\"Steve")));
        assertEquals(List.of("\"Steve"), pretokenized.remainingArgs());
    }

    private void assertRoute(List<String> expected, String... args) {
        DirectorRoute route = route(args);
        assertEquals(String.join(" ", args), expected, route.path());
        assertTrue(route.invocable());
    }

    private DirectorRoute route(String... args) {
        return engine.route(new DirectorInvocation(sender, "test", List.of(args)));
    }

    @Director(name = "test")
    public static class Commands {
        UserCommands user = new UserCommands();
        GroupCommands group = new GroupCommands();

        @Director(name = "status")
        public void status() {
        }
    }

    @Director(name = "user", aliases = {"u"})
    public static class UserCommands {
        private int grants;

        @Director(name = "info")
        public void info(@Param(name = "player") String player) {
        }

        @Director(name = "grant", aliases = {"give"})
        public void grant(@Param(name = "player") String player, @Param(name = "node") String node) {
            grants++;
        }
    }

    @Director(name = "group")
    public static class GroupCommands {
        CommandCommands command = new CommandCommands();

        @Director(name = "info")
        public void info(@Param(name = "group") String group) {
        }
    }

    @Director(name = "command")
    public static class CommandCommands {
        @Director(name = "add")
        public void add(@Param(name = "group") String group, @Param(name = "when") String when) {
        }

        @Director(name = "list")
        public void list(@Param(name = "group") String group) {
        }
    }

    private static final class Sender implements DirectorSender {
        private final List<String> messages = new ArrayList<>();

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isPlayer() {
            return false;
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }
    }
}
