package io.msgforge;

import java.util.Arrays;

import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class MsgforgeApplication {

    static final String USAGE = """
            msgforge: a message simulator with a console in the browser.

              bin/msgforge            start it, then open http://localhost:8585
              bin/msgforge --help     this text

            Settings are environment variables (or --name=value arguments, shown in brackets):

              PORT                    port of the console, 8585                           [--server.port]
              SERVER_ADDRESS          address to listen on, 127.0.0.1                     [--server.address]
              MSGFORGE_CONFIG         file for connections and scenarios, msgforge.json   [--msgforge.config]
              MSGFORGE_PACKS          pack folders, comma-separated, packs                [--msgforge.packs]
              MSGFORGE_PLUGINS        folder of plugin jars, plugins                      [--msgforge.plugins]
              MSGFORGE_ALLOWED_HOSTS  host names it answers to besides localhost          [--msgforge.allowed-hosts]
              MSGFORGE_ENV_ALLOW      variables a connection may name besides MSGFORGE_*  [--msgforge.env-allow]

            The README next to this program explains each of them.
            """;

    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.print(USAGE);
                return;
            }
        }
        SpringApplication.run(MsgforgeApplication.class, args);
    }

    /** The one line a first-time user is looking for. Listening on every address (a container) still has to print something a browser can open. */
    @EventListener
    void started(WebServerInitializedEvent event) {
        var env = event.getApplicationContext().getEnvironment();
        LoggerFactory.getLogger(MsgforgeApplication.class).info(ready(env.getProperty("server.address", "127.0.0.1"),
                event.getWebServer().getPort(), env.getProperty("msgforge.allowed-hosts", "")));
    }

    /** Bound to one address of its own, the console refuses that name until it is allowed: say so next to the link, or the first click is a 403. */
    static String ready(String bound, int port, String allowed) {
        String url = address(bound, port);
        String host = url.substring("http://".length(), url.lastIndexOf(':'));
        boolean answers = host.equals("localhost") || host.equals("[::1]")
                || Arrays.stream(allowed.split(",")).map(String::strip).anyMatch(a -> a.equals("*") || a.equalsIgnoreCase(host));
        return "msgforge is ready: open " + url + (answers ? "" : " (add " + host + " to MSGFORGE_ALLOWED_HOSTS first, or the console answers 403)");
    }

    static String address(String bound, int port) {
        String host = bound.equals("0.0.0.0") || bound.equals("::") || bound.equals("127.0.0.1") ? "localhost" : bound.contains(":") ? "[" + bound + "]" : bound;
        return "http://" + host + ":" + port;
    }
}
