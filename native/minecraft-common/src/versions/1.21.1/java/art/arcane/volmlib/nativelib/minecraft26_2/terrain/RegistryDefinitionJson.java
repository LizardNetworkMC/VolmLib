package art.arcane.volmlib.nativelib.minecraft26_2.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import art.arcane.volmlib.util.json.StrictJsonReader;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class RegistryDefinitionJson {
    private RegistryDefinitionJson() {
    }

    static JsonObject read(String json) throws IOException {
        Limits limits = new Limits(8 * 1024 * 1024, 64, 65_536, 65_536);
        if (json.length() > limits.inflatedBytes() || json.getBytes(StandardCharsets.UTF_8).length > limits.inflatedBytes()) {
            throw new IOException("Registry definition exceed the JSON byte limit");
        }
        try (JsonReader reader = new JsonReader(new StrictJsonReader(new StringReader(json)))) {
            reader.setLenient(false);
            JsonElement result = value(reader, 0, limits);
            if (!result.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Registry definition require exactly one JSON object");
            }
            return result.getAsJsonObject();
        } catch (IllegalStateException | NumberFormatException failure) {
            throw new IOException("Registry definition JSON is invalid", failure);
        }
    }

    static CompoundTag toNbt(JsonObject json) throws IOException {
        return (CompoundTag) toTag(json);
    }

    static String encode(Tag settings) throws IOException {
        if (!(settings instanceof CompoundTag)) {
            throw new IOException("Native registry codec returned non-compound settings");
        }
        return toJson(settings, "").toString();
    }

    private static Tag toTag(JsonElement json) throws IOException {
        if (json.isJsonObject()) {
            CompoundTag compound = new CompoundTag();
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                compound.put(entry.getKey(), toTag(entry.getValue()));
            }
            return compound;
        }
        if (json.isJsonArray()) {
            ListTag list = new ListTag();
            byte type = Tag.TAG_END;
            for (JsonElement element : json.getAsJsonArray()) {
                Tag value = toTag(element);
                if (type != Tag.TAG_END && type != value.getId()) {
                    throw new IOException("Registry definition require homogeneous lists");
                }
                type = value.getId();
                list.add(value);
            }
            return list;
        }
        if (!json.isJsonPrimitive()) {
            throw new IOException("Registry definition cannot contain JSON null");
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isString()) {
            return StringTag.valueOf(primitive.getAsString());
        }
        if (primitive.isBoolean()) {
            return ByteTag.valueOf(primitive.getAsBoolean());
        }
        try {
            BigDecimal number = primitive.getAsBigDecimal();
            if (number.stripTrailingZeros().scale() <= 0) {
                long integer = number.longValueExact();
                return integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE
                        ? IntTag.valueOf((int) integer) : LongTag.valueOf(integer);
            }
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal)) {
                throw new IOException("Registry definition number is not finite");
            }
            return DoubleTag.valueOf(decimal);
        } catch (ArithmeticException failure) {
            throw new IOException("Registry definition integer exceeds the native range", failure);
        }
    }

    private static JsonElement toJson(Tag tag, String field) throws IOException {
        if (tag instanceof CompoundTag compound) {
            JsonObject json = new JsonObject();
            for (String name : compound.getAllKeys()) {
                json.add(name, toJson(compound.get(name), name));
            }
            return json;
        }
        if (tag instanceof ListTag list) {
            JsonArray json = new JsonArray();
            byte type = Tag.TAG_END;
            for (Tag element : list) {
                if (type != Tag.TAG_END && type != element.getId()) {
                    throw new IOException("Native registry codec returned a heterogeneous list");
                }
                type = element.getId();
                json.add(toJson(element, ""));
            }
            return json;
        }
        if (tag instanceof StringTag) {
            return new JsonPrimitive(tag.getAsString());
        }
        if (tag instanceof ByteTag byteTag) {
            byte value = byteTag.getAsByte();
            if (value != 0 && value != 1) {
                throw new IOException("Native registry codec returned a non-boolean byte");
            }
            return new JsonPrimitive(value == 1);
        }
        if (tag instanceof NumericTag numeric) {
            Number number = numeric.getAsNumber();
            if (!Double.isFinite(number.doubleValue())) {
                throw new IOException("Native registry codec returned a non-finite number");
            }
            return new JsonPrimitive(number);
        }
        JsonArray array = new JsonArray();
        if (tag instanceof ByteArrayTag bytes) {
            for (byte value : bytes.getAsByteArray()) { array.add(value); }
            return array;
        }
        if (tag instanceof IntArrayTag integers) {
            for (int value : integers.getAsIntArray()) { array.add(value); }
            return array;
        }
        if (tag instanceof LongArrayTag longs) {
            for (long value : longs.getAsLongArray()) { array.add(value); }
            return array;
        }
        throw new IOException("Native registry codec returned an unsupported tag for " + field);
    }

    private static JsonElement value(JsonReader reader, int depth, Limits limits) throws IOException {
        if (depth > limits.depth()) {
            throw new IOException("Registry definition exceed the JSON depth limit");
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> object(reader, depth, limits);
            case BEGIN_ARRAY -> array(reader, depth, limits);
            case STRING -> new JsonPrimitive(string(reader.nextString(), limits));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NUMBER -> number(reader.nextString());
            default -> throw new IOException("Registry definition have an unsupported JSON token");
        };
    }

    private static JsonObject object(JsonReader reader, int depth, Limits limits) throws IOException {
        reader.beginObject();
        JsonObject object = new JsonObject();
        int count = 0;
        while (reader.hasNext()) {
            count(++count, limits);
            String name = string(reader.nextName(), limits);
            if (object.has(name)) {
                throw new IOException("Registry definition have a duplicate JSON property");
            }
            object.add(name, value(reader, depth + 1, limits));
        }
        reader.endObject();
        return object;
    }

    private static JsonArray array(JsonReader reader, int depth, Limits limits) throws IOException {
        reader.beginArray();
        JsonArray array = new JsonArray();
        int count = 0;
        while (reader.hasNext()) {
            count(++count, limits);
            array.add(value(reader, depth + 1, limits));
        }
        reader.endArray();
        return array;
    }

    private static JsonPrimitive number(String value) throws IOException {
        if (value.length() > 32 || !Double.isFinite(Double.parseDouble(value))) {
            throw new IOException("Registry definition have an unbounded JSON number");
        }
        return new JsonPrimitive(new BigDecimal(value));
    }

    private static void count(int count, Limits limits) throws IOException {
        if (count > limits.collectionElements()) {
            throw new IOException("Registry definition exceed the JSON collection limit");
        }
    }

    private static String string(String value, Limits limits) throws IOException {
        if (value.length() > limits.stringBytes() || value.getBytes(StandardCharsets.UTF_8).length > limits.stringBytes()) {
            throw new IOException("Registry definition exceed the JSON string limit");
        }
        return value;
    }
    private record Limits(long inflatedBytes, int depth, int collectionElements, int stringBytes) { }
}
