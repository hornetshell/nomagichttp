package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.route.Route;

/**
 * Thrown by {@link Headers} when attempting to convert a String header value
 * into another Java type.<p>
 * 
 * When observed by a route-level exception handler, the {@link Route} and
 * {@link Request} arguments will be non-null, but the {@link Handler} argument
 * may or may not be null.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class BadHeaderException extends RuntimeException
{
    public BadHeaderException(String message) {
        super(message);
    }
    
    public BadHeaderException(String message, Throwable cause) {
        super(message, cause);
    }
}