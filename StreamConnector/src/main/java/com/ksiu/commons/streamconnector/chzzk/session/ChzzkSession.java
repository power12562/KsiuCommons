package com.ksiu.commons.streamconnector.chzzk.session;

import com.ksiu.commons.streamconnector.chzzk.authorizer.ChzzkAuthorizer;
import com.ksiu.commons.streamconnector.chzzk.session.interfaces.ISessionDisconnectEvent;
import com.ksiu.commons.streamconnector.chzzk.session.interfaces.ISessionSubscribeEvent;
import com.ksiu.commons.streamconnector.chzzk.session.interfaces.session.IChatEvent;
import com.ksiu.commons.streamconnector.chzzk.session.interfaces.session.IDonationEvent;
import com.ksiu.commons.streamconnector.chzzk.session.interfaces.session.ISessionEvent;
import com.ksiu.commons.streamconnector.chzzk.session.interfaces.session.ISubscriptionEvent;
import com.ksiu.commons.streamconnector.chzzk.token.ChzzkToken;
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
    private ISessionSubscribeEvent _sessionSubscribeEvent;
    private ISessionSubscribeEvent _sessionUnsubscribeEvent;
    private ISessionSubscribeEvent _sessionRevokedSubscribeEvent;
    private final ConcurrentMap<String, IChatEvent> _channelIdByChatEvent = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IDonationEvent> _channelIdByDonationEvent = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ISubscriptionEvent> _channelIdBySubscriptionEvent = new ConcurrentHashMap<>();

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

        initSystemEvent();
        initChatEvent();
        initDonationEvent();
        initSubscriptionEvent();
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
                            if (_sessionSubscribeEvent == null)
                                return;

                            JSONObject object = body.getJSONObject("data");
                            onSubscribed(object, _sessionSubscribeEvent);
                        }
                        case "unsubscribed" ->
                        {
                            if (_sessionUnsubscribeEvent == null)
                                return;

                            JSONObject object = body.getJSONObject("data");
                            onUnsubscribed(object, _sessionUnsubscribeEvent);
                        }
                        case "revoked" ->
                        {
                            if (_sessionRevokedSubscribeEvent == null)
                                return;

                            JSONObject object = body.getJSONObject("data");
                            onUnsubscribed(object, _sessionRevokedSubscribeEvent);
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

    private static void onSubscribed(JSONObject object, ISessionSubscribeEvent event) throws JSONException
    {
        String type = object.getString("eventType");
        String channelId = object.getString("channelId");
        event.execute(type, channelId);
    }

    private void onUnsubscribed(JSONObject object, ISessionSubscribeEvent event) throws JSONException
    {
        String type = object.getString("eventType");
        String channelId = object.getString("channelId");
        switch (type)
        {
            case "CHAT" ->
            {
                _channelIdByChatEvent.remove(channelId);
            }
            case "DONATION" ->
            {
                _channelIdByDonationEvent.remove(channelId);
            }
            case "SUBSCRIPTION" ->
            {
                _channelIdBySubscriptionEvent.remove(channelId);
            }
        }
        event.execute(type, channelId);
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

    private void initDonationEvent()
    {
        _socket.on("DONATION", args ->
        {
            try
            {
                JSONObject body = argsToJson(args);
                if (body != null)
                {
                    String channelId = body.getString("channelId");
                    IDonationEvent event = _channelIdByDonationEvent.get(channelId);
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

    private void initSubscriptionEvent()
    {
        _socket.on("SUBSCRIPTION", args ->
        {
            try
            {
                JSONObject body = argsToJson(args);
                if (body != null)
                {
                    String channelId = body.getString("channelId");
                    ISubscriptionEvent event = _channelIdBySubscriptionEvent.get(channelId);
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

    public void setDisconnectEvent(ISessionDisconnectEvent onDisconnect)
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

    private static HttpRequest newSessionSubscribeRequest(URI uri, String accessToken)
    {
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private boolean cantSessionSubscribe(ChzzkToken token)
    {
        if (!token.IsValid())
        {
            return true;
        }
        final String channelId = token.getChannelId();
        ChzzkSession originSession = channelIdBySession.get(channelId);
        return originSession != null && originSession != this;
    }

    private static void postSessionEvent(ChzzkSession session, ChzzkToken token, String completeURI, ISessionEvent event, Consumer<Throwable> failCallback)
    {
        if (session.cantSessionSubscribe(token))
        {
            failCallback.accept(new RuntimeException("유효한 토큰이 아니거나, 다른 세션에 등록되어있는 토큰입니다."));
            return;
        }

        String accessToken = token.getAccessToken();
        HttpRequest request = newSessionSubscribeRequest(URI.create(completeURI), accessToken);
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response ->
        {
            if (response.statusCode() != 200)
            {
                failCallback.accept(new RuntimeException(response.body()));
                return;
            }

            final String channelId = token.getChannelId();
            if (channelId == null)
            {
                failCallback.accept(new RuntimeException("유효하지 않는 채널 ID 입니다."));
                return;
            }

            boolean isPut = false;
            if (event instanceof IChatEvent chatEvent)
            {
                synchronized (session._channelIdByChatEvent)
                {
                    isPut = session._channelIdByChatEvent.size() < MAX_EVENT_SIZE || session._channelIdByChatEvent.containsKey(channelId);
                }
                if (isPut)
                {
                    channelIdBySession.put(channelId, session);
                    session._channelIdByChatEvent.put(channelId, chatEvent);
                }
            }
            else if (event instanceof IDonationEvent donationEvent)
            {
                synchronized (session._channelIdByDonationEvent)
                {
                    isPut = session._channelIdByDonationEvent.size() < MAX_EVENT_SIZE || session._channelIdByDonationEvent.containsKey(channelId);
                }
                if (isPut)
                {
                    channelIdBySession.put(channelId, session);
                    session._channelIdByDonationEvent.put(channelId, donationEvent);
                }
            }
            else if (event instanceof ISubscriptionEvent subscriptionEvent)
            {
                synchronized (session._channelIdBySubscriptionEvent)
                {
                    isPut = session._channelIdBySubscriptionEvent.size() < MAX_EVENT_SIZE || session._channelIdBySubscriptionEvent.containsKey(channelId);
                }
                if (isPut)
                {
                    channelIdBySession.put(channelId, session);
                    session._channelIdBySubscriptionEvent.put(channelId, subscriptionEvent);
                }
            }

            if (!isPut)
            {
                failCallback.accept(new RuntimeException("더 이상 구독할 수 없습니다."));
            }

        }).exceptionally(throwable ->
        {
            failCallback.accept(throwable);
            return null;
        });

    }

    public void subscribeChatEvent(ChzzkToken token, IChatEvent chatEvent, Consumer<Throwable> failCallback)
    {
        String completeURI = ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/events/subscribe/chat?" + "sessionKey=" + _sessionKey;
        postSessionEvent(this, token, completeURI, chatEvent, failCallback);
    }

    private void unsubscribeChatEvent(ChzzkToken token)
    {
        final String accessToken = token.getAccessToken();
        String completeURI = ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/events/unsubscribe/chat?" + "sessionKey=" + _sessionKey;
        HttpRequest request = newSessionSubscribeRequest(URI.create(completeURI), accessToken);
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public void subscribeDonationEvent(ChzzkToken token, IDonationEvent donationEvent, Consumer<Throwable> failCallback)
    {
        String completeURI = ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/events/subscribe/donation?" + "sessionKey=" + _sessionKey;
        postSessionEvent(this, token, completeURI, donationEvent, failCallback);
    }

    private void unsubscribeDonationEvent(ChzzkToken token)
    {
        final String accessToken = token.getAccessToken();
        String completeURI = ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/events/unsubscribe/donation?" + "sessionKey=" + _sessionKey;
        HttpRequest request = newSessionSubscribeRequest(URI.create(completeURI), accessToken);
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public void subscribeSubscriptionEvent(ChzzkToken token, ISubscriptionEvent subscriptionEvent, Consumer<Throwable> failCallback)
    {
        String completeURI = ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/events/subscribe/subscription?" + "sessionKey=" + _sessionKey;
        postSessionEvent(this, token, completeURI, subscriptionEvent, failCallback);
    }

    private void unsubscribeSubscriptionEvent(ChzzkToken token)
    {
        final String accessToken = token.getAccessToken();
        String completeURI = ChzzkAuthorizer.CHZZK_API_URL + "/open/v1/sessions/events/unsubscribe/subscription?" + "sessionKey=" + _sessionKey;
        HttpRequest request = newSessionSubscribeRequest(URI.create(completeURI), accessToken);
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 토큰에 구독된 모든 이벤트를 해지합니다. Token의 revoke에서 호출됩니다.
     */
    public void unsubscribeTokenEvents(ChzzkToken token)
    {
        String channelId = token.getChannelId();
        unsubscribeChatEvent(token);
        unsubscribeDonationEvent(token);
        unsubscribeSubscriptionEvent(token);
        channelIdBySession.remove(channelId);
    }

    public void setSessionSubscribeEvent(ISessionSubscribeEvent subscribeEvent)
    {
        this._sessionSubscribeEvent = subscribeEvent;
    }

    public void setSessionUnsubscribeEvent(ISessionSubscribeEvent unsubscribeEvent)
    {
        this._sessionUnsubscribeEvent = unsubscribeEvent;
    }

    public void setSessionRevokedSubscribeEvent(ISessionSubscribeEvent revokedEvent)
    {
        this._sessionRevokedSubscribeEvent = revokedEvent;
    }
}