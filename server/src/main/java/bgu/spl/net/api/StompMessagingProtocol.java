package bgu.spl.net.api;

import bgu.spl.net.srv.Connections;

public interface StompMessagingProtocol<T>  {
	/**
	 * Used to initiate the current client protocol with it's personal connection ID and the connections implementation
	**/
    void start(int connectionId, Connections<T> connections);
    
    void process(T message);
	
	/**
     * @return true if the connection should be terminated
     */
    boolean shouldTerminate();
}
//YA - the STOMP messaging protocol is in charge of:
//1. knows the state of the client - connected/disconnected to which channels
//2. processing a message that was received from a client
//3. determining whether the connection should be terminated