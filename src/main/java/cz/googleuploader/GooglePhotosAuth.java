package cz.googleuploader;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.auth.oauth2.Credential;

import java.io.FileReader;
import java.util.List;

public class GooglePhotosAuth {

    private static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/photoslibrary.appendonly"
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
    }
}
