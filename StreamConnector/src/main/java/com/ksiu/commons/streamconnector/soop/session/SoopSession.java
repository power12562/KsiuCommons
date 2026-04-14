package com.ksiu.commons.streamconnector.soop.session;

import com.ksiu.commons.streamconnector.soop.token.SoopToken;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;

public class SoopSession
{
    private SoopSession(SoopToken token)
    {

    }

    public CompletableFuture<SoopSession> createSession


    private static class SoopWebSocketClient extends WebSocketClient
    {
        private static final Draft_6455 DRAFT_6455 = new Draft_6455();
        private static final String F = "\u000c";
        private static final String ESC = "\u001b\t";
        private static final String COMMAND_PING = "0000";
        private static final String COMMAND_CONNECT = "0001";
        private static final String COMMAND_JOIN = "0002";
        private static final String COMMAND_ENTER = "0004";
        private static final String COMMAND_ENTER_FAN = "0127"; // 0004직후 호출 됨, 입장한 유저의 열혈팬/팬 구분으로 추정
        private static final String COMMAND_CHAT = "0005";
        private static final String COMMAND_DONE = "0018";
        private static final String COMMNAD_C = "0110";
        private static final String COMMNAD_D = "0054";
        private static final String COMMNAD_E = "0090";
        private static final String COMMNAD_F = "0094";

        // 최초 연결시 전달하는 패킷, CONNECT_PACKET = f'{ESC}000100000600{F*3}16{F}'
        private static final String CONNECT_PACKET = createPacket(COMMAND_CONNECT, String.format("%s16%s", F.repeat(3), F));

        // CONNECT_PACKET 전송시 수신 하는 패킷, CONNECT_PACKET = f'{ESC}000100000700{F*3}16|0{F}'
        private static final String CONNECT_RES_PACKET = createPacket(COMMAND_CONNECT, String.format("%s16|0%s", F.repeat(2), F));

        // 주기적으로 핑을 보내서 메세지를 계속 수신하는 패킷, PING_PACKET = f'{ESC}000000000100{F}'
        private static final String PING_PACKET = createPacket(COMMAND_PING, F);

        private final SoopToken _token;
        private boolean _isConnect;
        private Thread _pingThread;

        public SoopWebSocketClient(SoopToken token)
        {
            super(createSoopUri(token), DRAFT_6455);
            this._token = token;
            this.setConnectionLostTimeout(0);
            this.setSocketFactory(createSSLSocketFactory());
            this.connect();
        }

        @Override
        public void onOpen(ServerHandshake handshakedata)
        {
            _isConnect = true;
            _pingThread = createPingThread();
            _pingThread.start();
        }

        private Thread createPingThread()
        {
            Thread pingThread = new Thread(() ->
            {
                byte[] connectPacketBytes = CONNECT_PACKET.getBytes(StandardCharsets.UTF_8);
                send(connectPacketBytes);
                while (_isConnect)
                {
                    try
                    {
                        Thread.sleep(59996);
                        byte[] pingPacketBytes = PING_PACKET.getBytes(StandardCharsets.UTF_8);
                        send(pingPacketBytes);
                    }
                    catch (InterruptedException e)
                    {
                        break;
                    }
                }
            });
            return pingThread;
        }

        @Override
        public void onMessage(String message)
        {

        }

        @Override
        public void onClose(int code, String reason, boolean remote)
        {
            _isConnect = false;
            if (_pingThread != null)
            {
                _pingThread.interrupt();
            }
        }

        @Override
        public void onError(Exception ex)
        {

        }

        private static URI createSoopUri(SoopToken token)
        {
            String uriString = String.format("wss://%s:%s/Websocket/%s",
                    token.getChatDomain().toLowerCase(),
                    token.getChatPort(),
                    token.getBjId());

            return URI.create(uriString);
        }

        private static String createPacket(String command, String data)
        {
            return String.format("%s%s%s%s", ESC, command, createLengthPacket(data), data);
        }

        private static String createLengthPacket(String data)
        {
            return String.format("%06d00", data.length());
        }

        private static SSLSocketFactory createSSLSocketFactory() throws RuntimeException
        {
            try
            {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{new SoopTrustManager()}, null);
                return sslContext.getSocketFactory();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        private static class SoopTrustManager implements X509TrustManager
        {
            @Override
            public void checkClientTrusted(X509Certificate[] certs, String authType)
            {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] certs, String authType)
            {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers()
            {
                return new X509Certificate[0];
            }
        }

    }
}
