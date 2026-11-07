package net.nexora.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class ApiTest {
    JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    @Test void rejectsInsecureEndpoints(){for(String endpoint:List.of("http://example.com/api","https://user:pass@example.com/api","https://example.com/api?token=x","https://example.com/#token","garbage"))
        assertThrows(IllegalArgumentException.class,()->new NexoraApiClient(endpoint,"test-only",1,1));}
    @Test void rejectsMissingAndPlaceholderSecrets(){
        for(String token:List.of(""," ","NEXORA_API_TOKEN","a\r\nb"))assertThrows(IllegalArgumentException.class,()->new NexoraApiClient("https://example.com/api",token,1,1));
    }
    @Test void parsesStrictCoinInteger() throws Exception {assertEquals(123,NexoraApiClient.coins(json("{\"ok\":true,\"data\":{\"coins\":123}}")));}
    @Test void rejectsFakeCoinValues(){for(String value:List.of("-1","1.5","\"123\"","true","9223372036854775808","null"))
        assertThrows(IOException.class,()->NexoraApiClient.coins(json("{\"ok\":true,\"data\":{\"coins\":"+value+"}}")));}
    @Test void rejectsStringOk(){assertFalse(NexoraApiClient.ok(json("{\"ok\":\"true\"}")));}
    @Test void rejectsHostileRank(){assertThrows(IOException.class,()->NexoraApiClient.rank(json("{\"ok\":true,\"data\":{\"rank\":\"&kop\"}}")));}
    @Test void parsesRealRankFormat() throws Exception {assertEquals("member",NexoraApiClient.rank(json("{\"ok\":true,\"data\":{\"rank\":\"member\"}}")));}
    @Test void invalidJsonRejected(){assertThrows(IOException.class,()->NexoraApiClient.parse("<html>error</html>".getBytes()));assertThrows(IOException.class,()->NexoraApiClient.parse("[]".getBytes()));}
    @Test void oversizedResponseRejected(){assertThrows(IOException.class,()->NexoraApiClient.parse(new byte[65537]));}
    @Test void bodySubscriberCancelsOversizedResponse(){
        boolean[] cancelled={false};LimitedBodySubscriber body=new LimitedBodySubscriber(4);
        body.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled[0]=true;}});
        body.onNext(List.of(ByteBuffer.wrap(new byte[5])));assertTrue(cancelled[0]);assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void bodySubscriberAccumulatesChunks(){
        LimitedBodySubscriber body=new LimitedBodySubscriber(4);
        body.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){}});
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));body.onComplete();assertArrayEquals(new byte[]{1,2,3,4},body.getBody().toCompletableFuture().join());
    }
}
