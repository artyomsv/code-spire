package dev.codespire.context.jira;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JiraConfigTest {
    @Test void unsupportedAuthenticationCannotFallBackToBasic() {
        assertThrows(IllegalArgumentException.class,()->new JiraConfig("https://TEST-jira.invalid","TEST-unknown","TEST-person","TEST-token",Set.of("TEST")));
    }
}
