package cz.googleuploader;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.auth.oauth2.Credential;
import org.apache.http.protocol.HTTP;

import java.io.FileReader;
import java.util.List;
import java.util.Map;

public class GooglePhotosAuth {

    public static List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/photoslibrary.readonly",
            "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata",
            "https://www.googleapis.com/auth/photoslibrary.appendonly",
            "https://www.googleapis.com/auth/photoslibrary.sharing"
    );
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    public static Credential authorize(String clientSecretJson) throws Exception {
        var httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        // načtení client_id + client_secret
        var clientSecrets = GoogleClientSecrets.load(
                JSON_FACTORY,
                new FileReader(clientSecretJson)
        );

        // vytvoření OAuth flow
        var flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File("tokens")))
                .setAccessType("offline")
                .build();

        // spustí lokální server a otevře prohlížeč
        var receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.out.println("Použití: client_secret_json path");
            return;
        }

        Credential cred = authorize(args[0]);
        System.out.println("✅ Access token: " + cred.getAccessToken());
        System.out.println("🔄 Refresh token: " + cred.getRefreshToken());

// …

        String tokenValue = cred.getAccessToken();

// 1️⃣ Připravíme transport a JSON factory
        HttpTransport transport = new NetHttpTransport();
        JsonFactory jsonFactory = new JacksonFactory();

// 2️⃣ Vytvoříme HttpRequestFactory s JSON parserem
        HttpRequestFactory requestFactory = transport.createRequestFactory(request -> {
            request.setParser(new JsonObjectParser(jsonFactory));
        });

// 3️⃣ Sestavíme URL pro tokeninfo
        GenericUrl url = new GenericUrl("https://oauth2.googleapis.com/tokeninfo");
        url.put("access_token", tokenValue);

// 4️⃣ Provedeme GET a parsujeme odpověď jako Map
        HttpResponse response = requestFactory.buildGetRequest(url).execute();
        @SuppressWarnings("unchecked")
        Map<String,Object> info = (Map<String,Object>) response.parseAs(Map.class);

// 5️⃣ Vypíšeme scopes
        System.out.println("Scopes: " + info.get("scope"));
        System.out.println("Expires in: " + info.get("expires_in"));
    }
}
