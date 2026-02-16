package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * YA - STOMP protocol implementation
 * YA - Implements MessagingProtocol<String> as required by the server API
 */
public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    // YA - unique id of this connection
    private int connectionId;

    // YA - shared connections object (used to send messages)
    private Connections<String> connections;

    // YA - subscriptionId -> destination
    private final Map<Integer, String> subscriptions = new ConcurrentHashMap<>();

    // YA - logged-in users (to prevent duplicate logins)
    private static final Set<String> loggedInUsers =
            ConcurrentHashMap.newKeySet();

    // YA - current user login
    private String login = null;

    // YA - connection state
    private boolean connected = false;
    private boolean shouldTerminate = false;

    // YA - global message-id counter
    private static final AtomicInteger messageIdCounter =
            new AtomicInteger(1);

    /**
     * YA - called once when protocol instance is created
     */
    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    /**
     * YA - process a single STOMP frame
     * YA - MUST return null (STOMP replies are sent manually)
     */
    @Override
    public String process(String message) {
        if (shouldTerminate) return null;

        message = message.replace("\0", "");
        String[] lines = message.split("\n");
        if (lines.length == 0) return null; // YA - if empty frame, ignore
        String command = lines[0];

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
                break;
        }

        return null; // YA - replies are sent via connections.send(...)
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    /* ===================== handlers ===================== */

    private void handleConnect(String[] lines, String originalFrame) {
        String passcode = null;
        login = null;

        if (connected) {
            sendError("Already connected", null, originalFrame);
            return;
        }

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("login:"))
                login = lines[i].substring("login:".length());
            else if (lines[i].startsWith("passcode:"))
                passcode = lines[i].substring("passcode:".length());
        }

        if (login == null || passcode == null) {
            sendError("Missing login or passcode", null, originalFrame);
            return;
        }

        if (!loggedInUsers.add(login)) { //YA - returns false if login already exists, adds login otherwise
            sendError("User already logged in", null, originalFrame);
            return;
        }

        connected = true;

        String response =
                "CONNECTED\n" +
                "version:1.2\n\n\0";

        connections.send(connectionId, response);
    }

    private void handleSubscribe(String[] lines, String originalFrame) {
        if (!connected) {
            sendError("Not connected", null, originalFrame);
            return;
        }

        String destination = null;
        Integer id = null;
        String receipt = null;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("destination:"))
                destination = lines[i].substring("destination:".length());
            else if (lines[i].startsWith("id:"))
                id = Integer.parseInt(lines[i].substring("id:".length()));
            else if (lines[i].startsWith("receipt:"))
                receipt = lines[i].substring("receipt:".length());
        }

        if (destination == null || id == null) {
            sendError("Missing headers in SUBSCRIBE", receipt, originalFrame);
            return;
        }

        if (subscriptions.containsKey(id)) {
            sendError("Subscription id already exists", receipt, originalFrame);
            return;
        }

        subscriptions.put(id, destination);

        connections.subscribe(connectionId, destination, id);

        if (receipt != null)
            sendReceipt(receipt);
    }

    private void handleUnsubscribe(String[] lines, String originalFrame) {
        if (!connected) {
            sendError("Not connected", null, originalFrame);
            return;
        }

        Integer id = null;
        String receipt = null;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("id:"))
                id = Integer.parseInt(lines[i].substring("id:".length()));
            else if (lines[i].startsWith("receipt:"))
                receipt = lines[i].substring("receipt:".length());
        }

        if (id == null) {
            sendError("Invalid subscription id", receipt, originalFrame);
            return;
        }

        String destination = subscriptions.remove(id); // YA - if no id destination == null

        if (destination == null) {
            sendError("Subscription does not exist", receipt, originalFrame);
            return;
        }

        connections.unsubscribeFromChannel(connectionId, destination);

        if (receipt != null)
            sendReceipt(receipt);
    }

    private void handleSend(String[] lines, String originalFrame) {
        if (!connected) {
            sendError("Not connected", null, originalFrame);
            return;
        }

        String destination = null;
        String receipt = null;
        int bodyStart = -1;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                bodyStart = i + 1;
                break;
            }
            if (lines[i].startsWith("destination:"))
                destination = lines[i].substring("destination:".length());
            else if (lines[i].startsWith("receipt:"))
                receipt = lines[i].substring("receipt:".length());
        }

        if (destination == null || bodyStart == -1) {
            sendError("Missing destination header", receipt, originalFrame);
            return;
        }

        if (connections.getSubscriptionId(connectionId, destination) == null) {
            sendError("User is not subscribed to destination", receipt, originalFrame);
            return;
        }

        StringBuilder body = new StringBuilder();
        for (int i = bodyStart; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1)
                body.append("\n");
        }

        for (Integer connId : connections.getSubscribers(destination)) {
            Integer subId = connections.getSubscriptionId(connId, destination);
            int msgId = messageIdCounter.getAndIncrement();

            String frame =
                    "MESSAGE\n" +
                    "subscription:" + subId + "\n" +
                    "message-id:" + msgId + "\n" +
                    "destination:" + destination + "\n\n" +
                    body + "\n\0";

            connections.send(connId, frame);
        }

        if (receipt != null)
            sendReceipt(receipt);
    }

    private void handleDisconnect(String[] lines) {
    String receipt = null;

    for (int i = 1; i < lines.length; i++) {
        if (lines[i].startsWith("receipt:"))
            receipt = lines[i].substring("receipt:".length());
    }

    if (receipt != null)
        sendReceipt(receipt);

    if (login != null)
        loggedInUsers.remove(login);

    // YA - disconnect from server connections
    connections.disconnect(connectionId);

    shouldTerminate = true;
}


    /* ===================== helpers ===================== */

    private void sendReceipt(String receiptId) {
        String frame =
                "RECEIPT\n" +
                "receipt-id:" + receiptId + "\n\n\0";

        connections.send(connectionId, frame);
    }

    private void sendError(String msg, String receipt, String originalFrame) {
        StringBuilder frame = new StringBuilder();
        frame.append("ERROR\n");
        frame.append("message:").append(msg).append("\n");

        if (receipt != null)
            frame.append("receipt-id:").append(receipt).append("\n");

        frame.append("\n");
        frame.append("The message:\n-----\n");
        frame.append(originalFrame).append("\n");
        frame.append("-----\n");
        frame.append(msg).append("\n\0");

        connections.send(connectionId, frame.toString());

        if (login != null)
            loggedInUsers.remove(login);

        shouldTerminate = true;
    }
}
