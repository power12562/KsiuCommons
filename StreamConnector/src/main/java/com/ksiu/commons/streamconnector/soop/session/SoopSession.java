package com.ksiu.commons.streamconnector.soop.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.ksiu.commons.streamconnector.soop.session.interfaces.IDonationEvent;
import com.ksiu.commons.streamconnector.soop.token.SoopToken;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SoopSession
{
    private static final Map<String, CompletableFuture<SoopSession>> bjIdBySessionFuture = new ConcurrentHashMap<>();
    private final SoopWebSocketClient _socket;

    private SoopSession(SoopWebSocketClient socket)
    {
        _socket = socket;
    }

    public static SoopSession getSessionByToken(SoopToken token)
    {
        CompletableFuture<SoopSession> future = bjIdBySessionFuture.get(token.getBjId());
        try
        {
            return future.get();
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    public static CompletableFuture<SoopSession> createSession(SoopToken token)
    {
        String bjId = token.getBjId();
        CompletableFuture<SoopSession> returnFuture;
        synchronized (bjIdBySessionFuture)
        {
            returnFuture = bjIdBySessionFuture.get(bjId);
            if (returnFuture != null)
                return returnFuture;

            returnFuture = new CompletableFuture<>();
            bjIdBySessionFuture.put(bjId, returnFuture);
        }
        final SoopWebSocketClient socket = new SoopWebSocketClient(token, returnFuture);
        socket.connect();
        return returnFuture;
    }

    public void disconnect()
    {
        if (_socket.isConnect())
        {
            _socket.close();
        }
    }

    public void subscribeDonationEven(IDonationEvent donationEvent)
    {
        _socket.setDonationEvent(donationEvent);
    }

    public void unsubscribeTokenEvents()
    {
        _socket.setDonationEvent(null);
    }

    private static class SoopWebSocketClient extends WebSocketClient
    {
        private static final Logger logger = LoggerFactory.getLogger(SoopWebSocketClient.class);

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

        private static final int PACKET_INDEX_CMD = 0;
        private static final int CHAT_INDEX_MESSAGE = 1;   // 채팅 내용
        private static final int CHAT_INDEX_NICKNAME = 6;  // 실제 표시될 닉네임

        private static final int DONE_INDEX_NICKNAME = 3;  // 도네이터 닉네임
        private static final int DONE_INDEX_AMOUNT = 4;    // 별풍선 개수

        // 최초 연결시 전달하는 패킷, CONNECT_PACKET = f'{ESC}000100000600{F*3}16{F}'
        private static final String CONNECT_PACKET = createPacket(COMMAND_CONNECT, String.format("%s16%s", F.repeat(3), F));

        // CONNECT_PACKET 전송시 수신 하는 패킷, CONNECT_PACKET = f'{ESC}000100000700{F*3}16|0{F}'
        private static final String CONNECT_RES_PACKET = createPacket(COMMAND_CONNECT, String.format("%s16|0%s", F.repeat(2), F));

        // 주기적으로 핑을 보내서 메세지를 계속 수신하는 패킷, PING_PACKET = f'{ESC}000000000100{F}'
        private static final String PING_PACKET = createPacket(COMMAND_PING, F);

        private CompletableFuture<SoopSession> _sessionFuture;
        private final SoopToken _token;
        private final AtomicBoolean _isConnect = new AtomicBoolean(false);
        private Thread _pingThread;
        private final Cache<String, String[]> _donationCache;
        private IDonationEvent _donationEvent;

        public SoopWebSocketClient(SoopToken token, CompletableFuture<SoopSession> sessionFuture)
        {
            super(createSoopUri(token), DRAFT_6455);
            this._sessionFuture = sessionFuture;
            this._token = token;
            this.setConnectionLostTimeout(0);
            this.setSocketFactory(createSSLSocketFactory());
            this._donationCache = Caffeine.newBuilder()
                    .expireAfterWrite(1, TimeUnit.SECONDS)
                    .removalListener((String key, String[] messageList, RemovalCause cause) ->
                    {
                        if (cause.wasEvicted())
                        {
                            if (_donationEvent != null)
                            {
                                try
                                {
                                    String msg = "";
                                    String nickname = messageList[DONE_INDEX_NICKNAME];
                                    int payAmount = Integer.parseInt(messageList[DONE_INDEX_AMOUNT]) * 100;
                                    _donationEvent.execute(nickname, msg, payAmount);
                                }
                                catch (Exception e)
                                {
                                    logger.error("[SoopSession] 도네이션 처리 중 오류 {}", e.getMessage());
                                }
                            }
                        }
                    })
                    .build();
        }

        @Override
        public void onOpen(ServerHandshake handshakedata)
        {
            logger.info("[SoopSession] open");
            _isConnect.set(true);
            _pingThread = createPingThread();
            _pingThread.start();
            if (_sessionFuture != null && !_sessionFuture.isDone())
            {
                _sessionFuture.complete(new SoopSession(this));
                _sessionFuture = null;
            }
        }

        private Thread createPingThread()
        {
            Thread pingThread = new Thread(() ->
            {
                byte[] connectPacketBytes = CONNECT_PACKET.getBytes(StandardCharsets.UTF_8);
                send(connectPacketBytes);
                while (_isConnect.get())
                {
                    try
                    {
                        Thread.sleep(59996);
                        byte[] pingPacketBytes = PING_PACKET.getBytes(StandardCharsets.UTF_8);
                        send(pingPacketBytes);
                    }
                    catch (InterruptedException e)
                    {
                        _isConnect.set(false);
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
        public void onMessage(ByteBuffer bytes)
        {
            String message = new String(bytes.array(), StandardCharsets.UTF_8);

            if (CONNECT_RES_PACKET.equals(message))
            {
                final String CHATNO = _token.getChatNo();
                // 메세지를 내려받기 위해 보내는 패킷, JOIN_PACKET = f'{ESC}0002{calculate_byte_size(CHATNO):06}00{F}{CHATNO}{F*5}'
                final String JOIN_PACKET = createPacket(COMMAND_JOIN, String.format("%s%s%s", F, CHATNO, F.repeat(5)));
                final byte[] joinPacketBytes = JOIN_PACKET.getBytes(StandardCharsets.UTF_8);
                send(joinPacketBytes);
                return;
            }

            try
            {
                final String[] messageList = message.replace(ESC, "").split(F);
                final String cmd = messageList[PACKET_INDEX_CMD].substring(0, 4);
                logger.info("[SoopSession] cmd: {}", cmd);

                switch (cmd)
                {
                    case COMMAND_PING:
                    case COMMAND_CONNECT:
                    case COMMAND_JOIN:
                    case COMMAND_ENTER:
                    case COMMAND_ENTER_FAN:
                        return;
                }

                if (cmd.equals(COMMAND_DONE))
                {
                    logger.info("[SoopSession] donation: {}", Arrays.toString(messageList));
                    String nickName = messageList[DONE_INDEX_NICKNAME];
                    _donationCache.put(nickName, messageList);
                }
                else if (cmd.equals(COMMAND_CHAT))
                {
                    logger.info("[SoopSession] chat: {}", Arrays.toString(messageList));
                    // 채팅 패킷 : [cmd, msg, ?, ?, ?, ?, nickName, ?, ?, ?, ?, ?, ?, ?, ?]
                    String nickName = messageList[6]; // 닉네임
                    String[] doneMessage = _donationCache.getIfPresent(nickName);

                    if (doneMessage != null)
                    {
                        // 도네이션 채팅
                        _donationCache.invalidate(nickName);
                        String msg = messageList[CHAT_INDEX_MESSAGE]; // 채팅 내용
                        int payAmount = Integer.parseInt(doneMessage[DONE_INDEX_AMOUNT]) * 100; // 후원 가격
                        if (_donationEvent != null)
                        {
                            _donationEvent.execute(nickName, msg, payAmount);
                        }
                    }
                    else
                    {
                        //TODO: 채팅 이벤트 필요하면 나중에 추가.
                    }
                }
            }
            catch (Exception e)
            {
                logger.info("[SoopSession] Exception: {}", e.getMessage());
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote)
        {
            logger.info("[SoopSession] close");
            bjIdBySessionFuture.remove(_token.getBjId());
            _isConnect.set(false);
            if (_pingThread != null)
            {
                _pingThread.interrupt();
            }

            if (_sessionFuture != null && !_sessionFuture.isDone())
            {
                _sessionFuture.completeExceptionally(new RuntimeException("Connection closed: " + reason));
                _sessionFuture = null;
            }
        }

        @Override
        public void onError(Exception ex)
        {
            logger.error("[SoopSession] error");
            bjIdBySessionFuture.remove(_token.getBjId());
            if (_sessionFuture != null && !_sessionFuture.isDone())
            {
                _sessionFuture.completeExceptionally(ex);
                _sessionFuture = null;
            }
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

        public boolean isConnect()
        {
            return _isConnect.get();
        }

        public void setDonationEvent(IDonationEvent donationEvent)
        {
            _donationEvent = donationEvent;
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
