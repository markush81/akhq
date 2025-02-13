package org.akhq.controllers;

import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.micrometer.core.instrument.util.StringUtils;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.http.hateoas.Link;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.web.router.UriRouteMatch;
import lombok.extern.slf4j.Slf4j;
import org.akhq.modules.InvalidClusterException;
import org.akhq.security.annotation.AKHQSecured;
import org.apache.kafka.common.errors.ApiException;
import org.sourcelab.kafka.connect.apiclient.rest.exceptions.ConcurrentConfigModificationException;
import org.sourcelab.kafka.connect.apiclient.rest.exceptions.InvalidRequestException;
import org.sourcelab.kafka.connect.apiclient.rest.exceptions.ResourceNotFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

@Secured(SecurityRule.IS_ANONYMOUS)
@Slf4j
@Controller("/errors")
public class ErrorController extends AbstractController {
    // Kafka
    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, ApiException e) {
        return renderExecption(request, e);
    }

    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, ClassCastException e) {
        return extractAndRenderException(request, e);
    }

    // Registry
    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, RestClientException e) {
        return renderExecption(request, e);
    }

    // Connect
    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, InvalidRequestException e) {
        return renderExecption(request, e);
    }

    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, ResourceNotFoundException e) {
        return renderExecption(request, e);
    }

    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, ConcurrentConfigModificationException e) {
        return renderExecption(request, e);
    }

    // Akhq

    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, IllegalArgumentException e) {
        return renderExecption(request, e);
    }

    private HttpResponse<?> renderExecption(HttpRequest<?> request, Exception e) {
        JsonError error = new JsonError(e.getMessage())
            .link(Link.SELF, Link.of(request.getUri()));

        return HttpResponse.<JsonError>status(HttpStatus.CONFLICT)
            .body(error);
    }

    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, AuthorizationException e) throws URISyntaxException {
        if (request.getUri().toString().startsWith("/api")) {
            if (e.isForbidden()) {
                if (request.getAttribute(HttpAttributes.ROUTE_INFO).isPresent() &&
                    ((UriRouteMatch<?, ?>) request.getAttribute(HttpAttributes.ROUTE_INFO).get()).hasAnnotation(AKHQSecured.class)) {
                    AnnotationValue<AKHQSecured> annotation =
                        ((UriRouteMatch<?, ?>) request.getAttribute(HttpAttributes.ROUTE_INFO).get()).getAnnotation(AKHQSecured.class);

                    return HttpResponse.status(HttpStatus.FORBIDDEN)
                        .body(new JsonError(String.format("Unauthorized: missing permission on resource %s and action %s",
                            annotation.getValues().get("resource"),
                            annotation.getValues().get("action"))));
                }
            } else {
                return HttpResponse.unauthorized().body(new JsonError("User not authenticated or token expired"));
            }
        }

        return HttpResponse.temporaryRedirect(this.uri("/ui/login"));
    }

    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, Throwable e) {
        log.error(e.getMessage(), e);

        StringWriter stringWriter = new StringWriter();
        e.printStackTrace(new PrintWriter(stringWriter));

        JsonError error = new JsonError("Internal Server Error: " + e.getMessage())
            .link(Link.SELF, Link.of(request.getUri()))
            .embedded(
                "stacktrace",
                new JsonError(stringWriter.toString())
            );

        return HttpResponse.<JsonError>serverError()
            .body(error);
    }

    @Error(status = HttpStatus.NOT_FOUND, global = true)
    public HttpResponse<?> notFound(HttpRequest<?> request) throws URISyntaxException {
        if (request.getPath().equals("/") && StringUtils.isNotEmpty(getBasePath())) {
            return HttpResponse.temporaryRedirect(this.uri("/"));
        }

        JsonError error = new JsonError("Page Not Found")
            .link(Link.SELF, Link.of(request.getUri()));

        return HttpResponse.<JsonError>notFound()
            .body(error);
    }

    @Error(global = true)
    public HttpResponse<?> error(HttpRequest<?> request, InvalidClusterException e) {
        JsonError error = new JsonError(e.getMessage())
            .link(Link.SELF, Link.of(request.getUri()));

        return HttpResponse.status(HttpStatus.CONFLICT).body(error);
    }

    private HttpResponse<?> extractAndRenderException(HttpRequest<?> request, Exception e) {
        String fieldRegex = "field\\s.*$";
        String expectedTypeRegex = "cannot be cast to\\sclass\\s[A-z.]+";
        String actualTypeRegex = "class\\s[A-z.]+";

        String actualField = retrievePatternMatch(fieldRegex, e.getMessage());
        String expectedType = retrievePatternMatch(expectedTypeRegex, e.getMessage()).toLowerCase();
        String actualType = retrievePatternMatch(actualTypeRegex, e.getMessage()).toLowerCase();

        var message = String.format("Field %s required %s but got %s", actualField, expectedType, actualType);
        JsonError error = new JsonError(message)
            .link(Link.SELF, Link.of(request.getUri()));

        return HttpResponse.<JsonError>status(HttpStatus.CONFLICT)
            .body(error);
    }

    private String retrievePatternMatch(String regex, String message) {
        var compile = Pattern.compile(regex);
        var matcher = compile.matcher(message);
        if (matcher.find()) {
            var group = matcher.group();
            var invalidWordRegex = "field\\s|cannot\\sbe\\scast\\sto\\sclass\\s|class\\s|java.lang.";

            return group.replaceAll(invalidWordRegex, "");
        }

        return "";
    }
}
