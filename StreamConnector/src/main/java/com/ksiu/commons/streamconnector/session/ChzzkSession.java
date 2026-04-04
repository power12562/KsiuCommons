package com.ksiu.commons.streamconnector.session;

import com.ksiu.commons.streamconnector.authorizer.ChzzkAuthorizer;
import com.ksiu.commons.streamconnector.session.interfaces.IDisconnectEvent;
import com.ksiu.commons.streamconnector.session.interfaces.ISystemEvent;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class ChzzkSession
{
    private final Socket _socket;
    private final String _sessionID;
    private volatile boolean _isConnect = true;

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

    private ChzzkSession(Socket socket, String sessionID)
    {
        _socket = socket;
        _sessionID = sessionID;
        _socket.off();
        onDisconnect(null);
    }

    public boolean isConnect()
    {
        return _isConnect;
    }

    public void onSystem(ISystemEvent onSystem)
    {
        _socket.off("SYSTEM");
        _socket.on("SYSTEM", args ->
        {
            if (onSystem != null)
            {
                JSONObject response = argsToJson(args);
                if (response != null)
                    onSystem.execute(response);
            }
        });
    }

    public void onDisconnect(IDisconnectEvent onDisconnect)
    {
        _socket.off("disconnect");
        _socket.on("disconnect", args ->
        {
            _isConnect = false;
        });
        _socket.on("disconnect", args ->
        {
            if (onDisconnect != null)
            {
                onDisconnect.execute();
            }
        });
    }

    public static CompletableFuture<ChzzkSession> createSession(String clientId, String clientSecret)
    {
        CompletableFuture<ChzzkSession> future = new CompletableFuture<>();
        try
        {
            // 세션 소켓 url 요청
            HttpClient httpClient = HttpClient.newHttpClient();
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
                    sessionSocket.on("disconnect", args ->
                    {
                        if (args[0] instanceof String message)
                        {
                            future.completeExceptionally(new RuntimeException("세션 연결 실패: " + message));
                            return;
                        }
                        future.completeExceptionally(new RuntimeException("세션 연결 실패 접속이 종료되었습니다."));
                    });
                    sessionSocket.on("SYSTEM", args ->
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
        }
        catch (Exception ex)
        {
            future.completeExceptionally(ex);
        }
        return future;
    }

    public void disconnect()
    {
        _socket.disconnect();
    }
}