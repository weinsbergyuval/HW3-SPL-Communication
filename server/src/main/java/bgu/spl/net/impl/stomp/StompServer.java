package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Server;

import java.util.function.Supplier;

public class StompServer {

    public static void main(String[] args) {

        if (args.length < 2) {
            System.out.println("Usage: <port> <tpc|reactor>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String serverType = args[1];

        // YA - STOMP protocol factory
        Supplier<MessagingProtocol<String>> protocolFactory = StompMessagingProtocolImpl::new;


        // YA - encoder/decoder factory
        Supplier<MessageEncoderDecoder<String>> encdecFactory = StompMessageEncoderDecoder::new;

        if (serverType.equals("tpc")) {

            Server.threadPerClient(
                    port,
                    protocolFactory,
                    encdecFactory
            ).serve();

        } else if (serverType.equals("reactor")) {

            int numThreads = Runtime.getRuntime().availableProcessors();

            Server.reactor(
                    numThreads,
                    port,
                    protocolFactory,
                    encdecFactory
            ).serve();

        } else {
            System.out.println("Unknown server type: " + serverType + " (use tpc or reactor)");
        }
    }
}
