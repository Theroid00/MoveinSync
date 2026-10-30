package com.moveinsync.rostersync.roster;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for booking-service's /internal/events (spec §17): a JDK HttpServer on a random port with
 * programmable replies (status, delay, closed connection) and a log of every request received.
 */
public final class StubBookingServer implements AutoCloseable {

    public sealed interface Reply permits Status, Delay, CloseConnection {
    }

    public record Status(int code) implements Reply {
    }

    public record Delay(long millis, int code) implements Reply {
    }

    public record CloseConnection() implements Reply {
    }

    /** One request as received: call number, Idempotency-Key, Authorization and raw body. */
    public record Received(int call, String idempotencyKey, String authorization, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Function<Received, Reply> behavior = r -> new Status(200);

    private StubBookingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/internal/events", this::handle);
        server.start();
    }

    public static StubBookingServer start() {
        try {
            return new StubBookingServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(Function<Received, Reply> behavior) {
        this.behavior = behavior;
    }

    public void respondAlways(int status) {
        respond(r -> new Status(status));
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void reset() {
        received.clear();
        calls.set(0);
        respondAlways(200);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received r = new Received(calls.incrementAndGet(), exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                exchange.getRequestHeaders().getFirst("Authorization"), body);
        received.add(r);
        Reply reply = behavior.apply(r);
        switch (reply) {
            case CloseConnection c -> exchange.close();
            case Delay d -> {
                try {
                    Thread.sleep(d.millis());   // simulates a slow consumer (exercises the relay's read timeout)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                send(exchange, d.code());
            }
            case Status s -> send(exchange, s.code());
        }
    }

    private static void send(HttpExchange exchange, int code) throws IOException {
        byte[] out = ("{\"outcome\":\"" + (code == 200 ? "PROCESSED" : "ERROR") + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
