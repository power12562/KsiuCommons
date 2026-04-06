package com.ksiu.commons.streamconnector.chzzk.session;

import com.ksiu.commons.streamconnector.chzzk.session.interfaces.ISessionSubscribeEvent;
import com.ksiu.commons.streamconnector.chzzk.token.ChzzkToken;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class ChzzkSessionManager
{
    private static volatile ChzzkSessionManager instance;

    public static void initialize(String clientId, String clientSecret)
    {
        if (instance != null)
            return;

        instance = new ChzzkSessionManager(clientId, clientSecret);
    }

    private ChzzkSessionManager(String clientId, String clientSecret)
    {
        _clientId = clientId;
        _clientSecret = clientSecret;
        expansionSessions(0);
    }

    private final String _clientId;
    private final String _clientSecret;
    private final AtomicReferenceArray<ChzzkSession> _sessions = new AtomicReferenceArray<>(10);
    private final Map<String, Integer> _channelIdBySessionsIndex = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<ChzzkSession>> _sessionsIndexBySessionFutures = new ConcurrentHashMap<>();
    private ISessionSubscribeEvent _sessionSubscribeEvent;
    private ISessionSubscribeEvent _sessionUnsubscribeEvent;
    private ISessionSubscribeEvent _sessionRevokedSubscribeEvent;

    public static void clear()
    {
        instance.internalClear();
    }

    private void internalClear()
    {
        int count = _channelIdBySessionsIndex.size();
        if (count == 0)
            return;

        _channelIdBySessionsIndex.clear();
        _sessionsIndexBySessionFutures.forEach((idx, future) ->
        {
            future.cancel(true);
        });
        _sessionsIndexBySessionFutures.clear();

        for (int i = 0; i < _sessions.length(); i++)
        {
            ChzzkSession session = _sessions.getAndSet(i, null);
            if (session != null)
            {
                session.disconnect();
            }
        }
    }

    public static CompletableFuture<ChzzkSession> getSession(ChzzkToken token)
    {
        return instance.internalGetSession(token);
    }

    private CompletableFuture<ChzzkSession> internalGetSession(ChzzkToken token)
    {
        ChzzkSession findSession = ChzzkSession.getSessionByToken(token);
        if (findSession != null)
            return CompletableFuture.completedFuture(findSession);

        final String channelId = token.getChannelId();
        Integer sessionsIndex = _channelIdBySessionsIndex.get(channelId);
        if (sessionsIndex != null)
        {
            CompletableFuture<ChzzkSession> sessionFuture = _sessionsIndexBySessionFutures.get(sessionsIndex);
            if (sessionFuture != null)
                return sessionFuture;

            findSession = _sessions.get(sessionsIndex);
            if (findSession != null)
                return CompletableFuture.completedFuture(findSession);

            return CompletableFuture.failedFuture(new RuntimeException("잘못된 Token 입니다."));
        }

        int tokenCount = _channelIdBySessionsIndex.size();
        sessionsIndex = tokenCount / 10;
        _channelIdBySessionsIndex.put(channelId, sessionsIndex);

        if (tokenCount % 10 == 9)
            expansionSessions(sessionsIndex + 1);

        CompletableFuture<ChzzkSession> sessionFuture = _sessionsIndexBySessionFutures.get(sessionsIndex);
        if (sessionFuture != null)
            return sessionFuture;

        findSession = _sessions.get(sessionsIndex);
        if (findSession != null)
            return CompletableFuture.completedFuture(findSession);

        _channelIdBySessionsIndex.remove(channelId);
        return CompletableFuture.failedFuture(new RuntimeException("더 이상 세션을 생성할 수 없습니다."));
    }

    private void expansionSessions(int newIndex)
    {
        if (newIndex > 10)
            return;

        CompletableFuture<ChzzkSession> newFuture = ChzzkSession.createSession(_clientId, _clientSecret)
                .thenApply(session ->
                {
                    _sessions.set(newIndex, session);
                    _sessionsIndexBySessionFutures.remove(newIndex);

                    if (_sessionSubscribeEvent != null)
                        session.setSessionSubscribeEvent(_sessionSubscribeEvent);
                    if (_sessionUnsubscribeEvent != null)
                        session.setSessionUnsubscribeEvent(_sessionUnsubscribeEvent);
                    if (_sessionRevokedSubscribeEvent != null)
                        session.setSessionRevokedSubscribeEvent(_sessionRevokedSubscribeEvent);

                    return session;
                })
                .exceptionally(throwable ->
                {
                    _sessionsIndexBySessionFutures.remove(newIndex);
                    return null;
                });
        _sessionsIndexBySessionFutures.put(newIndex, newFuture);
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
