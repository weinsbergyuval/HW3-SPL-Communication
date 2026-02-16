package bgu.spl.net.srv;

import java.io.IOException;

public interface Connections<T> {

    boolean send(int connectionId, T msg); //YA - sends message to one specific client

    void send(String channel, T msg); //YA - sends message to all clients that are subscribed to the channel

    void disconnect(int connectionId);

    // YA - subscription management helpers (for STOMP)
    void subscribe(int connectionId, String channel, int subscriptionId);
    void unsubscribeFromChannel(int connectionId, String channel);
    Integer getSubscriptionId(int connectionId, String channel);
    java.util.Set<Integer> getSubscribers(String channel);

}
