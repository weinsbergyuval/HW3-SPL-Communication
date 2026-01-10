package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

public class Reactor<T> implements Server<T> {

    private final int port;

    // YA - factory for creating a new protocol instance per connection
    private final Supplier<MessagingProtocol<T>> protocolFactory;
    // YA - factory for creating a new encoder/decoder per connection
    private final Supplier<MessageEncoderDecoder<T>> readerFactory;
    private final ActorThreadPool pool;

    private Selector selector;
    private Thread selectorThread;

    // YA - queue of tasks that must run on the selector thread used to safely update interestOps from worker threads
    private final ConcurrentLinkedQueue<Runnable> selectorTasks = new ConcurrentLinkedQueue<>();

    public Reactor(
            int numThreads,
            int port,
            Supplier<MessagingProtocol<T>> protocolFactory,
            Supplier<MessageEncoderDecoder<T>> readerFactory) {

        this.pool = new ActorThreadPool(numThreads);
        this.port = port;
        this.protocolFactory = protocolFactory;
        this.readerFactory = readerFactory;
    }

    @Override
    public void serve() {
        // YA - the selector thread is the thread running serve()
        selectorThread = Thread.currentThread();

        try (Selector selector = Selector.open();
             ServerSocketChannel serverSock = ServerSocketChannel.open()) {

            this.selector = selector; // YA - saved for close()

            serverSock.bind(new InetSocketAddress(port));
            serverSock.configureBlocking(false);

            // YA - register server socket to accept new connections
            serverSock.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("Server started");

            while (!Thread.currentThread().isInterrupted()) {

                selector.select(); // YA - wait for I/O events
                runSelectionThreadTasks(); // YA - run pending selector updates

                for (SelectionKey key : selector.selectedKeys()) {

                    if (!key.isValid()) {
                        continue;
                    } else if (key.isAcceptable()) {
                        // YA - accept a new client connection
                        handleAccept(serverSock, selector);
                    } else {
                        // YA - handle read/write events for existing clients
                        handleReadWrite(key);
                    }
                }

                // YA - clear keys to prepare for next select()
                selector.selectedKeys().clear();
            }

        } catch (ClosedSelectorException ex) {
            // YA - normal shutdown path
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        System.out.println("server closed!!!");
        pool.shutdown();
    }

    /*package*/ void updateInterestedOps(SocketChannel chan, int ops) {
        // YA - interestOps must be updated from the selector thread only
        final SelectionKey key = chan.keyFor(selector);

        if (Thread.currentThread() == selectorThread) {
            key.interestOps(ops);
        } else {
            selectorTasks.add(() -> key.interestOps(ops));
            selector.wakeup();
        }
    }

    private void handleAccept(ServerSocketChannel serverChan, Selector selector) throws IOException {
        SocketChannel clientChan = serverChan.accept();
        clientChan.configureBlocking(false);

        // YA - create a NonBlockingConnectionHandler per client
        // YA - it holds:
        // YA - 1) STOMP protocol instance
        // YA - 2) STOMP encoder/decoder
        // YA - 3) the socket channel
        final NonBlockingConnectionHandler<T> handler =
                new NonBlockingConnectionHandler<>(
                        readerFactory.get(),      // YA - StompMessageEncoderDecoder
                        protocolFactory.get(),    // YA - StompMessagingProtocolImpl
                        clientChan,
                        this);

        // YA - register client channel for READ events
        clientChan.register(selector, SelectionKey.OP_READ, handler);
    }

    private void handleReadWrite(SelectionKey key) {
        @SuppressWarnings("unchecked")
        NonBlockingConnectionHandler<T> handler =
                (NonBlockingConnectionHandler<T>) key.attachment();

        if (key.isReadable()) {
            // YA - read bytes and possibly produce a STOMP frame
            Runnable task = handler.continueRead();

            // YA - if a full message was decoded, submit protocol processing
            if (task != null) {
                pool.submit(handler, task);
            }
        }

        if (key.isValid() && key.isWritable()) {
            // YA - flush pending outgoing messages
            handler.continueWrite();
        }
    }

    private void runSelectionThreadTasks() {
        // YA - execute selector updates requested by worker threads
        while (!selectorTasks.isEmpty()) {
            selectorTasks.remove().run();
        }
    }

    @Override
    public void close() throws IOException {
        selector.close();
    }
}
