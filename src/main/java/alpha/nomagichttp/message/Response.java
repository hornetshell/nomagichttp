package alpha.nomagichttp.message;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Contains the status-line (HTTP-version, status-code and reason-phrase) as
 * well as headers and message body.<p>
 * 
 * The server will write the response to the client as-is with no inference.<p>
 * 
 * The first thing the server do when receiving the response object from the
 * request handler is to subscribe to the message body.<p>
 * 
 * Not before the body publisher issues a bytebuffer or completes the
 * subscription (whichever happens first) will the server call {@code
 * statusLine()} and {@code headers()} in order to get the response head. This
 * means that the implementation may internally write these values lazily, if
 * need be.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface Response
{
    /**
     * Returns the status-line, for example "HTTP/1.1 200 OK".<p>
     * 
     * The status-line will be sent to client verbatim.
     * 
     * @return the status-line
     */
    String statusLine();
    
    /**
     * Returns the headers.<p>
     * 
     * The header lines will be sent to client verbatim.
     * 
     * @return the headers
     */
    Iterable<String> headers();
    
    /**
     * Returns the message body.
     * 
     * @return the message body
     * 
     * @see Response
     */
    Flow.Publisher<ByteBuffer> body();
    
    /**
     * Returns this response object boxed in a completed stage.<p>
     * 
     * Useful for synchronous request handler implementations that are able to
     * build the response immediately.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     return CompletableFuture.completedStage(this);
     * }</pre>
     * 
     * @return this response object boxed in a completed stage
     */
    default CompletionStage<Response> asCompletedStage() {
        return CompletableFuture.completedStage(this);
    }
}