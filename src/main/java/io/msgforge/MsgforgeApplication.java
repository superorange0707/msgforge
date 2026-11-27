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

              bin/msgforge                     start the console, then open http://localhost:8585
              bin/msgforge run <scenario>      run a saved scenario (name or id) without the console, and exit
              bin/msgforge list                list the saved scenarios
              bin/msgforge --help              this text

            run prints each check and exits 0 when the run passed (or had nothing to verify), 1 when a check
            failed, 2 for anything else: no such scenario, a setting that is wrong, a run that failed.

              --report <file>          also write the result as JUnit XML, for a CI system to show
              --url http://host:port   run it on a msgforge that is already running, instead of here (also for list)
              --count <n>              send this many messages instead of the scenario's number
              --rate <r>               messages per second instead of the scenario's rate, 0 for as fast as possible
              --duration <s>           stop sending after this many seconds (with a count: whichever comes first)
              --ramp-up <s>            reach the rate in a straight line over this many seconds
              --senders <n>            send from this many connections at once, 0 to add them as the run falls behind
              --invalid <percent>      make this share of the messages invalid on purpose
              --seed <n>               repeat the bodies and faults of the run that printed this seed
              --save <folder>          also write the run's report there as HTML, JSON and CSV

            Settings are environment variables (or --name=value arguments, shown in brackets). run and list
            read the same ones as the console, except PORT and SERVER_ADDRESS: they open no port.

              PORT                    port of the console, 8585                           [--server.port]
              SERVER_ADDRESS          address to listen on, 127.0.0.1                     [--server.address]
              MSGFORGE_CONFIG         file for connections and scenarios, msgforge.json   [--msgforge.config]
              MSGFORGE_PACKS          pack folders, comma-separated, packs                [--msgforge.packs]
              MSGFORGE_PLUGINS        folder of plugin jars, plugins                      [--msgforge.plugins]
              MSGFORGE_ALLOWED_HOSTS  host names it answers to besides localhost          [--msgforge.allowed-hosts]
              MSGFORGE_ENV_ALLOW      variables a connection may name besides MSGFORGE_*  [--msgforge.env-allow]
              MSGFORGE_SHOW           variables the console may show the value of, MSGFORGE_ENV [--msgforge.show]
              MSGFORGE_REPORTS        folder every finished run's report is saved in, none [--msgforge.reports]
              MSGFORGE_REPORTS_KEEP   how many runs that folder keeps, 50                 [--msgforge.reports-keep]
              MSGFORGE_THEME          a .css file laid over the console's own style       [--msgforge.theme]
              MSGFORGE_TITLE          what the console calls itself, msgforge             [--msgforge.title]

            The README next to this program explains each of them.
            """;

    public static void main(String[] args) {
        if (Cli.handles(args) && !Arrays.asList(args).contains("--help")) {
            System.exit(Cli.main(args, System.out));
        }
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
