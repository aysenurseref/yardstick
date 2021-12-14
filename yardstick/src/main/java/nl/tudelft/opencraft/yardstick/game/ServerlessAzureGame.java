package nl.tudelft.opencraft.yardstick.game;

import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.HttpClientProvider;
import com.azure.core.http.ProxyOptions;



public class ServerlessAzureGame implements GameArchitecture {

    private final Random random = new Random(System.currentTimeMillis());

    private final String host;
    private final int port;

    private final InetSocketAddress addr;

    public ServerlessAzureGame(String host, int port) {
        this.host = host;
        this.port = port;
        this.addr =  new InetSocketAddress(host, port);

        Duration timeout = Duration.ofMinutes(15);
        HttpClient httpClient = new NettyAsyncHttpClientBuilder()
                            .connectTimeout(timeout)
                            .proxy(new ProxyOptions(ProxyOptions.Type.HTTP, addr))
                            .build();
    }

    
    @Override
    public CompletableFuture<InetSocketAddress> getAddressForPlayer() {
        return CompletableFuture.completedFuture(addr);
    }
}