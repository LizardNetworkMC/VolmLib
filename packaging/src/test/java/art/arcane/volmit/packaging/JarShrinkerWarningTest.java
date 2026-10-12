package art.arcane.volmit.packaging;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class JarShrinkerWarningTest {
    private static final String CHILD = "org.example.privatechild.Entrypoint";
    private static final String PARENT = "org.example.host.SharedContract";

    @Test
    void bothDependencyFormsRetainExactLibraryOwnerAndProgramType() throws Exception {
        for (String relation : List.of("depends on", "extends or implements")) {
            assertEquals(List.of(CHILD, PARENT), names("library class " + CHILD + " " + relation + " program class " + PARENT));
            assertEquals(List.of(CHILD + "$Nested", PARENT + "$Options"),
                    names("library class " + CHILD + "$Nested " + relation + " program class " + PARENT + "$Options"));
        }
    }

    @Test
    void exactPrivateChildExceptionHandlesBothRelationsWithoutPermittingOtherOwners() throws Exception {
        for (String relation : List.of("depends on", "extends or implements")) {
            String body = "library class " + CHILD + " " + relation + " program class " + PARENT;
            assertEquals(CHILD, tolerance(body, List.of(CHILD)));
            assertNull(tolerance(body.replace(CHILD, CHILD + "Other"), List.of(CHILD)));
            assertNull(tolerance(body.replace(CHILD, "org.example.other.Entrypoint"), List.of(CHILD)));
        }
    }

    @Test
    void unrecognizedInheritanceWordingDoesNotAcquirePrivateChildException() throws Exception {
        assertNull(tolerance("library class " + CHILD + " inherits program class " + PARENT, List.of(CHILD)));
        assertNull(tolerance("unknown library warning for " + CHILD, List.of(CHILD)));
    }

    @Test
    void exactProgramSideAndExistingMemberWarningsRetainTheirClassification() throws Exception {
        assertEquals(PARENT, tolerance("library class " + CHILD + " extends or implements program class " + PARENT, List.of(PARENT)));
        assertEquals(CHILD, tolerance(CHILD + ": can't find referenced class " + PARENT, List.of(CHILD)));
        assertNull(tolerance(CHILD + ": can't find referenced class " + PARENT, List.of("org.example.unrelated.Type")));
    }

    private static Object names(String body) throws Exception {
        Method method = JarShrinker.class.getDeclaredMethod("classNames", String.class);
        method.setAccessible(true);
        return method.invoke(null, body);
    }

    private static Object tolerance(String body, List<String> exceptions) throws Exception {
        Method method = JarShrinker.class.getDeclaredMethod("classify", List.class, List.class);
        method.setAccessible(true);
        List<?> warnings = (List<?>) method.invoke(null, List.of("Warning: " + body), exceptions);
        assertEquals(1, warnings.size());
        Object warning = warnings.get(0);
        Method pattern = warning.getClass().getDeclaredMethod("pattern");
        pattern.setAccessible(true);
        return pattern.invoke(warning);
    }
}
