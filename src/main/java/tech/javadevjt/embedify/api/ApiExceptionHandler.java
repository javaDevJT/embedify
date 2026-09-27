package tech.javadevjt.embedify.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import tech.javadevjt.embedify.net.SafeFetchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(SafeFetchException.class)
    public ResponseEntity<ApiError> fetchFailure(SafeFetchException exception) {
        return switch (exception.kind()) {
            case INVALID_INPUT -> response(HttpStatus.BAD_REQUEST, "The URL is invalid or not allowed.", false);
            case BUSY -> response(HttpStatus.TOO_MANY_REQUESTS, "The upstream fetch limit is busy.", true);
            case UPSTREAM -> response(HttpStatus.BAD_GATEWAY, "The upstream resource could not be fetched.", false);
        };
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> responseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = exception.getReason();
        if (message == null || message.isBlank()) {
            message = status.is4xxClientError() ? "The request is invalid." : "The request could not be completed.";
        }
        return response(status, message, status == HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, "The request is invalid.", false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ignored) {
        if (ignored instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
            return response(status, status.getReasonPhrase(), false);
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.", false);
    }

    private static ResponseEntity<ApiError> response(HttpStatus status, String message, boolean retryable) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set("Referrer-Policy", "no-referrer");
        if (retryable) headers.set("Retry-After", "5");
        return new ResponseEntity<>(new ApiError(message), headers, status);
    }

    public record ApiError(String message) {}
}
