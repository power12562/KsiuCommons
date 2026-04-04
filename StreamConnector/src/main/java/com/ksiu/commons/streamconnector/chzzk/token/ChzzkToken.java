package com.ksiu.commons.streamconnector.chzzk.token;

import com.ksiu.commons.streamconnector.chzzk.authorizer.ChzzkAuthorizer;
import com.ksiu.commons.streamconnector.chzzk.session.ChzzkSession;

public final class ChzzkToken
{
    private static final long VALID_TOKEN_TIME_MS = 1430 * 60 * 1000L;
    private final String _accessToken;
    private final String _refreshToken;
    private final String _channelId;
    private final String _channelName;
    private final ChzzkAuthorizer _authorizer;
    private boolean _isValid;
    private final long _issuedAt;

    public ChzzkToken(ChzzkAuthorizer authorizer, String accessToken, String refreshToken, String channelId, String channelName)
    {
        _authorizer = authorizer;
        _accessToken = accessToken;
        _refreshToken = refreshToken;
        _channelId = channelId;
        _channelName = channelName;
        _issuedAt = System.currentTimeMillis();
        _isValid = true; // 하루 뒤 false 되야함 revoke 에서도 false로 해야함
    }

    public final String getAccessToken()
    {
        return _accessToken;
    }

    public final String getRefreshToken()
    {
        return _refreshToken;
    }

    public final String getChannelId()
    {
        return _channelId;
    }

    public final String getChannelName()
    {
        return _channelName;
    }

    public final boolean IsValid()
    {
        return _isValid && (System.currentTimeMillis() < _issuedAt + VALID_TOKEN_TIME_MS);
    }

    public void revoke()
    {
        revokeAccessToken();
        revokeRefreshToken();
        ChzzkSession mySession = ChzzkSession.getSessionByToken(this);
        if (mySession != null)
        {
            mySession.unsubscribeTokenEvents(this);
        }
    }

    private final void revokeAccessToken()
    {
        _isValid = false;
        _authorizer.revokeToken(getAccessToken(), "access_token");
    }

    private final void revokeRefreshToken()
    {
        _isValid = false;
        _authorizer.revokeToken(getRefreshToken(), "refresh_token");
    }

}
