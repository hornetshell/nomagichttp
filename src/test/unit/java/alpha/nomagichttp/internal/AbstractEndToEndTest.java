package alpha.nomagichttp.internal;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.Server;
import alpha.nomagichttp.ServerConfig;
import alpha.nomagichttp.message.Char;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static alpha.nomagichttp.handler.Handlers.noop;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.net.InetAddress.getLoopbackAddress;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract class to help facilitate end-to-end testing by using
 * <i>{@code writeReadXXX}</i>-provided methods.<p>
 * 
 * Any exchange taking 3 seconds or longer will be interrupted and consequently
 * fail the test.<p>
 * 
 * Note: This class provides low-level access for test cases that needs direct
 * control over what bytes are put on the wire and what is received. Test cases
 * that operate on a higher "HTTP exchange semantics kind of layer" should use a
 * client such as JDK's {@link HttpClient} instead.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractEndToEndTest
{
    private static final System.Logger LOG = System.getLogger(AbstractEndToEndTest.class.getPackageName());
    
    private static Server server;
    private static NetworkChannel listener;
    private static int port;
    private static volatile Throwable lastExc;
    private static ScheduledExecutorService scheduler;
    
    @BeforeAll
    static void startServer() throws IOException {
        Route route = new RouteBuilder("/")
                .handler(noop())
                .build();
        
        server = Server.with(ServerConfig.DEFAULT, Set.of(route), exc -> {
            lastExc = exc;
            // TODO: Default error handler should log
            LOG.log(ERROR, "Unhandled exception.", exc);
            return ExceptionHandler.DEFAULT.apply(exc);
        });
        
        listener = server.start();
        
        port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }
    
    @AfterAll
    static void stopServer() throws IOException, InterruptedException {
        if (listener != null) {
            // TODO: Use official Server.stop() instead
            listener.close();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler.awaitTermination(1, SECONDS);
        }
    }
    
    protected static Server server() {
        return server;
    }
    
    private SocketChannel client;
    
    @AfterEach
    void closeClient() throws IOException {
        if (client != null) {
            client.close();
            client = null;
        }
    }
    
    /**
     * Open a persistent connection for re-use throughout the test.<p>
     * 
     * This method should be called at the start of a test for connection
     * re-use by all subsequent {@code writeXXX()} calls. Not calling this
     * method will make the {@code writeXXX()} methods open a new connection
     * each time.
     */
    protected final void openConnection() throws IOException {
        if (client != null) {
            throw new IllegalStateException("Already opened.");
        }
        
        client = SocketChannel.open(
                new InetSocketAddress(getLoopbackAddress(), port));
    }
    
    protected final String writeReadText(String request, String responseEnd)
            throws IOException, InterruptedException
    {
        byte[] bytes = writeReadBytes(
                request.getBytes(US_ASCII),
                responseEnd.getBytes(US_ASCII));
        
        return new String(bytes, US_ASCII);
    }
    
    protected final byte[] writeReadBytes(byte[] request, byte[] responseEnd)
            throws IOException, InterruptedException
    {
        final Thread worker = Thread.currentThread();
        final AtomicBoolean communicating = new AtomicBoolean(true);
        
        ScheduledFuture<?> interrupt = scheduler.schedule(() -> {
            if (communicating.get()) {
                LOG.log(WARNING, "HTTP exchange took too long, will timeout.");
                worker.interrupt();
            }
        }, 3, SECONDS);
        
        final FiniteByteBufferSink sink = new FiniteByteBufferSink(128, responseEnd);
        final boolean persistent = client != null;
        
        try {
            if (!persistent) {
                openConnection();
            }
            
            int r = client.write(wrap(request));
            assertThat(r).isEqualTo(request.length);
            
            ByteBuffer buff = allocate(128);
            
            while (!sink.hasReachedEnd() && client.read(buff) != -1) {
                buff.flip();
                sink.write(buff);
                buff.clear();
                
                if (Thread.interrupted()) { // clear flag
                    throw new InterruptedException();
                }
            }
            
            return sink.toByteArray();
        } catch (Exception e) {
            sink.dumpToLog();
            throw e;
        }
        finally {
            communicating.set(false);
            interrupt.cancel(false);
            Thread.interrupted(); // clear flag
            
            if (!persistent) {
                closeClient();
            }
        }
    }
    
    private static class FiniteByteBufferSink {
        private final ByteArrayOutputStream delegate;
        private final byte[] eos;
        private int matched;
        
        FiniteByteBufferSink(int initialSize, byte[] endOfSink) {
            delegate = new ByteArrayOutputStream(initialSize);
            eos = endOfSink;
            matched = 0;
        }
        
        void write(ByteBuffer data) {
            if (hasReachedEnd()) {
                throw new IllegalStateException();
            }
            
            int start = data.arrayOffset() + data.position(),
                end   = start + data.remaining();
            
            for (int i = start; i < end; ++i) {
                byte b = data.array()[i];
                delegate.write(b);
                memorize(b);
                
                if (hasReachedEnd()) {
                    assertThat(i + 1 == end)
                            .as("Unexpected trailing bytes in response: " + dump(data.array(), i + 1, end))
                            .isTrue();
                }
            }
        }
        
        private void memorize(byte b) {
            if (b == eos[matched]) {
                ++matched;
            } else {
                matched = 0;
            }
        }
        
        boolean hasReachedEnd() {
            return matched == eos.length;
        }
        
        byte[] toByteArray() {
            return delegate.toByteArray();
        }
        
        void dumpToLog() {
            if (!LOG.isLoggable(WARNING)) {
                return;
            }
            
            byte[] b = delegate.toByteArray();
            Collection<String> chars = dump(b, 0, b.length);
            LOG.log(WARNING, "About to crash. We received this many bytes: " + chars.size() + ". Will log each as a char.");
            dump(b, 0, b.length).forEach(c -> LOG.log(WARNING, c));
        }
        
        private static Collection<String> dump(byte[] bytes, int start, int end) {
            List<String> l = new ArrayList<>();
            
            for (int i = start; i < end; ++i) {
                l.add(Char.toDebugString((char) bytes[i]));
            }
            
            return l;
        }
    }
}