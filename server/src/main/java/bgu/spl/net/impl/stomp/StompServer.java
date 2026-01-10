package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;

/**
 * YA - Main class for STOMP server
 * YA - Responsible only for starting the server and wiring components together
 */
public class StompServer {

    public static void main(String[] args) {

        // YA - default port if not provided
        int port = 7777;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        // YA - number of worker threads for Reactor
        int numThreads = Runtime.getRuntime().availableProcessors();

        // YA - start STOMP server using Reactor pattern
        Server.reactor(
                numThreads,
                port,
                () -> new StompMessagingProtocolImpl(), // YA - create a new STOMP protocol per client
                StompMessageEncoderDecoder::new         // YA - STOMP frame encoder/decoder (\0 terminated)
        ).serve();
    }
}
