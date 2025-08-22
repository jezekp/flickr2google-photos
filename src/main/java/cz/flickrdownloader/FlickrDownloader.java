package cz.flickrdownloader;

import com.flickr4java.flickr.Flickr;
import com.flickr4java.flickr.REST;
import com.flickr4java.flickr.RequestContext;
import com.flickr4java.flickr.auth.Auth;
import com.flickr4java.flickr.auth.Permission;
import com.flickr4java.flickr.photos.Media;
import com.flickr4java.flickr.photos.Photo;
import com.flickr4java.flickr.photos.Size;
import com.flickr4java.flickr.photosets.Photoset;
import com.flickr4java.flickr.photosets.Photosets;
import com.flickr4java.flickr.photosets.PhotosetsInterface;
import com.google.common.util.concurrent.RateLimiter;
import cz.util.Utils;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static cz.util.Utils.readLines;
import static cz.util.Utils.writeLine;

public class FlickrDownloader {

    private static final int MAX_RETRIES = 5;
    private static final long BASE_BACKOFF = 1_000; // 1s základní backoff
    private static final double DOWNLOAD_RATE = 2.0;   // permity za sekundu

    private static final String ALBUMS_FILE = "downloaded_albums.txt"; // soubor pro uložení alb

    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            System.out.println("Pouziti: API_KEY, API_SECRET,  download directory path, [ignoreAutoUpload]");
            System.exit(1);
        }

        var apiKey = args[0];
        var apiSecret = args[1];
        var downloadDir = args[2];
        var ingoreAutoUpload = args.length > 3 && Boolean.parseBoolean(args[3]);

        Utils.ignoreCert(); // 1. (Test) Vypnutí validace SSL certifikátů

        // --- inicializace Flickr klienta a OAuth flow ---
        Flickr flickr = new Flickr(apiKey, apiSecret, new REST());

        // načteme dříve uložené hodnoty
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream("flickr.properties")) {
            props.load(in);
        }

        String token = props.getProperty("oauth.token");
        String tokenSecret = props.getProperty("oauth.tokenSecret");

        // znovu sestavíme Auth a nastavíme ho do RequestContextu
        Auth auth = flickr.getAuthInterface()
                .checkToken(token, tokenSecret);
        auth.setPermission(Permission.READ);
        flickr.setAuth(auth);

        RequestContext.getRequestContext().setAuth(auth);

        System.out.println("Přihlášen jako: " + auth.getUser().getUsername() +
                " (" + auth.getUser().getId() + "), perms=" + auth.getPermission());

        // --- načtení alb ---
        PhotosetsInterface psi = flickr.getPhotosetsInterface();
        Photosets sets = psi.getList(auth.getUser().getId(), 500, 1, null);

        // --- RateLimiter pro stahování ---
        RateLimiter limiter = RateLimiter.create(DOWNLOAD_RATE);

        var downloadedAlbums = readLines(ALBUMS_FILE);

        for (Photoset set : sets.getPhotosets()) {
            String albumTitle = set.getTitle()
                    .replaceAll("[^\\p{L}\\d_\\-\\.]", "_");
            if (downloadedAlbums.contains(albumTitle)) {
                // přeskočíme auto-upload album a uz nactena alba
                System.out.println("Album already exists: " + albumTitle);
                continue;
            }

            if (ingoreAutoUpload && "Auto_Upload".equals(albumTitle)) {
                System.out.println("⏭️ Přeskočeno: " + albumTitle + " (Auto_Upload)");
                continue;
            }

            Path albumPath = Paths.get(downloadDir, albumTitle);
            Files.createDirectories(albumPath);
            System.out.println("📁 Stahuji album: " + albumTitle);

            // stáhneme všech 500 fotek z alba (jedna stránka)
            var photos = psi.getPhotos(set.getId(), 5000, 1);

            for (Photo photo : photos) {
                limiter.acquire(); // dodržujeme max DOWNLOAD_RATE

                Collection<Size> sizes = flickr.getPhotosInterface().getSizes(photo.getId());
//
                var size = sizes.stream().filter(s -> (s.getMedia() == Media.photo && s.getLabelName().equals("Original")) ||
                        s.getMedia() == Media.video && s.getLabelName().equals("Video Original")).max(Comparator.comparingInt(Size::getLabel));

                String photoUrl = size.get().getSource();


//                String photoUrl = photo.getOriginalUrl()
//                        .replaceFirst("farm\\d+\\.staticflickr\\.com", "live.staticflickr.com")  // fallback doména
//                        .replace("http://", "https://");

                String baseName = photo.getTitle()
                        .replaceAll("[^\\p{L}\\d_\\-\\.]", "_");

                baseName = baseName.isEmpty() ? "photo_" + photo.getId() : baseName;

                String fileName = baseName + "." + photo.getOriginalFormat();
                Path filePath = albumPath.resolve(fileName);


                boolean success = false;
                long backoff = BASE_BACKOFF;

                for (int attempt = 1; attempt <= MAX_RETRIES && !success; attempt++) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(photoUrl).openConnection();
                    conn.setRequestProperty("User-Agent", "cz.flickrdownloader.FlickrDownloader/1.0");
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(10_000);

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (InputStream in = conn.getInputStream()) {
                            Files.copy(in, filePath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        System.out.println("  ✅ Staženo: " + fileName);
                        success = true;

                    } else if (code == 429) {
                        // Too Many Requests
                        String retryAfter = conn.getHeaderField("Retry-After");
                        long wait = retryAfter != null
                                ? TimeUnit.SECONDS.toMillis(Long.parseLong(retryAfter))
                                : backoff;
                        System.err.println("  ⚠️ 429, čekám " + wait + " ms a zkouším znovu ("
                                + attempt + "/" + MAX_RETRIES + ")");
                        Thread.sleep(wait);
                        backoff *= 2; // exponenciální backoff

                    } else {
                        System.err.println("  ❌ HTTP " + code + " při stahování " + photoUrl);
                        break;
                    }
                }

                if (!success) {
                    System.err.println("  ❌ Nezdařilo se stáhnout po " +
                            MAX_RETRIES + " pokusech: " + photoUrl);
                }
            }
            writeLine(ALBUMS_FILE, albumTitle);
        }

        System.out.println("🎉 Hotovo!");
    }

}
