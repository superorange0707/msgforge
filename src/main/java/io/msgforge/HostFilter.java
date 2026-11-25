package io.msgforge;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The console has no login, so being reachable only from this machine is its whole protection. A web page
 * elsewhere can still reach it through the user's browser: by pointing its own domain at 127.0.0.1 (DNS
 * rebinding), which makes it same-origin, by posting across origins, or by showing the console in a frame and
 * getting the user to click in it. All three are refused here. A rebinding request carries the page's host name,
 * a cross-origin write an Origin that is not this server (or Sec-Fetch-Site saying so), and every answer forbids
 * being framed.
 *
 * It covers every request, not only /api: that leaves no path spelling to get around, and a host that is
 * not allowed is told so on the first page load.
 */
@Component
public class HostFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> OWN = Set.of("same-origin", "none");   // Sec-Fetch-Site: the console itself, or typed in by the user

    private final Set<String> allowed;

    public HostFilter(@Value("${msgforge.allowed-hosts:}") String[] allowed) {
        this.allowed = Arrays.stream(allowed).map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        String host = request.getHeader("Host");
        String name = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst(":\\d*$", "");
        if (!allowed.contains("*") && !LOCAL.contains(name) && !allowed.contains(name)) {
            refuse(response, "msgforge only answers to localhost. To reach it under another host name, start it with MSGFORGE_ALLOWED_HOSTS set to that name.");
        } else if (!READS.contains(request.getMethod()) && (request.getHeader("Origin") != null && !sameSite(request.getHeader("Origin"), host)
                || request.getHeader("Sec-Fetch-Site") != null && !OWN.contains(request.getHeader("Sec-Fetch-Site")))) {
            refuse(response, "This request comes from another site, so msgforge has not acted on it.");
        } else {
            chain.doFilter(request, response);
        }
    }

    private static boolean sameSite(String origin, String host) {
        try {
            return host != null && host.equalsIgnoreCase(URI.create(origin).getAuthority());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void refuse(HttpServletResponse response, String why) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + why + "\"}");
    }
}
