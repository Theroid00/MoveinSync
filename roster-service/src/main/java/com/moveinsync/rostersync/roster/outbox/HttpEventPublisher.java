package com.moveinsync.rostersync.roster.outbox;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;

import com.moveinsync.rostersync.roster.core.RetryPolicy;
import com.moveinsync.rostersync.roster.core.RetryPolicy.Verdict;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * POSTs an event to booking-service's /internal/events with the service token and
 * Idempotency-Key = eventId. RestClient is built by hand with explicit timeouts (connect 1 s, read 3 s).
 */
@Component
public class HttpEventPublisher implements EventPublisher {

    private final RestClient client;
    private final ObjectProvider<ServiceTokenSupplier> tokens;

    public HttpEventPublisher(@Value("${app.booking.base-url}") String baseUrl, ObjectProvider<ServiceTokenSupplier> tokens) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
        this.tokens = tokens;
    }

    @Override
    public PublishResult publish(UUID eventId, String payloadJson) {
        try {
            RestClient.RequestBodySpec request = client.post().uri("/internal/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", eventId.toString());
            ServiceTokenSupplier supplier = tokens.getIfAvailable();
            if (supplier != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + supplier.serviceToken());
            }
            int status = request.body(payloadJson).exchange((req, res) -> res.getStatusCode().value());
            Verdict verdict = RetryPolicy.forStatus(status);
            return new PublishResult(verdict, verdict == Verdict.DELIVERED ? null : "HTTP_" + status);
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof HttpTimeoutException
                    || e.getCause() instanceof java.net.SocketTimeoutException;
            return new PublishResult(Verdict.RETRY, timeout ? "TIMEOUT" : "IO_ERROR");
        } catch (RuntimeException e) {
            return new PublishResult(Verdict.RETRY, "IO_ERROR");
        }
    }
}
