package com.ksiu.commons.streamconnector.soop.token;

import org.json.JSONException;
import org.json.JSONObject;

public final class SoopToken
{
    private final String _bjId;
    private final String _bjNickname;
    private final String _title;
    private final String _chatNo;
    private final String _chatDomain;
    private final String _FTK;
    private final String _BNO;
    private final String _CHIP;
    private final String _CHPT;
    private final String _CTIP;
    private final String _CTPT;
    private final String _GWIP;
    private final String _GWPT;

    private boolean _isValid;
    private long _lastRenewedAt;

    public SoopToken(JSONObject channelJson) throws JSONException
    {
        _bjId = channelJson.getString("BJID");
        _bjNickname = channelJson.getString("BJNICK");
        _title = channelJson.getString("TITLE");
        _chatNo = channelJson.getString("CHATNO");
        _chatDomain = channelJson.getString("CHDOMAIN");
        _FTK = channelJson.getString("FTK");
        _BNO = channelJson.getString("BNO");
        _CHIP = channelJson.getString("CHIP");
        _CHPT = String.valueOf(Integer.parseInt(channelJson.getString("CHPT")) + 1);
        _CTIP = channelJson.getString("CTIP");
        _CTPT = channelJson.getString("CTPT");
        _GWIP = channelJson.getString("GWIP");
        _GWPT = channelJson.getString("GWPT");

        _isValid = true;
        _lastRenewedAt = System.currentTimeMillis();
    }

    public final boolean isValid()
    {
        return _isValid;
    }

    public void revoke()
    {
        _isValid = false;
    }

    public String getBjId()
    {
        return _bjId;
    }

    public String getBJNickname()
    {
        return _bjNickname;
    }

    public String getChatDomain()
    {
        return _chatDomain;
    }

    public Object getChatPort()
    {
        return _CHPT;
    }

    public String getChatNo()
    {
        return _chatNo;
    }
}