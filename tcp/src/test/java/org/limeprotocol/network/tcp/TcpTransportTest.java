package org.limeprotocol.network.tcp;

import org.junit.Ignore;
import org.junit.Test;
import org.limeprotocol.*;
import org.limeprotocol.client.ClientChannel;
import org.limeprotocol.client.ClientChannelImpl;
import org.limeprotocol.network.SessionChannel;
import org.limeprotocol.network.TraceWriter;
import org.limeprotocol.network.Transport;
import org.limeprotocol.security.Authentication;
import org.limeprotocol.security.GuestAuthentication;
import org.limeprotocol.serialization.EnvelopeSerializer;
import org.limeprotocol.serialization.JacksonEnvelopeSerializer;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TcpTransportTest {

    private EnvelopeSerializer envelopeSerializer;
    private TcpClient tcpClient;
    private TraceWriter traceWriter;

    private boolean outputStreamFlushed;

    private class MockTcpClientFactory implements TcpClientFactory {

        @Override
        public TcpClient create() {
            return TcpTransportTest.this.tcpClient;
        }
    }

    private TcpTransport getTarget() throws IOException {
        return getTarget(mock(InputStream.class), mock(OutputStream.class));
    }

    private TcpTransport getTarget(InputStream inputStream, OutputStream outputStream) throws IOException {
        return getTarget(inputStream, outputStream, TcpTransport.DEFAULT_BUFFER_SIZE);
    }

    private TcpTransport getTarget(InputStream inputStream, OutputStream outputStream, int bufferSize) throws IOException {
        envelopeSerializer = mock(EnvelopeSerializer.class);
        tcpClient = mock(TcpClient.class);
        when(tcpClient.getOutputStream()).thenReturn(outputStream);
        when(tcpClient.getInputStream()).thenReturn(inputStream);
        when(tcpClient.isOutputShutdown()).thenReturn(false);
        when(tcpClient.isInputShutdown()).thenReturn(false);
        traceWriter = mock(TraceWriter.class);
        return new TcpTransport(envelopeSerializer, new MockTcpClientFactory(), traceWriter, bufferSize);
    }

    private TcpTransport getAndOpenTarget() throws IOException, URISyntaxException {
        return getAndOpenTarget(mock(InputStream.class), mock(OutputStream.class));
    }

    private TcpTransport getAndOpenTarget(InputStream inputStream, OutputStream outputStream) throws IOException, URISyntaxException {
        TcpTransport target = getTarget(inputStream, outputStream);
        target.open(Dummy.createUri());
        return target;
    }

    @Test
    public void open_notConnectedValidUri_callsConnectsAndGetStreams() throws URISyntaxException, IOException {
        // Arrange
        URI uri = Dummy.createUri();
        TcpTransport target = getTarget();

        // Act
        target.open(uri);

        // Assert
        verify(tcpClient, times(1)).connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
        verify(tcpClient, times(1)).getInputStream();
        verify(tcpClient, times(1)).getOutputStream();
    }

    @Test(expected = IllegalArgumentException.class)
    public void open_notConnectedInvalidUriScheme_throwsIllegalArgumentException() throws URISyntaxException, IOException {
        // Arrange
        URI uri = Dummy.createUri("http", 55321);
        TcpTransport target = getTarget();

        // Act
        target.open(uri);
    }

    @Test(expected = IllegalStateException.class)
    public void open_alreadyConnected_throwsIllegalStateException() throws URISyntaxException, IOException {
        // Arrange
        URI uri = Dummy.createUri("net.tcp", 55321);
        TcpTransport target = getAndOpenTarget();

        // Act
        target.open(uri);
    }

    @Test
    public void send_validArgumentsAndOpenStreamAndTraceEnabled_callsWriteAndTraces() throws IOException, URISyntaxException {
        // Arrange
        final boolean[] outputStreamFlushed = {false};
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                super.flush();
                outputStreamFlushed[0] = true;
            }
        };
        TcpTransport target = getAndOpenTarget(new ByteArrayInputStream(new byte[0]), outputStream);

        Envelope envelope = mock(Envelope.class);
        String serializedEnvelope = Dummy.createRandomString(200);
        when(envelopeSerializer.serialize(envelope)).thenReturn(serializedEnvelope);
        when(traceWriter.isEnabled()).thenReturn(true);

        // Act
        target.send(envelope);

        // Assert
        assertEquals(serializedEnvelope, outputStream.toString());
        assertTrue(outputStreamFlushed[0]);
        verify(traceWriter, atLeastOnce()).trace(serializedEnvelope, TraceWriter.DataOperation.SEND);
    }

    @Test(expected = IllegalArgumentException.class)
    public void send_nullEnvelope_throwsIllegalArgumentException() throws IOException, URISyntaxException {
        // Arrange
        TcpTransport target = getAndOpenTarget();
        Envelope envelope = null;

        // Act
        target.send(envelope);
    }

    @Test(expected = IllegalStateException.class)
    public void send_closedTransport_throwsIllegalStateException() throws IOException, URISyntaxException {
        // Arrange
        TcpTransport target = getTarget();
        Envelope envelope = mock(Envelope.class);

        // Act
        target.send(envelope);
    }

    @Test
    @Ignore
    public void send_realTcpClient_receivesEnvelope() throws URISyntaxException, IOException, InterruptedException {
        TcpTransport transport = new TcpTransport(
                new JacksonEnvelopeSerializer(),
                new SocketTcpClientFactory(new CustomTrustManager(null)),
                new TraceWriter() {
                    @Override
                    public void trace(String data, DataOperation operation) {
                        System.out.printf("%s: %s", operation.toString(), data);
                        System.out.println();
                    }

                    @Override
                    public boolean isEnabled() {
                        return true;
                    }
                });

        transport.open(new URI("net.tcp://takenet-iris.cloudapp.net:55321"));

        ClientChannel clientChannel = new ClientChannelImpl(transport, true);
        final Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();

        final Session[] receivedSession = {null};
        SessionChannel.SessionChannelListener sessionChannelListener = new SessionChannel.SessionChannelListener() {
            @Override
            public void onReceiveSession(Session session) {
                receivedSession[0] = session;
                semaphore.release();
            }
        };

        clientChannel.startNewSession(sessionChannelListener);

        int timeout = 100000;
        if (semaphore.tryAcquire(1, timeout, TimeUnit.MILLISECONDS) &&
                receivedSession[0] != null) {
            if (receivedSession[0].getState() == Session.SessionState.NEGOTIATING) {
                receivedSession[0] = null;
                clientChannel.negotiateSession(SessionCompression.NONE, SessionEncryption.TLS, sessionChannelListener);
                if (semaphore.tryAcquire(1, timeout, TimeUnit.MILLISECONDS) &&
                        receivedSession[0] != null) {
                    if (receivedSession[0].getState() == Session.SessionState.NEGOTIATING) {
                        clientChannel.getTransport().setEncryption(SessionEncryption.TLS);
                        receivedSession[0] = null;
                        clientChannel.enqueueSessionListener(sessionChannelListener);
                        if (semaphore.tryAcquire(1, timeout, TimeUnit.MILLISECONDS) &&
                                receivedSession[0] != null) {
                            if (receivedSession[0].getState() == Session.SessionState.AUTHENTICATING) {
                                Identity identity = new Identity(UUID.randomUUID().toString(), "take.io");
                                Authentication authentication = new GuestAuthentication();
                                receivedSession[0] = null;
                                clientChannel.authenticateSession(identity, authentication, "default", sessionChannelListener);
                                if (semaphore.tryAcquire(1, timeout, TimeUnit.MILLISECONDS) &&
                                        receivedSession[0] != null) {
                                    if (receivedSession[0].getState() == Session.SessionState.ESTABLISHED) {
                                        System.out.printf("Session established - Id: %s - Remote node: %s - Local node: %s", clientChannel.getSessionId(), clientChannel.getRemoteNode(), clientChannel.getLocalNode());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        assertNotNull(receivedSession[0]);
    }

    @Test
    @Ignore
    public void establishSession_WithTls_ReceivesEstablishedSession() throws URISyntaxException, IOException, InterruptedException {
        TcpTransport transport = new TcpTransport(
                new JacksonEnvelopeSerializer(),
                new SocketTcpClientFactory(),
                new TraceWriter() {
                    @Override
                    public void trace(String data, DataOperation operation) {
                        System.out.printf("%s: %s", operation.toString(), data);
                        System.out.println();
                    }

                    @Override
                    public boolean isEnabled() {
                        return true;
                    }
                });

        transport.open(new URI("net.tcp://takenet-iris.cloudapp.net:55321"));

        ClientChannel clientChannel = new ClientChannelImpl(transport, true);

        final Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();

        clientChannel.establishSession(SessionCompression.NONE, SessionEncryption.NONE,
                new Identity(UUID.randomUUID().toString(), "take.io"), new GuestAuthentication(), "default",
                new ClientChannel.EstablishSessionListener() {
                    @Override
                    public void onReceiveSession(Session session) {
                        assertNotNull(session);
                        assertEquals(Session.SessionState.ESTABLISHED, session.getState());
                        semaphore.release();
                    }

                    @Override
                    public void onFailure(Exception exception) {
                        fail(exception.getMessage());
                        semaphore.release();
                    }
                });

        if (!semaphore.tryAcquire(1, 40, TimeUnit.SECONDS)) {
            fail("Timeout establishing session");
        }
    }

    @Test
    public void onReceive_oneRead_readEnvelopeJsonFromStream() throws IOException, URISyntaxException, InterruptedException {
        // Arrange
        String messageJson = Dummy.createMessageJson();
        TestInputStream inputStream = new TestInputStream(new byte[][]{messageJson.getBytes("UTF-8")});
        TcpTransport target = getTarget(inputStream, new ByteArrayOutputStream());
        Envelope envelope = mock(Envelope.class);
        when(envelopeSerializer.deserialize(messageJson)).thenReturn(envelope);
        Transport.TransportEnvelopeListener transportEnvelopeListener = mock(Transport.TransportEnvelopeListener.class);
        target.setEnvelopeListener(transportEnvelopeListener);
        Transport.TransportStateListener transportStateListener = mock(Transport.TransportStateListener.class);
        target.setStateListener(transportStateListener);

        // Act
        target.open(Dummy.createUri());
        Thread.sleep(100);

        // Assert
        verify(transportEnvelopeListener, times(1)).onReceive(envelope);
        verify(transportStateListener, never()).onException(any(Exception.class));
    }

    @Test
    public void onReceive_multipleReads_readEnvelopeJsonFromStream() throws IOException, URISyntaxException, InterruptedException {
        // Arrange
        String messageJson = Dummy.createMessageJson();
        byte[] messageBuffer = messageJson.getBytes("UTF-8");
        Envelope envelope = mock(Envelope.class);
        byte[][] messageBufferParts = splitBuffer(messageBuffer);
        int bufferSize = messageBuffer.length + Dummy.createRandomInt(1000);
        TestInputStream inputStream = new TestInputStream(messageBufferParts);
        TcpTransport target = getTarget(inputStream, new ByteArrayOutputStream(), bufferSize);
        when(envelopeSerializer.deserialize(messageJson)).thenReturn(envelope);
        Transport.TransportEnvelopeListener transportEnvelopeListener = mock(Transport.TransportEnvelopeListener.class);
        target.setEnvelopeListener(transportEnvelopeListener);
        Transport.TransportStateListener transportStateListener = mock(Transport.TransportStateListener.class);
        target.setStateListener(transportStateListener);

        // Act
        target.open(Dummy.createUri());
        Thread.sleep(500);

        // Assert
        verify(transportEnvelopeListener, times(1)).onReceive(envelope);
        verify(transportStateListener, never()).onException(any(Exception.class));
        assertEquals(messageBufferParts.length, inputStream.getReadCount());
    }

    @Test
    public void onReceive_multipleReadsMultipleEnvelopes_readEnvelopesJsonFromStream() throws IOException, URISyntaxException, InterruptedException {
        // Arrange
        final int messagesCount = Dummy.createRandomInt(100) + 1;
        final Queue<String> messageJsonQueue = new LinkedBlockingQueue<>();
        StringBuilder messagesJsonBuilder = new StringBuilder();
        for (int i = 0; i < messagesCount; i++) {
            String messageJson;
            do {
                messageJson = Dummy.createMessageJson();
            } while (messageJsonQueue.contains(messageJson));
            messageJsonQueue.add(messageJson);
            messagesJsonBuilder.append(messageJson);
        }
        String messagesJson = messagesJsonBuilder.toString();
        byte[] messageBuffer = messagesJson.getBytes("UTF-8");
        byte[][] messageBufferParts = splitBuffer(messageBuffer);
        int bufferSize = messageBuffer.length + Dummy.createRandomInt(1000);
        TestInputStream inputStream = new TestInputStream(messageBufferParts);
        TcpTransport target = getTarget(inputStream, new ByteArrayOutputStream(), bufferSize);
        when(envelopeSerializer.deserialize(anyString())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                if (messageJsonQueue.peek().equals(invocationOnMock.getArguments()[0])) {
                    messageJsonQueue.remove();
                    return mock(Envelope.class);
                }
                return null;
            }
        });
        Transport.TransportEnvelopeListener transportEnvelopeListener = mock(Transport.TransportEnvelopeListener.class);
        target.setEnvelopeListener(transportEnvelopeListener);
        Transport.TransportStateListener transportStateListener = mock(Transport.TransportStateListener.class);
        target.setStateListener(transportStateListener);
        final Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();
        doAnswer(new Answer() {
            int receivedMessages = 0;

            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                receivedMessages++;
                if (receivedMessages == messagesCount) {
                    synchronized (semaphore) {
                        semaphore.release();
                    }
                }
                return null;
            }
        }).when(transportEnvelopeListener).onReceive(any(Envelope.class));

        // Act
        target.open(Dummy.createUri());
        synchronized (semaphore) {
            semaphore.tryAcquire(1, 1000, TimeUnit.MILLISECONDS);
        }

        // Assert
        verify(transportEnvelopeListener, times(messagesCount)).onReceive(any(Envelope.class));
        verify(transportStateListener, never()).onException(any(Exception.class));
        assertEquals(messageBufferParts.length, inputStream.getReadCount());
        assertTrue(messageJsonQueue.isEmpty());
    }


    @Test
    public void onReceive_multipleReadsWithRemovedListener_readSingleEnvelopeJsonFromStream() throws IOException, URISyntaxException, InterruptedException {
        // Arrange
        final int messagesCount = Dummy.createRandomInt(100) + 1;
        final Queue<String> messageJsonQueue = new LinkedBlockingQueue<>();
        StringBuilder messagesJsonBuilder = new StringBuilder();
        for (int i = 0; i < messagesCount; i++) {
            String messageJson;
            do {
                messageJson = Dummy.createMessageJson();
            } while (messageJsonQueue.contains(messageJson));
            messageJsonQueue.add(messageJson);
            messagesJsonBuilder.append(messageJson);
        }
        String messagesJson = messagesJsonBuilder.toString();
        byte[] messageBuffer = messagesJson.getBytes("UTF-8");
        byte[][] messageBufferParts = splitBuffer(messageBuffer);
        int bufferSize = messageBuffer.length + Dummy.createRandomInt(1000);
        TestInputStream inputStream = new TestInputStream(messageBufferParts);
        final TcpTransport target = getTarget(inputStream, new ByteArrayOutputStream(), bufferSize);
        when(envelopeSerializer.deserialize(anyString())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                if (messageJsonQueue.peek().equals(invocationOnMock.getArguments()[0])) {
                    messageJsonQueue.remove();
                    return mock(Envelope.class);
                }
                return null;
            }
        });
        Transport.TransportEnvelopeListener transportEnvelopeListener = mock(Transport.TransportEnvelopeListener.class);
        target.setEnvelopeListener(transportEnvelopeListener);
        Transport.TransportStateListener transportStateListener = mock(Transport.TransportStateListener.class);
        target.setStateListener(transportStateListener);
        final Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                target.setEnvelopeListener(null);
                semaphore.release();
                return null;
            }
        }).when(transportEnvelopeListener).onReceive(any(Envelope.class));

        // Act
        target.open(Dummy.createUri());
        synchronized (semaphore) {
            semaphore.tryAcquire(1, 1000, TimeUnit.MILLISECONDS);
        }

        // Assert
        verify(transportEnvelopeListener, times(1)).onReceive(any(Envelope.class));
        verify(transportStateListener, never()).onException(any(Exception.class));
        assertEquals(messagesCount - 1, messageJsonQueue.size());
    }

    @Test
    public void onReceive_multipleReadsMultipleEnvelopesWithInvalidCharsBetween_readEnvelopesJsonFromStream() throws IOException, URISyntaxException, InterruptedException {
        // Arrange
        final int messagesCount = Dummy.createRandomInt(100) + 1;
        final Queue<String> messageJsonQueue = new LinkedBlockingQueue<>();
        StringBuilder messagesJsonBuilder = new StringBuilder();
        messagesJsonBuilder.append("  \t\t ");
        for (int i = 0; i < messagesCount; i++) {
            String messageJson;
            do {
                messageJson = Dummy.createMessageJson();
            } while (messageJsonQueue.contains(messageJson));
            messageJsonQueue.add(messageJson);
            if (i != messagesCount - 1) {
                if (i % 3 == 0) messagesJsonBuilder.append("\r\n   ");
            }
            messagesJsonBuilder.append(messageJson);
            if (i != messagesCount - 1) {
                if (i % 2 == 0) messagesJsonBuilder.append("\r\n");
                if (i % 5 == 0) messagesJsonBuilder.append(" ");
            }
        }
        String messagesJson = messagesJsonBuilder.toString();
        byte[] messageBuffer = messagesJson.getBytes("UTF-8");
        byte[][] messageBufferParts = splitBuffer(messageBuffer);
        int bufferSize = messageBuffer.length + Dummy.createRandomInt(1000);
        TestInputStream inputStream = new TestInputStream(messageBufferParts);
        TcpTransport target = getTarget(inputStream, new ByteArrayOutputStream(), bufferSize);
        when(envelopeSerializer.deserialize(anyString())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                if (messageJsonQueue.peek().equals(invocationOnMock.getArguments()[0])) {
                    messageJsonQueue.remove();
                    return mock(Envelope.class);
                }
                return null;
            }
        });
        Transport.TransportEnvelopeListener transportEnvelopeListener = mock(Transport.TransportEnvelopeListener.class);
        target.setEnvelopeListener(transportEnvelopeListener);
        Transport.TransportStateListener transportStateListener = mock(Transport.TransportStateListener.class);
        target.setStateListener(transportStateListener);
        final Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();
        doAnswer(new Answer() {
            int receivedMessages = 0;

            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                receivedMessages++;
                if (receivedMessages == messagesCount) {
                    synchronized (semaphore) {
                        semaphore.release();
                    }
                }
                return null;
            }
        }).when(transportEnvelopeListener).onReceive(any(Envelope.class));

        // Act
        target.open(Dummy.createUri());
        synchronized (semaphore) {
            semaphore.tryAcquire(1, 1000, TimeUnit.MILLISECONDS);
        }

        // Assert
        verify(transportEnvelopeListener, times(messagesCount)).onReceive(any(Envelope.class));
        verify(transportStateListener, never()).onException(any(Exception.class));
        assertEquals(messageBufferParts.length, inputStream.getReadCount());
        assertTrue(messageJsonQueue.isEmpty());
    }

    @Test
    public void onReceive_multipleReadsBiggerThenBuffer_closesTheTransportAndCallsOnException() throws IOException, URISyntaxException, InterruptedException {
        // Arrange
        String messageJson = Dummy.createMessageJson();
        byte[] messageBuffer = messageJson.getBytes("UTF-8");
        byte[][] messageBufferParts = splitBuffer(messageBuffer);
        int bufferSize = messageBuffer.length - 1;
        TestInputStream inputStream = new TestInputStream(messageBufferParts);
        TcpTransport target = getTarget(inputStream, new ByteArrayOutputStream(), bufferSize);
        when(envelopeSerializer.deserialize(anyString())).thenReturn(mock(Envelope.class));
        Transport.TransportEnvelopeListener transportEnvelopeListener = mock(Transport.TransportEnvelopeListener.class);
        target.setEnvelopeListener(transportEnvelopeListener);
        Transport.TransportStateListener transportStateListener = mock(Transport.TransportStateListener.class);
        target.setStateListener(transportStateListener);

        // Act
        target.open(Dummy.createUri());
        Thread.sleep(100);

        // Assert
        verify(transportStateListener, times(1)).onException(any(BufferOverflowException.class));
        verify(tcpClient, times(1)).close();
    }

    @Test
    public void performCloseAsync_streamOpened_closesClient() throws IOException, URISyntaxException {
        // Arrange
        TcpTransport target = getAndOpenTarget();
        Transport.TransportEnvelopeListener transportEnvelopeListener = mock(Transport.TransportEnvelopeListener.class);
        target.setEnvelopeListener(transportEnvelopeListener);
        Transport.TransportStateListener transportStateListener = mock(Transport.TransportStateListener.class);
        target.setStateListener(transportStateListener);

        // Act
        target.close();

        // Assert
        verify(tcpClient, times(1)).close();
        verify(transportStateListener, never()).onException(any(Exception.class));
        verify(transportStateListener, times(1)).onClosing();
        verify(transportStateListener, times(1)).onClosed();
    }

    @Test
    public void getSupportedEncryption_default_returnsNoneAndTLS() throws IOException {
        // Arrange
        TcpTransport target = getTarget();

        // Act
        SessionEncryption[] actual = target.getSupportedEncryption();

        // Assert
        assertEquals(2, actual.length);
        assertTrue(Arrays.asList(actual).contains(SessionEncryption.NONE));
        assertTrue(Arrays.asList(actual).contains(SessionEncryption.TLS));
    }

    @Test
    public void setEncryption_setTls_callsStartTls() throws IOException, URISyntaxException {
        // Arrange
        TcpTransport target = getAndOpenTarget();

        // Act
        target.setEncryption(SessionEncryption.TLS);

        // Assert
        verify(tcpClient, times(1)).startTls();
    }


    private byte[][] splitBuffer(byte[] messageBuffer) {
        int bufferParts = Dummy.createRandomInt(25) + 1;

        byte[][] messageBufferParts = new byte[bufferParts][];
        int bufferPartSize = messageBuffer.length / bufferParts;
        for (int i = 0; i < bufferParts; i++) {
            if (i + 1 == bufferParts) {
                messageBufferParts[i] = new byte[messageBuffer.length - i * bufferPartSize];
            } else {
                messageBufferParts[i] = new byte[bufferPartSize];
            }
            System.arraycopy(messageBuffer, i * bufferPartSize, messageBufferParts[i], 0, messageBufferParts[i].length);
        }
        return messageBufferParts;
    }

    private class TestInputStream extends InputStream {

        private final byte[][] buffers;
        private byte[] currentBuffer;
        private int readCount;
        private int position;

        public TestInputStream(byte[][] buffers) {
            this.buffers = buffers;
        }

        @Override
        public int read() throws IOException {
            return currentBuffer[position++];
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (readCount >= buffers.length) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
                return 0;
            }
            currentBuffer = buffers[readCount];
            readCount++;

            System.arraycopy(currentBuffer, 0, b, off, currentBuffer.length > len ? len : currentBuffer.length);
            position += currentBuffer.length;
            return currentBuffer.length;
        }

        public int getReadCount() {
            return readCount;
        }
    }
}