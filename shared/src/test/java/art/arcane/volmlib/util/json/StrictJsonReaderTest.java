package art.arcane.volmlib.util.json;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class StrictJsonReaderTest {
    @Test
    public void acceptsStandardLiteralsNumbersAndEscapes() throws IOException {
        JsonElement value = parse("{\"v\":[true,false,null,1E+2,-2.5e-3,\"A\\n\\t\\u0041\\/\\\\\\\"\"]}");
        assertEquals(100D, value.getAsJsonObject().getAsJsonArray("v").get(3).getAsDouble(), 0D);
        assertEquals("A\n\tA/\\\"", value.getAsJsonObject().getAsJsonArray("v").get(5).getAsString());
    }

    @Test
    public void rejectsLegacyLeniencyAcrossReaderBuffers() {
        for (String invalid : List.of("TRUE", "truE", "False", "NuLl", "\"raw\nline\"", "\"raw\tcolumn\"",
                "\"\\'\"", "\"\\\n\"", "\"\\u00xz\"", "/*comment*/1", "'single'", "{unquoted:1}")) {
            assertThrows(invalid, IOException.class, () -> parse(" ".repeat(1023) + invalid));
        }
    }

    @Test
    public void validatesSingleCharacterReadsAndStringBoundaries() throws IOException {
        try (StrictJsonReader reader = new StrictJsonReader(new StringReader("[\"TRUE\",truE]"))) {
            assertThrows(IOException.class, () -> {
                while (reader.read() >= 0) {
                }
            });
        }
    }

    private static JsonElement parse(String input) throws IOException {
        try (JsonReader reader = new JsonReader(new StrictJsonReader(new StringReader(input)))) {
            reader.setLenient(false);
            JsonElement value = new Gson().getAdapter(JsonElement.class).read(reader);
            assertEquals(JsonToken.END_DOCUMENT, reader.peek());
            return value;
        }
    }
}
