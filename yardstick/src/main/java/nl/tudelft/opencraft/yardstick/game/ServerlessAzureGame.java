package nl.tudelft.opencraft.yardstick.game;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpResponse;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.HttpClientProvider;
import com.azure.core.http.ProxyOptions;
import lombok.Data;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;


public class ServerlessAzureGame implements GameArchitecture {

    private final Random random = new Random(System.currentTimeMillis());
    private final HttpClient httpClient;

    private final URI address;

    public ServerlessAzureGame(String address) {
       
        this.address =  address;

        Duration timeout = Duration.ofMinutes(15);
        this.httpClient = new NettyAsyncHttpClientBuilder()
                            .connectTimeout(timeout)
                            .build();
    }

    
    @Override
    public CompletableFuture<InetSocketAddress> getAddressForPlayer() {
        String id = String.valueOf(random.nextInt());
        NamingRequest namingRequest = new NamingRequest("servo/player:NAME" +
                "=" + id, Action.GET, Source.EXTERNAL);
        var request = HttpRequest.newBuilder(address)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(namingRequest)))
                .build();
        var retryPolicy = new RetryPolicy<NamingResponse>()
                .withMaxAttempts(-1)
                .withMaxDuration(Duration.ofMinutes(1))
                .withDelay(Duration.ofSeconds(3))
                .handleResultIf(r -> r.getStatus() != Status.RUN)
                .handleResultIf(r -> r.getHostname().isBlank());
        return Failsafe.with(retryPolicy).getAsync(() -> {
            HttpResponse<String> rawResponse;
            try {
                rawResponse = httpClient.send(httpClient, HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            return new Gson().fromJson(rawResponse.body(), NamingResponse.class);
        }).thenApply(n -> new InetSocketAddress(n.getHostname(), n.getPort()));
    }

    private enum Source {
        INTERNAL, EXTERNAL
    }

    private enum Action {
        GET, STOP
    }

    @Data
    private static class NamingRequest {
        private final String name;
        private final Action action;
        private final Source source;
    }

    private enum Status {
        START, RUN, STOP
    }

    @Data
    private static class NamingResponse {
        private final Status status;
        private final String hostname;
        private final int port;
    }
}