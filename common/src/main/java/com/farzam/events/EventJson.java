package com.farzam.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;

/**
 * The one way this system turns an event into bytes, and back.
 *
 * <p><b>Why "canonical" and not just "JSON".</b> From M3 on, approvers sign a payload and the signer
 * verifies that signature before it will touch a private key. A signature is over bytes, so both
 * sides have to produce <em>the same</em> bytes from the same facts — and ordinary JSON does not
 * promise that. Reordered keys, a space after a colon, or a different date format all change the
 * bytes without changing the meaning, and each one turns a valid approval into a forgery as far as
 * the verifier is concerned. So the mapper here is configured to have exactly one output per input:
 * keys sorted, no whitespace, dates as ISO-8601 text.
 *
 * <p><b>Why the bytes are recomputed rather than carried.</b> The obvious alternative is to keep the
 * exact bytes that were signed and pass them along. That cannot work here: the outbox stores the
 * payload in a {@code jsonb} column, and Postgres does not keep {@code jsonb} verbatim — it parses
 * it, drops whitespace, discards duplicate keys and stores the keys in its own order. Whatever goes
 * in does not come out byte-for-byte. Every party therefore parses into the shared payload record
 * from {@code common} and re-serialises it here, which makes verification independent of anything
 * the transport did on the way. Storing the payload as {@code text} would preserve the bytes, but it
 * would also give up indexing and querying the payload in SQL, and it would make correctness depend
 * on a column type that anyone could migrate without knowing what they had broken.
 *
 * <p><b>Null fields are written, not omitted.</b> The key set of a payload is then fixed by the
 * record's shape rather than by the data in it, so two payloads that differ only in an absent field
 * cannot serialise to the same bytes.
 *
 * <p><b>Unknown fields are ignored when reading.</b> That is the other half of the schema-evolution
 * rule in the spec: a producer may add an optional field at any time, and a consumer built before it
 * existed has to keep working. Adding is safe; renaming and repurposing are not, and no mapper
 * setting can make them safe.
 */
public final class EventJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // Instant as "2026-09-24T18:00:00Z" rather than an epoch decimal. Text, because a
            // decimal number of seconds is a float, and floats are banned here for the reason the
            // ledger is in wei.
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // The two halves of "keys are sorted". The first orders POJO and record properties;
            // the second orders the entries of a Map, which the first does not touch.
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            // And the half that is easy to miss. Every component of a record is a constructor
            // ("creator") property, and Jackson writes creator properties first in declaration
            // order by default — which silently defeats the alphabetical sort above for exactly the
            // types this class exists to serialise. There is a test for this.
            .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventJson() {}

    /**
     * Serialises a value to its one canonical form.
     *
     * @param value an envelope or a payload record
     * @return compact JSON with sorted keys
     * @throws EventFormatException if the value cannot be serialised
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new EventFormatException("cannot serialise " + value.getClass().getName(), failure);
        }
    }

    /**
     * The bytes that get signed and verified.
     *
     * @param value an envelope or a payload record
     * @return {@link #write} in UTF-8
     */
    public static byte[] canonicalBytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses JSON text into the type it should be.
     *
     * @param json the text, typically a Kafka record's value
     * @param type what it should parse as
     * @param <T> that type
     * @return the parsed value
     * @throws EventFormatException if the text is not valid JSON, or not this shape
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException failure) {
            // The parser's message quotes the offending input, and the input here is an event
            // payload that may carry a destination address or an amount. The type is enough to find
            // the message on the dead-letter topic, and the cause has the rest for whoever opens it.
            throw new EventFormatException("cannot read " + type.getSimpleName() + " from the message", failure);
        }
    }

    /**
     * Turns a value into a tree, for embedding in an envelope.
     *
     * @param value a payload record
     * @return the same value as a {@link JsonNode}
     */
    public static JsonNode toNode(Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * Reads an envelope's payload as the record it is supposed to be.
     *
     * @param node the envelope's payload
     * @param type the payload record that goes with the event type
     * @param <T> that type
     * @return the parsed payload
     * @throws EventFormatException if the payload is not that shape
     */
    public static <T> T fromNode(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException failure) {
            throw new EventFormatException("the payload is not a " + type.getSimpleName(), failure);
        }
    }
}
