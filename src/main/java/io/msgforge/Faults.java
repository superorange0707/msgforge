package io.msgforge;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Makes a message invalid on purpose, to see what the system under test does with one.
 *
 *   empty           no body at all
 *   garbage         text that is not the format in any way
 *   truncated       the message cut off somewhere inside
 *   field-removed   one field, element or attribute taken out (JSON and XML)
 *   type-changed    one value of the wrong type: a number where text was, text where a number was (JSON and XML)
 *
 * Every choice comes from the random source handed in, so a run with a seed makes the same faults again.
 */
final class Faults {

    static final List<String> KINDS = List.of("empty", "garbage", "truncated", "field-removed", "type-changed");
    private static final Pattern TYPED = Pattern.compile("-?\\d+(\\.\\d+)?|true|false|\\d{4}-\\d{2}-\\d{2}.*");

    /**
     * percent of the messages get a fault, each one of the kinds, picked at random (none listed means all five).
     * expect is where the invalid messages should turn up, a reject queue say; without it a run that verifies
     * expects them nowhere.
     */
    public record Spec(double percent, List<String> kinds, Runs.Target expect) {}

    // Number text is kept as written (10.50 stays 10.50), so only the faulted field differs from what was rendered.
    private static final ObjectMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN).build();

    private Faults() {
    }

    /** The kinds a spec asks for, checked. */
    static List<String> kinds(Spec spec) {
        if (!(spec.percent() >= 0 && spec.percent() <= 100)) throw new IllegalArgumentException("Invalid messages must be 0 to 100 percent");
        if (spec.kinds() == null || spec.kinds().isEmpty()) return KINDS;
        for (String kind : spec.kinds()) {
            if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unknown kind of invalid message: " + kind + ". The kinds are " + String.join(", ", KINDS));
        }
        return spec.kinds().stream().distinct().toList();
    }

    /** Why this kind cannot be applied to a body like this one, or null when it can. Asked before a run starts. */
    static String refusal(String kind, String template, String body, String token) {
        if (apply(kind, body, new java.util.SplittableRandom(1), token) != null) return null;
        boolean structured = looksLikeJson(body) || looksLikeXml(body);
        if (token != null && apply(kind, body, new java.util.SplittableRandom(1), null) != null) {
            return template + " has no field besides the one its messages are recognised by (" + token + "), and \"" + kind
                    + "\" leaves that one alone: without it an invalid message that was accepted could not be told from one that went missing. Leave that kind out, or give the template another field.";
        }
        return kind.equals("truncated") ? template + " renders to nothing, so there is nothing to cut off. Leave \"truncated\" out."
                : !structured ? template + " is neither JSON nor XML, so \"" + kind + "\" cannot find a field in it. Leave that kind out, or use it with a JSON or XML template."
                : template + " does not parse as the " + (looksLikeJson(body) ? "JSON" : "XML") + " it looks like, or has no field to change, so \"" + kind + "\" cannot be applied. Leave that kind out.";
    }

    /**
     * The body with the fault in it, or null when this kind cannot be applied to this text.
     * token is what the message is recognised by when it arrives, or null when nobody waits for it. A field is not
     * removed and a value not changed when it holds the token: the system under test may accept the message all the
     * same, and it then has to be recognised where it arrives to be reported as accepted, not as missing. (An
     * emptied, garbled or cut-off message loses the token whatever is done; those are recognised by a header or by
     * their correlation id.)
     */
    static String apply(String kind, String body, RandomGenerator random, String token) {
        return switch (kind) {
            case "empty" -> "";
            case "garbage" -> "@@ not a message @@ " + Long.toString(random.nextLong() >>> 1, 36);
            case "truncated" -> {
                // Cut inside what the message says, not in the blank space after it: a message that only lost its last
                // line break is as valid as it was, and a system that accepts it has done nothing wrong.
                int end = body.stripTrailing().length();
                if (end < 2) yield body.isEmpty() ? null : "";
                int cut = 1 + random.nextInt(end - 1);
                if (Character.isHighSurrogate(body.charAt(cut - 1))) cut--;   // never half a character
                yield body.substring(0, cut);
            }
            case "field-removed", "type-changed" -> {
                try {
                    boolean remove = kind.equals("field-removed");
                    yield looksLikeJson(body) ? json(body, remove, random, token) : looksLikeXml(body) ? xml(body, remove, random, token) : null;
                } catch (Exception | StackOverflowError notTheFormat) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private static boolean looksLikeJson(String body) {
        String s = body.stripLeading();
        return s.startsWith("{") || s.startsWith("[");
    }

    private static boolean looksLikeXml(String body) {
        return body.stripLeading().startsWith("<");
    }

    // ---- JSON

    /** A place a value sits: a field of an object or an element of an array. */
    private record Slot(JsonNode parent, String field, int index) {}

    private static void slots(JsonNode node, List<Slot> out) {
        if (node instanceof ObjectNode object) {
            object.fieldNames().forEachRemaining(name -> {
                out.add(new Slot(object, name, -1));
                slots(object.get(name), out);
            });
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                out.add(new Slot(array, null, i));
                slots(array.get(i), out);
            }
        }
    }

    private static String json(String body, boolean remove, RandomGenerator random, String token) throws Exception {
        JsonNode root = JSON.readTree(body);
        List<Slot> all = new ArrayList<>();
        slots(root, all);
        if (token != null) all.removeIf(slot -> value(slot).toString().contains(token));   // the value, or anything inside it
        // A field is taken out of an object; a value changes type wherever it is, and one that holds a plain value comes first.
        List<Slot> fields = all.stream().filter(s -> s.field() != null).toList();
        List<Slot> plain = all.stream().filter(s -> value(s).isValueNode() && !value(s).isNull()).toList();
        List<Slot> from = remove ? fields : plain.isEmpty() ? all : plain;
        if (from.isEmpty()) return null;
        Slot slot = from.get(random.nextInt(from.size()));
        if (remove) {
            ((ObjectNode) slot.parent()).remove(slot.field());
        } else {
            JsonNode was = value(slot);
            JsonNode now = was.isTextual() ? JSON.getNodeFactory().numberNode(random.nextInt(90_000) + 10_000) : JSON.getNodeFactory().textNode("not-a-" + (was.isNumber() ? "number" : was.isBoolean() ? "boolean" : "value"));
            if (slot.field() != null) ((ObjectNode) slot.parent()).set(slot.field(), now);
            else ((ArrayNode) slot.parent()).set(slot.index(), now);
        }
        return body.contains("\n") ? JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) : JSON.writeValueAsString(root);
    }

    private static JsonNode value(Slot slot) {
        return slot.field() != null ? slot.parent().get(slot.field()) : slot.parent().get(slot.index());
    }

    // ---- XML

    // ponytail: the XML is parsed and written again, so its layout (attribute quotes, empty-element form) may differ from
    // the template's in the faulted messages. A DOCTYPE is refused, which is also what keeps entity tricks out.
    private static synchronized String xml(String body, boolean remove, RandomGenerator random, String token) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);   // a body that does not parse is an answer here, not something to print
        Document doc = builder.parse(new InputSource(new StringReader(body)));
        Element root = doc.getDocumentElement();
        List<Node> elements = new ArrayList<>(), attributes = new ArrayList<>(), leaves = new ArrayList<>();
        collect(root, root, elements, attributes, leaves);
        if (token != null) {
            for (List<Node> nodes : List.of(elements, attributes, leaves)) nodes.removeIf(n -> holds(n, token));
        }
        if (remove) {
            List<Node> from = new ArrayList<>(elements);
            from.addAll(attributes);
            if (from.isEmpty()) return null;
            Node gone = from.get(random.nextInt(from.size()));
            if (gone instanceof Attr attr) attr.getOwnerElement().removeAttributeNode(attr);
            else gone.getParentNode().removeChild(gone);
        } else {
            // XML has no types of its own: what reads as a number, a boolean or a date becomes letters. With no such
            // value, a simple element becomes one with a child, which no schema that wanted text there accepts.
            List<Node> values = new ArrayList<>(leaves);
            values.addAll(attributes);
            List<Node> typed = values.stream().filter(n -> TYPED.matcher(n.getTextContent().strip()).matches()).toList();
            if (!typed.isEmpty()) {
                typed.get(random.nextInt(typed.size())).setTextContent("not-a-number");
            } else if (!leaves.isEmpty()) {
                Node leaf = leaves.get(random.nextInt(leaves.size()));
                Element inner = doc.createElement("value");
                inner.setTextContent(leaf.getTextContent());
                leaf.setTextContent("");
                leaf.appendChild(inner);
            } else {
                return null;
            }
        }
        Transformer out = TransformerFactory.newInstance().newTransformer();
        out.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, body.stripLeading().startsWith("<?xml") ? "no" : "yes");
        StringWriter text = new StringWriter();
        out.transform(new DOMSource(doc), new StreamResult(text));
        return text.toString();
    }

    /** Whether the token is in this node's text, in what is inside it, or in one of its attributes. */
    private static boolean holds(Node node, String token) {
        if (node.getTextContent().contains(token)) return true;
        NamedNodeMap attrs = node.getAttributes();
        for (int i = 0; attrs != null && i < attrs.getLength(); i++) if (attrs.item(i).getNodeValue().contains(token)) return true;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element && holds(child, token)) return true;
        return false;
    }

    private static void collect(Element root, Element element, List<Node> elements, List<Node> attributes, List<Node> leaves) {
        if (element != root) elements.add(element);
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            if (!attrs.item(i).getNodeName().startsWith("xmlns")) attributes.add(attrs.item(i));
        }
        boolean hasChild = false;
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e) {
                hasChild = true;
                collect(root, e, elements, attributes, leaves);
            }
        }
        if (!hasChild && !element.getTextContent().isBlank()) leaves.add(element);
    }
}
