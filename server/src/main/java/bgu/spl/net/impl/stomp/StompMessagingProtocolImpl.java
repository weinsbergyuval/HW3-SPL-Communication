package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import java.util.HashMap;
import java.util.Map;


//YA - implementaion of STOMP messaging protocol Interface
//STOMP messaging protocol is in charge of:
//1. knows the state of the client - connected/disconnected to which channels
//2. processing a message that was received from a client
//3. determining whether the connection should be terminated


public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId; //YA - the unique ID of the connection
    private Connections<String> connections; //YA - so we can send messages to other clients
    private final Map<Integer, String> subscriptions = new HashMap<>(); //YA - subscriptionId -> channel

    private boolean connected = false; //YA - whether the client is connected
    private boolean shouldTerminate = false; //YA - whether the connection should be terminated

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(String message) { //YA - process a message received from the client
        String[] lines = message.split("\n");
        String command = lines[0]; //YA - first line is the command

        switch (command) {
            case "CONNECT":
                handleConnect(lines, message);
                break;

            case "SUBSCRIBE":
                handleSubscribe(lines, message);
                break;

            case "UNSUBSCRIBE":
                handleUnsubscribe(lines, message);
                break;

            case "SEND":
                handleSend(lines, message);
                break;

            case "DISCONNECT":
                handleDisconnect(lines);
                break;

            default:
                sendError("Unknown command", null, message);
                shouldTerminate = true;
        }
    }

    private void handleConnect(String[] lines, String originalFrame) { //YA - if already connected, send error and terminate
        if (connected) {
            sendError("Already connected", null, originalFrame);
            shouldTerminate = true;
            return;
        }

        
        connected = true;
        String response = "CONNECTED\nversion:1.2\n\n\0";
        connections.send(connectionId, response); //YA - send CONNECTED frame back to client
    }

    private void handleSubscribe(String[] lines, String originalFrame) { //YA - handle SUBSCRIBE command
        if (!connected) {
            sendError("Not connected", null, originalFrame);
            shouldTerminate = true;
            return;
        }

        String destination = null;
        Integer subscriptionId = null;
        String receiptId = null;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("destination:")) {
                destination = lines[i].substring("destination:".length()); //YA - get destination
            } else if (lines[i].startsWith("id:")) {
                subscriptionId = Integer.parseInt(lines[i].substring("id:".length())); //YA - get subscription id
            } else if (lines[i].startsWith("receipt:")) {
                receiptId = lines[i].substring("receipt:".length()); // YA - get receipt id if exists
            }
        }

        if (destination == null || subscriptionId == null) {
            sendError("Missing headers in SUBSCRIBE", receiptId, originalFrame);
            shouldTerminate = true;
            return;
        }

        //YA - store the subscription
        subscriptions.put(subscriptionId, destination);

        //YA - subscribe in ConnectionsImpl
        ((ConnectionsImpl<String>) connections)
                .subscribe(connectionId, destination, subscriptionId);

        if (receiptId != null)
            sendReceipt(receiptId); //YA - send RECEIPT frame if receipt id exists
    }

    private void handleUnsubscribe(String[] lines, String originalFrame) { //YA - handle UNSUBSCRIBE command
        Integer subscriptionId = null;
        String receiptId = null;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("id:")) {
                subscriptionId = Integer.parseInt(lines[i].substring("id:".length())); //YA - get subscription id
            } else if (lines[i].startsWith("receipt:")) {
                receiptId = lines[i].substring("receipt:".length()); // YA - get receipt id if exists
            }
        }

        if (subscriptionId == null || !subscriptions.containsKey(subscriptionId)) { //YA - invalid subscription id - error msg
            sendError("Invalid subscription id", receiptId, originalFrame);
            shouldTerminate = true;
            return;
        }

        String channel = subscriptions.remove(subscriptionId); //YA - remove subscription

        ((ConnectionsImpl<String>) connections)
                .unsubscribeFromChannel(connectionId, channel); //YA - unsubscribe in ConnectionsImpl

        if (receiptId != null)
            sendReceipt(receiptId);
    }

    private void handleSend(String[] lines, String originalFrame) { //YA - handle SEND command - build MESSAGE Frame 
        if (!connected) {
            sendError("Not connected", null, originalFrame);
            shouldTerminate = true;
            return;
        }

        String destination = null;
        String receiptId = null;
        int bodyStart = -1;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                bodyStart = i + 1;
                break;
            }
            if (lines[i].startsWith("destination:")) {
                destination = lines[i].substring("destination:".length()); //YA - get destination
            } else if (lines[i].startsWith("receipt:")) {
                receiptId = lines[i].substring("receipt:".length()); //YA - get receipt id if exists
            }
        }

        if (destination == null || bodyStart == -1) {
            sendError("Missing destination header", receiptId, originalFrame);
            shouldTerminate = true;
            return;
        }

        StringBuilder body = new StringBuilder();  //YA - extract body - after empty line
        for (int i = bodyStart; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1)
                body.append("\n");
        }
        //YA - build MESSAGE Frame
        String messageFrame =
                "MESSAGE\n" +
                "destination:" + destination + "\n\n" +
                body.toString() + "\n\0";

        connections.send(destination, messageFrame);

        if (receiptId != null)
            sendReceipt(receiptId);
    }

    private void handleDisconnect(String[] lines) { //YA - handle DISCONNECT command
        String receiptId = null;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("receipt:")) {
                receiptId = lines[i].substring("receipt:".length());
            }
        }

        if (receiptId != null)
            sendReceipt(receiptId);

        connections.disconnect(connectionId);
        shouldTerminate = true;
    }

    private void sendReceipt(String receiptId) { //YA - send RECEIPT frame
        String frame =
                "RECEIPT\n" +
                "receipt-id:" + receiptId + "\n\n\0";
        connections.send(connectionId, frame);
    }

    private void sendError(String shortMsg, String receiptId, String originalFrame) { //YA - send ERROR frame
        StringBuilder frame = new StringBuilder();
        frame.append("ERROR\n");
        frame.append("message:").append(shortMsg).append("\n");

        if (receiptId != null) {
            frame.append("receipt-id:").append(receiptId).append("\n");
        }

        frame.append("\n");
        frame.append("The message:\n-----\n");
        frame.append(originalFrame).append("\n");
        frame.append("-----\n");
        frame.append(shortMsg).append("\n\0");

        connections.send(connectionId, frame.toString());
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }
}
