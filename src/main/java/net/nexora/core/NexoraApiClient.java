package net.nexora.core;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** No credentials, response bodies, or endpoint URLs are included in errors or logs. */
public final class NexoraApiClient implements AutoCloseable {
    private static final int LIMIT=65536;
    private final HttpClient client;
    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    public NexoraApiClient(String endpoint,String token,int connectTimeout,int timeout){
        URI uri;
        try {uri=URI.create(endpoint);}catch(RuntimeException e){throw new IllegalArgumentException("Ungültiger HTTPS-Endpunkt");}
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("HTTPS-Endpunkt ohne Zugangsdaten, Query oder Fragment erforderlich");
        if(token==null || token.isBlank() || token.equals("NEXORA_API_TOKEN") || token.contains("\r") || token.contains("\n"))
            throw new IllegalArgumentException("Echter API-Token fehlt (Umgebungsvariable NEXORA_API_TOKEN empfohlen)");
        this.endpoint=uri;this.token=token;this.timeout=Duration.ofSeconds(Math.clamp(timeout,1,30));
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.clamp(connectTimeout,1,15)))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public JsonObject send(JsonObject payload) throws IOException,InterruptedException {
        byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);
        if(body.length>LIMIT)throw new IOException("Anfrage zu groß");
        HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Content-Type","application/json").header("Accept","application/json")
            .header("X-Nexora-Token",token).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        // Byte subscriber bounds the body; unlike ofInputStream(), the request timeout covers body receipt.
        HttpResponse<byte[]> response=client.send(request,info->new LimitedBodySubscriber(LIMIT));
        if(response.statusCode()!=200)throw new IOException("API HTTP "+response.statusCode());
        return parse(response.body());
    }
    static JsonObject parse(byte[] bytes) throws IOException {
        if(bytes.length>LIMIT)throw new IOException("Antwort zu groß");
        try {
            JsonElement value=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));
            if(!value.isJsonObject())throw new IOException("JSON-Objekt erwartet");
            return value.getAsJsonObject();
        }catch(RuntimeException e){throw new IOException("Ungültige API-Antwort");}
    }
    static boolean ok(JsonObject response){
        JsonElement value=response.get("ok");
        return value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    static long coins(JsonObject response) throws IOException {
        try {
            if(!ok(response))throw new IOException("API abgelehnt");
            JsonPrimitive value=response.getAsJsonObject("data").getAsJsonPrimitive("coins");
            if(!value.isNumber() || !value.getAsString().matches("[0-9]{1,19}"))throw new IOException("Ganzzahl erwartet");
            return Long.parseLong(value.getAsString());
        }catch(RuntimeException e){throw new IOException("Ungültiger Coin-Wert");}
    }
    static String rank(JsonObject response) throws IOException {
        try {
            if(!ok(response))throw new IOException("API abgelehnt");
            JsonPrimitive value=response.getAsJsonObject("data").getAsJsonPrimitive("rank");
            if(!value.isString() || !value.getAsString().matches("[a-zA-Z0-9_-]{1,32}"))throw new IOException("Ungültiger Rang");
            return value.getAsString();
        }catch(RuntimeException e){throw new IOException("Ungültiger Rang");}
    }
    @Override public void close(){client.shutdownNow();}
}
