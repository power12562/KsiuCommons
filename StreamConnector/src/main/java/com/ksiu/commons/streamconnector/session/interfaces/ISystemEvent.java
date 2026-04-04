package com.ksiu.commons.streamconnector.session.interfaces;

import org.json.JSONObject;

@FunctionalInterface
public interface ISystemEvent
{
    void execute(JSONObject object);
}
