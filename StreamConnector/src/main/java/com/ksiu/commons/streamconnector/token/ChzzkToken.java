package com.ksiu.commons.streamconnector.token;

import com.ksiu.commons.streamconnector.authorizer.ChzzkAuthorizer;

public final class ChzzkToken
{
    private final String _accessToken;
    private final String _refreshToken;
    private final ChzzkAuthorizer _authorizer;
    private boolean _isValid;
    private static final long VALID_TOKEN_TIME_MS = 1430 * 60 * 1000L;
    private final long _issuedAt;

    public ChzzkToken(ChzzkAuthorizer authorizer, String accessToken, String refreshToken)
    {
        _authorizer = authorizer;
        _accessToken = accessToken;
        _refreshToken = refreshToken;
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

    public final boolean IsValid()
    {
        return _isValid && (System.currentTimeMillis() < _issuedAt + VALID_TOKEN_TIME_MS);
    }

    public final void revokeAccessToken()
    {
        _isValid = false;
        _authorizer.revokeToken(getAccessToken(), "access_token");
    }

    public final void revokeRefreshToken()
    {
        _isValid = false;
        _authorizer.revokeToken(getRefreshToken(), "refresh_token");
    }

}
