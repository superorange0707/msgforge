package io.msgforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.ObjectMapper;

class RecogniserTest {

    private final TemplateEngine engine = new TemplateEngine();
    private final Recogniser recogniser = new Recogniser(engine);

    private String template(String sample) {
        return recogniser.recognise(sample, Map.of(), false).template();
    }

    /** Everything recognition may change, blanked out: what is left of a sample and of its rendered template must be the same text. */
    private static String withoutTheVariableParts(String text) {
        return text.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "#")
                .replaceAll("\\b[0-9a-fA-F]{32}\\b", "#")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}:\\d{2}([.,]\\d+)?(Z|[+-]\\d{2}:?\\d{2})?)?", "#")
                .replaceAll("\\b20\\d{12}\\b", "#")
                .replaceAll("\\b(GB|DE|NL)\\d{2}[A-Z0-9]{14,18}\\b", "#");
    }

    @Test
    void eachKindOfValueThatDiffersPerMessageBecomesItsPlaceholder() {
        assertEquals("id={{uuid}}", template("id=3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d"));
        assertEquals("id={{uuid}}", template("id=3F2B8C1E-9A4D-4E7B-8C21-5D6F7A8B9C0D"));
        assertEquals("ref {{id}} end", template("ref 9f86d081884c7d659a2feaa0c55ad015 end"));

        // a time keeps the shape the sample writes it in
        assertEquals("{{now}}", template("2024-03-09T14:05:33Z"));
        assertEquals("{{now yyyy-MM-dd'T'HH:mm:ss}}", template("2024-03-09T14:05:33"));
        assertEquals("{{now yyyy-MM-dd'T'HH:mm:ss.SSSXXX}}", template("2024-03-09T14:05:33.120Z"));
        assertEquals("{{now yyyy-MM-dd'T'HH:mm:ssxxx}}", template("2024-03-09T14:05:33+02:00"));
        assertEquals("{{now yyyy-MM-dd'T'HH:mm:ss.SSSSSSxx}}", template("2024-03-09T14:05:33.120456+0200"));
        assertEquals("at {{now \"yyyy-MM-dd HH:mm:ss\"}} ok", template("at 2024-03-09 14:05:33 ok"));
        assertEquals("{{now yyyyMMddHHmmss}}", template("20240309140533"));
        // and every one of them renders in that shape
        assertTrue(engine.render(template("2024-03-09T14:05:33.120+02:00"), Map.of(), Map.of()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.000\\+00:00"));
        assertTrue(engine.render(template("at 2024-03-09 14:05:33 ok"), Map.of(), Map.of()).matches("at \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} ok"));
        assertTrue(engine.render(template("20240309140533"), Map.of(), Map.of()).matches("20\\d{12}"));

        // an account number the iban generator could have made: right country, right length, valid check digits
        assertEquals("{{iban GB}};{{iban DE}};{{iban NL}}", template("GB82WEST12345698765432;DE89370400440532013000;NL91ABNA0417164300"));
        String generated = engine.render("{{iban NL}}", Map.of(), Map.of());
        assertEquals("{{iban NL}}", template(generated));

        Recogniser.Result result = recogniser.recognise("a\nb 3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d\n\n2024-03-09T14:05:33Z\n", Map.of(), false);
        assertEquals(List.of(new Recogniser.Replacement(2, "3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d", "{{uuid}}", null), new Recogniser.Replacement(4, "2024-03-09T14:05:33Z", "{{now}}", null)),
                result.replacements());
    }

    @Test
    void whatDecidesARouteAndWhatOnlyLooksLikeAnIdIsLeftAlone() {
        for (String untouched : List.of(
                "<Amount currency=\"EUR\">1250.00</Amount>",                 // an amount, and a currency no pack field knows
                "{\"quantity\": 42, \"country\": \"GB\", \"type\": \"EXPRESS\"}",
                "12345678901234567890123456789012",                           // 32 digits are a number
                "20241399140533", "19991231235959", "20240309140533.5", "x20240309140533",   // no month 13; not this century; a decimal; part of a word
                "2024-13-09T10:00:00Z", "2024-03-09T25:00:00Z x", "12024-03-09T10:00:00Z",
                // A date without a time is not "now": it is a version, a birthday, part of a name.
                "<Bucket xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>", "{\"Version\": \"2012-10-17\", \"born\": \"1985-07-23\"}",
                "ORD-2024-01-15-0001", "report_2024-01-15.csv", "due 2024-03-09.",
                // Right after a backslash a placeholder would be read as an escaped {{ and not be filled in.
                "C:\\logs\\3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d\\app.log", "{\"path\": \"C:\\\\tmp\\\\9f86d081884c7d659a2feaa0c55ad015\\\\x\"}",
                "\\2024-03-09T14:05:33Z", "\\20240309140533", "\\GB82WEST12345698765432",
                "GB82WEST12345698765431", "FR7630006000011234567890189", "GB82 WEST 1234 5698 7654 32",   // wrong check digits; a country iban does not make; spaced
                "3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0", "g9f86d081884c7d659a2feaa0c55ad015", "9f86d081884c7d659a2feaa0c55ad0150",
                "version 1.2.3 build 7", "")) {
            Recogniser.Result result = recogniser.recognise(untouched, Map.of(), false);
            assertEquals(untouched, result.template());
            assertTrue(result.replacements().isEmpty(), untouched);
            assertEquals(untouched, engine.render(result.template(), Map.of(), Map.of()), "and it renders as itself");
        }
    }

    @Test
    void aValueThatOccursTwiceIsGeneratedOnceAndRepeatedByName() {
        String xml = """
                <Order id="3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d">
                  <ns:Reference>9f86d081884c7d659a2feaa0c55ad015</ns:Reference>
                  <Parent>3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d</Parent>
                  <Other>11111111-2222-4333-8444-555555555555</Other>
                  <Echo>9f86d081884c7d659a2feaa0c55ad015</Echo>
                  <Account>GB82WEST12345698765432</Account><Again>GB82WEST12345698765432</Again>
                </Order>
                """;
        String template = template(xml);
        assertEquals("""
                <Order id="{{id1=uuid}}">
                  <ns:Reference>{{Reference=id}}</ns:Reference>
                  <Parent>{{id1}}</Parent>
                  <Other>{{uuid}}</Other>
                  <Echo>{{Reference}}</Echo>
                  <Account>{{Account=iban GB}}</Account><Again>{{Account}}</Again>
                </Order>
                """, template, "the attribute is called id, which is a generator's name, so it gets id1; the element names are free");
        String rendered = engine.render(template, Map.of(), Map.of());
        String first = rendered.substring(rendered.indexOf("id=\"") + 4, rendered.indexOf("\">"));
        assertTrue(rendered.contains("<Parent>" + first + "</Parent>"), rendered);

        // in JSON the key gives the name; a name that is a pack field, or taken by the text already, is not used
        String json = "{\"orderId\": \"3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d\", \"links\": [\"/orders/3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d\"]}";
        assertEquals("{\"orderId\": \"{{orderId=uuid}}\", \"links\": [\"/orders/{{orderId}}\"]}", template(json));
        assertEquals("{\"orderId\": \"{{id1=uuid}}\", \"links\": [\"/orders/{{id1}}\"]}",
                recogniser.recognise(json, Map.of("orderId", "{{pick a b}}"), false).template());
        // with no name to take, or the name taken, they are numbered past what the text already uses
        assertEquals("{{id1=uuid}} {{id2=uuid}} {{id2}} {{id}} | {{id3=id}} {{id3}}",
                template("{{id1=uuid}} 3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d 3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d 9f86d081884c7d659a2feaa0c55ad015 | "
                        + "aaaaaaaa00000000aaaaaaaa00000000 aaaaaaaa00000000aaaaaaaa00000000"));
        // the same time twice is simply the time twice
        assertEquals("{{now}} {{now}}", template("2024-03-09T14:05:33Z 2024-03-09T14:05:33Z"));
    }

    @Test
    void whatIsAPlaceholderAlreadyStaysAndWhatOnlyLooksLikeOneIsEscaped() {
        String sample = """
                {"id": "{{uuid}}", "ref": "{{ref=id}}", "again": "{{ref}}", "at": "2024-03-09T14:05:33Z",
                 "greeting": "Hello {{ user.name }} and {{#each items}}", "kept": "\\{{literal}}", "who": "{{customer}}"}""";
        Recogniser.Result once = recogniser.recognise(sample, Map.of("customer", "{{pick Ada Alan}}"), true);
        assertEquals("""
                {"id": "{{uuid}}", "ref": "{{ref=id}}", "again": "{{ref}}", "at": "{{now}}",
                 "greeting": "Hello \\{{ user.name }} and {{#each items}}", "kept": "\\{{literal}}", "who": "{{customer}}"}""", once.template());
        assertEquals(1, once.replacements().size(), "only the time; the escape is not a replacement");

        // doing it again changes nothing
        Recogniser.Result twice = recogniser.recognise(once.template(), Map.of("customer", "{{pick Ada Alan}}"), true);
        assertEquals(once.template(), twice.template());
        assertTrue(twice.replacements().isEmpty());

        // and the template renders: the other language's braces come out as they went in
        String rendered = engine.render(once.template(), Map.of("customer", "{{pick Ada Alan}}"), Map.of());
        assertTrue(rendered.contains("\"greeting\": \"Hello {{ user.name }} and {{#each items}}\", \"kept\": \"{{literal}}\""), rendered);
        // without the escape the sample could not be used as a template at all
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> engine.render(sample, Map.of("customer", "x"), Map.of()));
    }

    @Test
    void theRenderedTemplateIsTheSameDocumentApartFromTheRecognisedValues() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ShipmentNotice xmlns="urn:example:shipment:1" version="2.1">
                  <ShipmentId>3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d</ShipmentId>
                  <Created>2024-03-09T14:05:33.120+01:00</Created>
                  <Dispatch date="2024-03-11"/>
                  <Carrier code="NWF">Northwind Freight &amp; Sons</Carrier>
                  <ShipTo country="GB">Ada Lovelace, 12 St James's Square</ShipTo>
                  <Parcels>3</Parcels><WeightKg>12.5</WeightKg><Value currency="EUR">1250.00</Value>
                  <Label>3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d</Label>
                  <!-- batch 9f86d081884c7d659a2feaa0c55ad015 -->
                </ShipmentNotice>
                """;
        Recogniser.Result fromXml = recogniser.recognise(xml, Map.of(), false);
        assertEquals(4, fromXml.replacements().size(), fromXml.replacements().toString());
        assertTrue(fromXml.template().contains("<Dispatch date=\"2024-03-11\"/>"), "the date stays");
        String renderedXml = engine.render(fromXml.template(), Map.of(), Map.of());
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(renderedXml)));
        assertEquals(withoutTheVariableParts(xml), withoutTheVariableParts(renderedXml));
        assertFalse(renderedXml.contains("3f2b8c1e"), "and the recognised values did change");

        String json = """
                {
                  "orderId": "3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d",
                  "createdAt": "2024-03-09 14:05:33",
                  "traceId": "9f86d081884c7d659a2feaa0c55ad015",
                  "customer": {"name": "Ada \\"the first\\" Lovelace", "since": "2019-07-01", "account": "DE89370400440532013000"},
                  "items": [{"sku": "A-100", "quantity": 2, "price": 19.99}, {"sku": "B-7", "quantity": 1, "price": 5}],
                  "total": 44.98, "currency": "EUR", "express": true, "note": null,
                  "self": "/orders/3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d"
                }
                """;
        Recogniser.Result fromJson = recogniser.recognise(json, Map.of(), false);
        assertEquals(5, fromJson.replacements().size(), fromJson.replacements().toString());
        String renderedJson = engine.render(fromJson.template(), Map.of(), Map.of());
        ObjectMapper mapper = new ObjectMapper();
        var before = mapper.readTree(json);
        var after = mapper.readTree(renderedJson);
        assertEquals(before.get("items"), after.get("items"));
        assertEquals(before.get("customer").get("name"), after.get("customer").get("name"));
        assertEquals("/orders/" + after.get("orderId").asText(), after.get("self").asText(), "one id, in both places");
        assertEquals(withoutTheVariableParts(json), withoutTheVariableParts(renderedJson));
        assertTrue(renderedJson.contains("\"total\": 44.98, \"currency\": \"EUR\""));

        // a delimited line: whatever is not XML or JSON is text
        String csv = "3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d,2024-03-09T14:05:33Z,north-1,sensor-17,temperature,21.5\n";
        assertEquals("{{uuid}},{{now}},north-1,sensor-17,temperature,21.5\n", template(csv));
    }

    @Test
    void aPackTeachesItsChoicesForWholeValuesOnly() {
        Map<String, String> fields = Map.of(
                "carrier", "{{pick \"Northwind Freight\" \"Blue Harbor\"}}", "site", "{{pick north-1 south-2}}",
                "country", "{{pick GB DE}}", "origin", "{{pick DE FR}}",          // DE is offered by two fields: nobody can say which is meant
                "reference", "INV-{{digits 6}}", "label", "{{uuid}}");            // not a list of choices
        String xml = """
                <Shipment carrier="Blue Harbor" route="GB-DE">
                  <Carrier>  Northwind Freight </Carrier>
                  <Note>Northwind Freight was late, Blue Harbor took over</Note>
                  <From>DE</From><To>GB</To><Via>FR</Via>
                  <GB>north-1, south-2</GB>
                </Shipment>
                """;
        Recogniser.Result result = recogniser.recognise(xml, fields, true);
        assertEquals("""
                <Shipment carrier="{{carrier}}" route="GB-DE">
                  <Carrier>  {{carrier}} </Carrier>
                  <Note>Northwind Freight was late, Blue Harbor took over</Note>
                  <From>DE</From><To>{{country}}</To><Via>{{origin}}</Via>
                  <GB>north-1, south-2</GB>
                </Shipment>
                """, result.template());
        assertEquals(List.of("carrier", "carrier", "country", "origin"), result.replacements().stream().map(Recogniser.Replacement::field).collect(Collectors.toList()));
        // Unless asked to, the pack's fields are not applied: an imported sample still stands for the route it was taken from.
        assertEquals(xml, recogniser.recognise(xml, fields, false).template());
        assertEquals(xml, template(xml));

        // A whole value is the whole value. Markup characters inside an attribute, a comment and a CDATA section are text, not structure;
        // a quoted cell is one cell, whatever is in it.
        String tricky = "<Order note=\"x>GB<y\" tip='a>north-1<b'><!-- <To>GB</To> --><![CDATA[<To>GB</To>]]><To>GB</To><Site> north-1\n</Site></Order>";
        assertEquals("<Order note=\"x>GB<y\" tip='a>north-1<b'><!-- <To>GB</To> --><![CDATA[<To>GB</To>]]><To>{{country}}</To><Site> {{site}}\n</Site></Order>",
                recogniser.recognise(tricky, fields, true).template());
        assertEquals("1,\"Harbor, Blue Harbor\",\"{{id1=carrier}}\", {{id1}} ,GB south-2\n",
                recogniser.recognise("1,\"Harbor, Blue Harbor\",\"Blue Harbor\", Blue Harbor ,GB south-2\n", Map.of("carrier", "{{pick \"Blue Harbor\"}}", "site", "{{pick south-2}}"), true)
                        .template());

        assertEquals("{\"north-1\": \"{{site}}\", \"sites\": [\"{{id1=site}}\", \"{{id1}}\", \"north-1 \\u0026 south-2\"], \"n\": 1}",
                recogniser.recognise("{\"north-1\": \"north-1\", \"sites\": [\"south-2\", \"south-2\", \"north-1 \\u0026 south-2\"], \"n\": 1}", fields, true).template(),
                "a key is not a value; the same choice twice stays the same choice");
        assertEquals("r1,{{site}},\"{{carrier}}\",north-1 annex,status={{country}}\n", recogniser.recognise("r1,north-1,\"Blue Harbor\",north-1 annex,status=GB\n", fields, true).template());
    }

    @Test
    void aGeneratorSaysWhichValuesAreItsOwn() {
        Generator customerId = new Generator() {
            public String name() { return "customerId"; }
            public String generate(List<String> args) { return "C-" + (args.isEmpty() ? "EU" : args.get(0)) + "-" + System.nanoTime(); }
            public String recognise(String value) { return value.matches("C-(EU|US)-\\d+") ? "customerId " + value.substring(2, 4) : null; }
        };
        Recogniser withPlugin = new Recogniser(new TemplateEngine(List.of(customerId)));
        String sample = "{\"customer\": \"C-US-1001\", \"payer\": \"C-US-1001\", \"other\": \"C-EU-7\", \"text\": \"about C-EU-7\", \"id\": \"3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d\"}";
        Recogniser.Result result = withPlugin.recognise(sample, Map.of(), false);
        assertEquals("{\"customer\": \"{{customer=customerId US}}\", \"payer\": \"{{customer}}\", \"other\": \"{{customerId EU}}\", \"text\": \"about C-EU-7\", \"id\": \"{{uuid}}\"}",
                result.template());
        assertEquals(List.of("C-US-1001", "C-US-1001", "C-EU-7", "3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d"),
                result.replacements().stream().map(Recogniser.Replacement::value).collect(Collectors.toList()));
        // a generator that recognises nothing (the default) changes nothing, and the core does not know this format
        assertEquals(sample.replace("3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d", "{{uuid}}"), template(sample));
    }

    @Test
    void aGeneratorThatMisbehavesIsTakenAsNotMine() {
        List<String> asked = new java.util.ArrayList<>();
        java.util.function.BiFunction<String, java.util.function.Function<String, String>, Generator> answering = (name, answer) -> new Generator() {
            public String name() { return name; }
            public String generate(List<String> args) { return "x"; }
            public String recognise(String value) {
                asked.add(name);
                return answer.apply(value);
            }
        };
        Recogniser withPlugins = new Recogniser(new TemplateEngine(List.of(
                answering.apply("broken", v -> { throw new NoClassDefFoundError("org/example/Missing"); }),   // a plugin built against a class that is not there
                answering.apply("throws", v -> { throw new IllegalStateException("no"); }),
                answering.apply("uuidThief", v -> "uuid"),                          // names a placeholder that is not its own
                answering.apply("binder", v -> "x=binder"),                         // a binding
                answering.apply("braces", v -> "{{braces}}"),                       // already in braces
                answering.apply("two", v -> "two}} and {{uuid"),                    // more than one placeholder
                answering.apply("blank", v -> " "),
                answering.apply("good", v -> v.equals("VALUE-1") ? " good \"two words\" 7 " : null))));
        Recogniser.Result result = withPlugins.recognise("{\"a\": \"VALUE-1\", \"b\": \"other\"}", Map.of(), false);
        assertEquals("{\"a\": \"{{good \"two words\" 7}}\", \"b\": \"other\"}", result.template(), "the ones before it did not stop it, and none of their answers got in");
        assertTrue(asked.containsAll(List.of("broken", "throws", "uuidThief", "binder", "braces", "two", "blank", "good")));
    }

    @Test
    void longValuesAndManyHitsAreHandled() {
        // A string of this length overflowed the stack when strings were matched by a pattern; so would any pattern that
        // steps through a value one group at a time. Each format, with something to recognise on either side of the long value.
        String id = "3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d", time = "2024-03-09T14:05:33Z", filler = "abc/+0123456789 \\\" x ".repeat(5_000);   // with escapes in it
        assertTrue(filler.length() >= 100_000);
        String plain = filler.replace("\\\"", "__");
        assertEquals("{\"id\":\"{{uuid}}\",\"attachment\":\"" + filler + "\",\"at\":\"{{now}}\"}",
                template("{\"id\":\"" + id + "\",\"attachment\":\"" + filler + "\",\"at\":\"" + time + "\"}"));
        assertEquals("<a><id>{{uuid}}</id><blob note=\"" + plain + "\">" + plain + "</blob><at>{{now}}</at></a>",
                template("<a><id>" + id + "</id><blob note=\"" + plain + "\">" + plain + "</blob><at>" + time + "</at></a>"));
        assertEquals("{{uuid}}," + plain + ",\"" + plain + "\",{{now}}\n", template(id + "," + plain + ",\"" + plain + "\"," + time + "\n"));
        // one long run of each shape's own characters, which a careless pattern walks again from every position
        for (String run : List.of("a".repeat(100_000), "0".repeat(100_000), "{".repeat(100_000), "{{ x".repeat(25_000), "\\".repeat(100_000), "2024-".repeat(20_000), "<".repeat(100_000), "\"".repeat(99_999))) {
            assertEquals(run, template(run), run.substring(0, 8));
        }

        // About a megabyte with 35,000 values to replace took almost six seconds: every hit was checked against every earlier one,
        // and its line number counted from the top.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 35_000; i++) big.append(String.format("%08x-0000-4000-8000-000000000000,", i)).append("2024-03-09T14:05:33Z,x\n");
        long began = System.nanoTime();
        Recogniser.Result many = recogniser.recognise(big.toString(), Map.of(), false);
        long took = (System.nanoTime() - began) / 1_000_000;
        assertEquals(70_000, many.replacements().size());
        assertEquals(new Recogniser.Replacement(35_000, "000088b7-0000-4000-8000-000000000000", "{{uuid}}", null), many.replacements().get(69_998));
        assertTrue(took < 3_000, "took " + took + " ms");
    }
}
