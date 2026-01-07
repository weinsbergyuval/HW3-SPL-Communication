package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
// YA implementation of Connections interface 
// YA manages active connections and channel subscriptions
// YA supports sending messages to specific clients or broadcasting to channels
public class ConnectionsImpl<T> implements Connections<T> {
    
    // YA maps connectionId -> handler (send to specific client)
    private final Map<Integer, ConnectionHandler<T>> handlersById = new ConcurrentHashMap<>();

    // YA maps channel -> (connectionId -> subscriptionId)
    private final Map<String, Map<Integer, Integer>> channelSubs = new ConcurrentHashMap<>();

    // YA reverse map: connectionId -> subscribed channels
    private final Map<Integer, Set<String>> channelsByConnection = new ConcurrentHashMap<>();

    @Override
    public boolean send(int connectionId, T msg) {
        // YA send message to a single client
        ConnectionHandler<T> handler = handlersById.get(connectionId); // YA get handler
        if (handler == null)// YA no such connection-false
            return false;
        handler.send(msg);// YA send message
        return true;
    }

    @Override
    public void send(String channel, T msg) {
        // YA broadcast message to all subscribers of a channel
        Map<Integer, Integer> subs = channelSubs.get(channel);
        if (subs == null)
            return;

        for (Integer connId : subs.keySet()) { // YA send to each subscriber by loop of connectionId
            send(connId, msg);
        }
    }

    @Override
    public void disconnect(int connectionId) {
        // YA remove handler so no more messages are sent
        ConnectionHandler<T> handler = handlersById.remove(connectionId);

        // YA remove client from all subscribed channels
        //YA if after remove the channel has no subscribers, remove the channel 
        Set<String> channels = channelsByConnection.remove(connectionId);
        if (channels != null) { // YA for each subscribed channel
            for (String channel : channels) {
                Map<Integer, Integer> subs = channelSubs.get(channel);
                if (subs != null) {
                    subs.remove(connectionId);
                    if (subs.isEmpty())
                        channelSubs.remove(channel);
                }
            }
        }

        // YA close socket connection
        if (handler != null) {
            try {
                handler.close();
            } catch (Exception ignored) {}
        }
    }

    // -------- helper methods --------

    // YA register new active connection
    public void register(int connectionId, ConnectionHandler<T> handler) {
        handlersById.put(connectionId, handler);
        channelsByConnection.putIfAbsent(connectionId, ConcurrentHashMap.newKeySet());// YA put in the map only if absent with atomic operation
    }

    // YA subscribe client to channel with subscriptionId
    public void subscribe(int connectionId, String channel, int subscriptionId) {
        channelSubs.computeIfAbsent(channel, c -> new ConcurrentHashMap<>())// YA in STOMP we allow to subscribe to non-existing channels and create them
                   .put(connectionId, subscriptionId);

        channelsByConnection.get(connectionId).add(channel);//YA het the set of channels of client and add the new channel
    }

    // YA unsubscribe client from channel
    public void unsubscribeFromChannel(int connectionId, String channel) {
        Map<Integer, Integer> subs = channelSubs.get(channel);
        if (subs != null) {
            subs.remove(connectionId);// YA remove client from channel
            if (subs.isEmpty())// YA if no more subscribers, remove channel
                channelSubs.remove(channel);
        }
        channelsByConnection.get(connectionId).remove(channel);//YA remove channel from client's set
    }

    // YA get subscription id of client in channel
    public Integer getSubscriptionId(int connectionId, String channel) {
        Map<Integer, Integer> subs = channelSubs.get(channel);// YA get subscribers of channel
        return subs == null ? null : subs.get(connectionId);// YA return subscriptionId or null if not subscribed
    }

// YA get all subscribers of a channel
public Set<Integer> getSubscribers(String channel) {
    Map<Integer, Integer> subs = channelSubs.get(channel);
    if (subs == null)
        return java.util.Collections.emptySet(); // Java 8 safe
    return subs.keySet();
}


}
