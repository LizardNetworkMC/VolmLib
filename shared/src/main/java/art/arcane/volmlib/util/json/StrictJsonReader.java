package art.arcane.volmlib.util.json;

import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

public final class StrictJsonReader extends Reader {
    private final Reader input;
    private boolean string;
    private boolean escape;
    private boolean number;
    private int unicodeDigits;
    private long position;

    public StrictJsonReader(Reader input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    @Override
    public int read() throws IOException {
        int value = input.read();
        if (value >= 0) {
            validate((char) value);
        }
        return value;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        int count = input.read(buffer, offset, length);
        for (int index = offset; index < offset + count; index++) {
            validate(buffer[index]);
        }
        return count;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private void validate(char value) throws IOException {
        position++;
        if (string) {
            if (unicodeDigits > 0) {
                if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                        || (value >= 'A' && value <= 'F'))) {
                    throw invalid("Unicode escape");
                }
                unicodeDigits--;
                return;
            }
            if (escape) {
                escape = false;
                if (value == 'u') {
                    unicodeDigits = 4;
                } else if ("\"\\/bfnrt".indexOf(value) < 0) {
                    throw invalid("string escape");
                }
                return;
            }
            if (value < 0x20) {
                throw invalid("unescaped control character");
            }
            if (value == '\\') {
                escape = true;
            } else if (value == '"') {
                string = false;
            }
            return;
        }
        if (value == '"') {
            string = true;
            number = false;
        } else if (value >= 'A' && value <= 'Z' && !(number && value == 'E')) {
            throw invalid("uppercase literal");
        } else if (" \t\n\r,]}:{[".indexOf(value) >= 0) {
            number = false;
        } else if (value == '-' || value >= '0' && value <= '9') {
            number = true;
        }
    }

    private IOException invalid(String reason) {
        return new IOException("Invalid JSON " + reason + " at character " + position);
    }
}
