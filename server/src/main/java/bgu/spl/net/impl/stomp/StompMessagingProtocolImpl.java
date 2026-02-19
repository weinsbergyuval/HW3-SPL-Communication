package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.Database;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.impl.data.LoginStatus;
import java.util.Map;
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

    // YA - static maps for user management (shared across all protocol instances)
    private final Database database = Database.getInstance();

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
    String username = null;
    String passcode = null;

    if (connected) {
        sendError("Already connected", null, originalFrame);
        return;
    }

    for (int i = 1; i < lines.length; i++) {
        if (lines[i].startsWith("login:"))
            username = lines[i].substring("login:".length());
        else if (lines[i].startsWith("passcode:"))
            passcode = lines[i].substring("passcode:".length());
    }

    if (username == null || passcode == null) {
        sendError("Missing login or passcode", null, originalFrame);
        return;
    }

    LoginStatus status = database.login(connectionId, username, passcode);

    switch (status) {
        case ADDED_NEW_USER:
        case LOGGED_IN_SUCCESSFULLY:
            this.login = username;
            this.connected = true;
            connections.send(connectionId,
                    "CONNECTED\nversion:1.2\n\n\0");
            break;

        case ALREADY_LOGGED_IN:
            sendError("User already logged in", null, originalFrame);
            break;

        case WRONG_PASSWORD:
            sendError("Wrong password", null, originalFrame);
            break;

        case CLIENT_ALREADY_CONNECTED:
            sendError("Client already connected", null, originalFrame);
            break;

        default:
            sendError("Login failed", null, originalFrame);
    }
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
    String filename = null;

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
        else if (lines[i].startsWith("file:"))
            filename = lines[i].substring("file:".length());
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

    // YA - track file upload ONCE per report
    if (filename != null && login != null) {
        database.trackFileUpload(login, filename, destination);
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

    database.logout(connectionId);

    subscriptions.clear();

    // YA - disconnect from server connections
    connections.disconnect(connectionId);

    shouldTerminate = true;
    connected = false;
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

        // YA - STOMP spec: ERROR must close the connection
        connections.disconnect(connectionId);
        shouldTerminate = true;
        connected = false;

    }
}
