package fr.pantheonsorbonne.cri.javarunner.auth;

import com.google.common.collect.Streams;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.catalina.AccessLog;
import org.apache.catalina.Globals;
import org.apache.juli.logging.Log;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public class JWTFilter implements Filter {
    String authServerURLInteral;
    String authServerURLExternal;

    private static final Logger LOGGER = LoggerFactory.getLogger(JWTFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        Filter.super.init(filterConfig);
        authServerURLInteral = System.getenv("AUTH_SERVER_URL_INTERNAL");
        authServerURLExternal = System.getenv("AUTH_SERVER_URL_EXTERNAL");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {


        if (response instanceof HttpServletResponse && request instanceof HttpServletRequest) {
            doFilter((HttpServletRequest) request, ((HttpServletResponse) response), chain);
        } else {
            chain.doFilter(request, response);
        }


    }

    private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {


        //first check if the jwt token is in the headers
        String bearer = request.getHeader("Authorization");
        String jWTtoken = null;
        if (Objects.nonNull(bearer)) {
            jWTtoken = bearer;
        } else if (request.getParameterMap().containsKey("token")) {
            jWTtoken = request.getParameterMap().get("token")[0].toString();
        } else if (Objects.nonNull(request.getCookies()) && request.getCookies().length > 0) {
            jWTtoken = Arrays.stream(request.getCookies()).filter(c -> c.getName().equals("auth-token")).map(c -> c.getValue()).findAny().orElse(null);
        }


        if (jWTtoken != null) {
            ResteasyClient client = (ResteasyClient) ClientBuilder.newClient();

            ResteasyWebTarget target = client.target(this.authServerURLInteral);

            CheckClaimResource checkClaimResource = target.proxy(CheckClaimResource.class);
            Response resp = checkClaimResource.checkRecaptcha("Bearer " + jWTtoken);
            if (resp.getStatus() == 200) {
                var cookie = new Cookie("auth-token", jWTtoken);
                cookie.setSecure(true);
                cookie.setMaxAge(Integer.MAX_VALUE);

                response.addCookie(cookie);
                request.getSession(true).setAttribute("authToken", jWTtoken);
                chain.doFilter(request, response);
                return;
            }
        }

        //if we are here, we need a new token
        LOGGER.atDebug().log(() -> "Headers for query passing the JWTFilter: " + Streams.stream(request.getHeaderNames().asIterator()).map(hn -> String.format("%s:%s", hn, request.getHeader(hn))).collect(Collectors.joining(",")));

        LOGGER.atDebug().log(() -> "Attributes for request passing the JWTFilter: " + Streams.stream(request.getAttributeNames().asIterator()).map(hn -> String.format("%s:%s", hn, request.getAttribute(hn).toString())).collect(Collectors.joining(",")));

        String host = request.getHeader("host").split(":")[0];
        if (request.getHeader("x-forwarded-host") != null) {
            host = request.getHeader("x-forwarded-host");
        }

        String protocol = "http";
        if (request.getHeader("x-forwarded-scheme") != null) {
            protocol = request.getHeader("x-forwarded-scheme");
        }

        Integer port = null;
        if (request.getHeader("x-forwarded-port") != null) {
            port = Integer.parseInt(request.getHeader("x-forwarded-port"));
        } else if (request.getHeader("host").split(":").length == 2) {
            port = Integer.parseInt(request.getHeader("host").split(":")[1]);
        } else {
            LOGGER.warn("failed to load port from host and from x-forwarded-port, defaulting to 8080");
            port = 8080;
        }


        UriBuilder builder = UriBuilder.newInstance().scheme(protocol).host(host).port(port).path(request.getRequestURI());
        request.getParameterMap().forEach((s, sa) -> builder.queryParam(s, sa));
        builder.replaceQueryParam("token", null);
        LOGGER.debug("callback url after filter: {}", builder.build().toASCIIString());
        UriBuilder authUriBuilder = UriBuilder.fromPath(this.authServerURLExternal).queryParam("callback", ((HttpServletResponse) response).encodeURL(builder.build().toASCIIString()));
        response.sendRedirect(response.encodeRedirectURL(authUriBuilder.build().toASCIIString()));


    }
}
