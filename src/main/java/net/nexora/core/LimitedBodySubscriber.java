package net.nexora.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedBodySubscriber(int limit){this.limit=limit;}
    public CompletionStage<byte[]> getBody(){return result;}
    public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
    public void onNext(List<ByteBuffer> buffers){
        for(ByteBuffer buffer:buffers){
            if(buffer.remaining()>limit-bytes.size()){
                subscription.cancel();result.completeExceptionally(new IOException("Antwort zu groß"));return;
            }
            byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
        }subscription.request(1);
    }
    public void onError(Throwable t){result.completeExceptionally(t);}
    public void onComplete(){result.complete(bytes.toByteArray());}
}
