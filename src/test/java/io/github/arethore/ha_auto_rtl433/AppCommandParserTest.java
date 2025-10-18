package io.github.arethore.ha_auto_rtl433;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class AppCommandParserTest {

    private final App app = new App(null);

    @Test
    public void parsesSimpleArguments() {
        String[] expected = { "rtl_433", "-F", "json" };
        assertArrayEquals(expected, app.parseCommand("rtl_433 -F json").toArray(new String[0]));
    }

    @Test
    public void parsesQuotedArguments() {
        String[] expected = { "/usr/bin/rtl_433", "-F", "json", "--option", "value with spaces" };
        assertArrayEquals(expected, app.parseCommand("/usr/bin/rtl_433 -F json --option \"value with spaces\"")
                .toArray(new String[0]));
    }

    @Test
    public void parsesSingleQuotesAndEscapes() {
        String[] expected = { "echo", "it", "has spaces", "and\"quotes" };
        assertArrayEquals(expected, app.parseCommand("echo it 'has spaces' and\\\"quotes").toArray(new String[0]));
    }

    @Test
    public void rejectsUnfinishedQuotes() {
        try {
            app.parseCommand("rtl_433 \"unterminated");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException ignored) {
            // expected
        }
    }
}
