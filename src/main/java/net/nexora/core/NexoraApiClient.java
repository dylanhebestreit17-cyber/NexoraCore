package net.nexora.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class NexoraApiClient {
    private final HttpClient client;
    private final URI endpoint;
    private final String token;
    private final int timeout;

    public NexoraApiClient(String endpoint, String token, int connectTimeout, int timeout) {
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("API-Endpunkt muss eine gültige HTTPS-Adresse sein");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("API-Token fehlt");
        this.endpoint = uri;
        this.token = token;
        this.timeout = Math.max(1, timeout);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeout)))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonObject send(JsonObject payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("X-Nexora-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("API HTTP " + response.statusCode());
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("API-Antwort ist kein JSON-Objekt", ex);
        }
    }
}
