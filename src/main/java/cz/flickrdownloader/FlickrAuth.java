package cz.flickrdownloader;

import com.flickr4java.flickr.Flickr;
import com.flickr4java.flickr.REST;
import com.flickr4java.flickr.auth.Permission;
import cz.util.Utils;

import javax.net.ssl.*;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.Properties;
import java.util.Scanner;

public class FlickrAuth {

    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            System.out.println("Pouziti: API_KEY, API_SECRET");
            System.exit(1);
        }

        Utils.ignoreCert(); // 1. (Test) Vypnutí validace SSL certifikátů


        // 2. API klíče
        String apiKey    = args[0];
        String apiSecret = args[1];

        // 3. Inicializace Flickr clienta
        Flickr flickr = new Flickr(apiKey, apiSecret, new REST());

        // 4. OAuth rozhraní
        var oauthIface = flickr.getAuthInterface();

        // 5. Získání request tokenu
        var requestToken = oauthIface.getRequestToken("oob");

        // 6. URL pro autorizaci
        String authUrl = oauthIface.getAuthorizationUrl(requestToken, Permission.READ);
        System.out.println("🔗 Otevři v prohlížeči: " + authUrl);

        // 7. Přečtení verifier kódu
        System.out.print("📥 Zadej ověřovací kód: ");
        String verifier = new Scanner(System.in).nextLine().trim();

        // 8. Výměna za access token
        var accessToken = oauthIface.getAccessToken(
                requestToken,
                verifier
        );

        // 9. Výpis výsledku
        System.out.println("✅ Access Token:  " + accessToken.getToken());
        System.out.println("🔐 Token Secret:  " + accessToken.getTokenSecret());

        // po získání Auth objektu (např. po oauth.getAccessToken(...))
        Properties props = new Properties();
        props.setProperty("oauth.token",       accessToken.getToken());
        props.setProperty("oauth.tokenSecret", accessToken.getTokenSecret());

// uložíme do souboru ve formátu key=value
        try (FileOutputStream out = new FileOutputStream("flickr.properties")) {
            props.store(out, "Flickr OAuth tokeny");
        }

    }
}
