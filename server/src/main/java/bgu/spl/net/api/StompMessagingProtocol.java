package bgu.spl.net.api;

import bgu.spl.net.srv.Connections;

/**
 * YA - STOMP messaging protocol
 * YA - Extends the generic MessagingProtocol used by the server
 */
public interface StompMessagingProtocol<T> extends MessagingProtocol<T> {

    /**
     * Used to initiate the current client protocol with its personal connection ID
     * and the shared Connections implementation
     */
    void start(int connectionId, Connections<T> connections);
}
