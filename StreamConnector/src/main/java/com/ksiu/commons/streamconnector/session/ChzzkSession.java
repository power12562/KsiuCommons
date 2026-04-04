package com.ksiu.commons.streamconnector.session;

import com.ksiu.commons.streamconnector.authorizer.ChzzkAuthorizer;
import com.ksiu.commons.streamconnector.session.interfaces.IChatEvent;
import com.ksiu.commons.streamconnector.session.interfaces.IDisconnectEvent;
import com.ksiu.commons.streamconnector.session.interfaces.ISubscribeEvent;
import com.ksiu.commons.streamconnector.token.ChzzkToken;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public class ChzzkSession
{
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final int MAX_EVENT_SIZE = 10;
    private static final ConcurrentMap<String, ChzzkSession> channelIdBySession = new ConcurrentHashMap<>();

    public static ChzzkSession getSessionByToken(ChzzkToken token)
    {
        return channelIdBySession.get(token.getChannelId());
    }

    private final Socket _socket;
    private final String _sessionKey;
    private volatile boolean _isConnect = true;
    private ISubscribeEvent _subscribeEvent;
    private ISubscribeEvent _unsubscribeEvent;
    private ISubscribeEvent _revokedEvent;
    private final ConcurrentMap<String, IChatEvent> _channelIdByChatEvent = new ConcurrentHashMap<>();

    private static JSONObject argsToJson(Object[] args)
    {
        if (args.length == 0)
            return null;

        if (args[0] instanceof String rawJson)
            return new JSONObject(rawJson);

        if (args[0] instanceof JSONObject json)
            return json;

        return null;
    }

    private ChzzkSession(Socket socket, String sessionKey)
    {
        _socket = socket;
        _sessionKey = sessionKey;

        //TODO:각 이벤트별 on 호출 필요
        initSystemEvent();
        initChatEvent();
    }

    public boolean isConnect()
    {
        return _isConnect;
    }

    private void initSystemEvent()
    {
        _socket.on("SYSTEM", args ->
        {
            try
            {
                JSONObject body = argsToJson(args);
                if (body != null)
                {
                    String type = body.getString("type");
                    switch (type)
                    {
                        case "subscribed" ->
                        {
                            if (_subscribeEvent == null)
                                return;

                            JSONObject object = body.getJSONObject("data");
                            onSubscribed(object, _subscribeEvent);
                        }
                        case "unsubscribed" ->
                        {
                            if (_unsubscribeEvent == null)
                                return;

                            JSONObject object = body.getJSONObject("data");
                            String channelId = onSubscribed(object, _unsubscribeEvent);
                            _channelIdByChatEvent.remove(channelId);
                        }
                        case "revoked" ->
                        {
                            if (_revokedEvent == null)
                                return;

                            JSONObject object = body.getJSONObject("data");
                            String channelId = onSubscribed(object, _revokedEvent);
                            _channelIdByChatEvent.remove(channelId);
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                System.out.println("[ChzzkSession] 시스템 처리 에러: " + ex);
            }
        });
    }

    private static String onSubscribed(JSONObject object, ISubscribeEvent event) throws JSONException
    {
        String type = object.getString("eventType");
        String channelId = object.getString("channelId");
        event.execute(type, channelId);
        return channelId;
    }

    private void initChatEvent()
    {
        _socket.on("CHAT", args ->
        {
            try
            {
                JSONObject body = argsToJson(args);
                if (body != null)
                {
                    String channelId = body.getString("channelId");
                    IChatEvent event = _channelIdByChatEvent.get(channelId);
                    if (event != null)
                        event.execute(body);
                }
            }
            catch (Exception ex)
            {
                System.out.println("[ChzzkSession] 시스템 처리 에러: " + ex);
            }
        });
    }

    public void setDisconnectEvent(IDisconnectEvent onDisconnect)
    {
        _socket.off("disconnect");
        _socket.on("disconnect", args ->
        {
            _isConnect = false;
            if (onDisconnect != null)
            {
                onDisconnect.execute();
            }
        });
    }

    public static CompletableFuture<ChzzkSession> createSession(String clientId, String clientSecret)
    {
        CompletableFuture<ChzzkSession> future = new CompletableFuture<>();

        // 세션 소켓 url 요청
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/auth/client"))
                .header("Client-Id", clientId)
                .header("Client-Secret", clientSecret)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        CompletableFuture<HttpResponse<String>> responseFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        responseFuture.thenAccept(response ->
        {
            if (response.statusCode() != 200)
            {
                future.completeExceptionally(new RuntimeException("세션 URL 요청 실패: " + response.body()));
            }
            JSONObject jsonResponse = new JSONObject(response.body());
            String sessionURL = jsonResponse.getJSONObject("content").getString("url");

            // 소켓 연결
            IO.Options opts = new IO.Options();
            opts.transports = new String[]{"websocket"};
            opts.forceNew = true;
            opts.reconnection = true;
            opts.timeout = 5000;

            // 필수 이벤트
            try
            {
                Socket sessionSocket = IO.socket(sessionURL, opts);
                sessionSocket.connect();
                sessionSocket.once("disconnect", args ->
                {
                    if (args[0] instanceof String message)
                    {
                        future.completeExceptionally(new RuntimeException("세션 연결 실패: " + message));
                        return;
                    }
                    future.completeExceptionally(new RuntimeException("세션 연결 실패 접속이 종료되었습니다."));
                });
                sessionSocket.once("SYSTEM", args ->
                {
                    JSONObject messageBody = argsToJson(args);
                    if (messageBody == null)
                    {
                        sessionSocket.disconnect();
                        return;
                    }

                    try
                    {
                        if (!messageBody.getString("type").equals("connected"))
                        {
                            future.completeExceptionally(new RuntimeException("세션 연결 실패: " + messageBody));
                            return;
                        }
                        JSONObject data = messageBody.getJSONObject("data");
                        String sessionKey = data.getString("sessionKey");
                        future.complete(new ChzzkSession(sessionSocket, sessionKey));
                    }
                    catch (JSONException ex)
                    {
                        future.completeExceptionally(ex);
                    }
                });
            }
            catch (Exception ex)
            {
                future.completeExceptionally(ex);
            }
        }).exceptionally(ex ->
        {
            future.completeExceptionally(ex);
            return null;
        });

        return future;
    }

    public void disconnect()
    {
        if (_isConnect)
            _socket.disconnect();
    }

    public void subscribeChatEvent(ChzzkToken token, IChatEvent chatEvent, Consumer<Throwable> failCallback)
    {
        if (!token.IsValid())
        {
            failCallback.accept(new RuntimeException("유효하지 않는 토큰입니다."));
            return;
        }

        final String channelId = token.getChannelId();
        ChzzkSession originSession = channelIdBySession.get(channelId);
        if (originSession != null && originSession != this)
        {
            failCallback.accept(new RuntimeException("같은 세션에만 구독할 수 있습니다."));
            return;
        }

        final String accessToken = token.getAccessToken();
        String completeURI = ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/events/subscribe/chat?" + "sessionKey=" + _sessionKey;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(completeURI)) //
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response ->
        {
            if (response.statusCode() != 200)
            {
                failCallback.accept(new RuntimeException(response.body()));
                return;
            }

            if (channelId == null)
            {
                failCallback.accept(new RuntimeException("유효하지 않는 채널 ID 입니다."));
                return;
            }

            boolean isPut;
            synchronized (_channelIdByChatEvent)
            {
                isPut = _channelIdByChatEvent.size() < MAX_EVENT_SIZE || _channelIdByChatEvent.containsKey(channelId);
            }
            if (isPut)
            {
                channelIdBySession.put(channelId, this);
                _channelIdByChatEvent.put(channelId, chatEvent);
            }
            else
            {
                failCallback.accept(new RuntimeException("더 이상 구독할 수 없습니다."));
            }
        }).exceptionally(throwable ->
        {
            failCallback.accept(throwable);
            return null;
        });
    }

    private void unsubscribeChatEvent(ChzzkToken token)
    {
        String channelId = token.getChannelId();
        final String accessToken = token.getAccessToken();
        String completeURI = ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/events/unsubscribe/chat?" + "sessionKey=" + _sessionKey;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(completeURI)) //
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 토큰에 구독된 모든 이벤트를 해지합니다.
     *
     */
    public void unsubscribeTokenEvents(ChzzkToken token)
    {
        String channelId = token.getChannelId();
        channelIdBySession.remove(channelId);
        //TODO:이벤트가 추가될때마다 다 해지 해줘야함
        unsubscribeChatEvent(token);
    }

    public void setSubscribeEvent(ISubscribeEvent subscribeEvent)
    {
        this._subscribeEvent = subscribeEvent;
    }

    public void setUnsubscribeEvent(ISubscribeEvent unsubscribeEvent)
    {
        this._unsubscribeEvent = unsubscribeEvent;
    }

    public void setRevokedSubscribeEvent(ISubscribeEvent revokedEvent)
    {
        this._revokedEvent = revokedEvent;
    }
}