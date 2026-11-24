package io.msgforge;

import java.util.List;

/**
 * A custom {{placeholder}}. Provide one as a Spring bean, or in a plugin jar (see {@link Plugins}),
 * and templates can use {{name arg1 arg2}}. A generator with a built-in's name replaces it.
 */
public interface Generator {

    String name();

    String generate(List<String> args);

    /**
     * Whether a value found in an imported sample is one of this generator's, and if so the placeholder to put in
     * its place, without the braces: "customerId", or "customerId EU" with arguments. Null means it is not.
     * It is asked about whole values only (the text of an element, an attribute, a JSON string, a delimited cell).
     * The answer has to be this generator's own name, optionally with arguments. Anything else, and anything
     * thrown, counts as "not mine" and is logged once, so a faulty generator cannot break an import.
     */
    default String recognise(String value) {
        return null;
    }
}
