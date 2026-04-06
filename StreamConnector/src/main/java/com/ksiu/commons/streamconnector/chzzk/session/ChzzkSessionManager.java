package com.ksiu.commons.streamconnector.chzzk.session;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
    }

    private final List<ChzzkSession> _sessions = new CopyOnWriteArrayList<>();
    private final String _clientId;
    private final String _clientSecret;

    public static void clear()
    {
        instance.internalClear();
    }

    private void internalClear()
    {
        for (ChzzkSession session : _sessions)
        {
            session.disconnect();
        }
        _sessions.clear();
    }

}
