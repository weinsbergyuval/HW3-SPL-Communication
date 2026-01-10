package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * YA - MessageEncoderDecoder implementation for STOMP protocol
 * YA - STOMP frames are terminated by a NULL byte ('\0')
 */
public class StompMessageEncoderDecoder implements MessageEncoderDecoder<String> {

    // YA - buffer for accumulating incoming bytes until full STOMP frame is received
    private byte[] buffer = new byte[1024];
    private int len = 0;

    @Override
    public String decodeNextByte(byte nextByte) {
        // YA - STOMP frame ends when NULL byte is received
        if (nextByte == '\0') {
            String frame = new String(buffer, 0, len, StandardCharsets.UTF_8);
            len = 0; // YA - reset buffer for next frame
            return frame;
        }

        // YA - enlarge buffer if needed
        if (len >= buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }

        buffer[len++] = nextByte;
        return null; // YA - frame not complete yet
    }

    @Override
    public byte[] encode(String message) {
        // YA - message already contains '\0' if required by protocol
        return message.getBytes(StandardCharsets.UTF_8);
    }
}
