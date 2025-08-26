package cz.googleuploader;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.UserCredentials;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.PhotosLibrarySettings;
import com.google.photos.library.v1.proto.CreateAlbumRequest;
import com.google.photos.library.v1.proto.ListAlbumsRequest;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsRequest;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsResponse;
import com.google.photos.library.v1.proto.NewMediaItem;
import com.google.photos.library.v1.proto.SimpleMediaItem;
import com.google.photos.types.proto.Album;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static cz.googleuploader.GooglePhotosAuth.SCOPES;
import static cz.util.Utils.readLines;
import static cz.util.Utils.writeLine;

public class GooglePhotosUploader {

    private static final String UPLOADED_FILES = "uploaded_files.txt";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Použití: java GooglePhotosUploader <cesta_k_root_složce>, <cesta_k_client_secret.json>");
            System.exit(1);
        }

        Path rootDir = Paths.get(args[0]);
        String clientSecretPath = args[1];
        if (!Files.isDirectory(rootDir)) {
            throw new IllegalArgumentException("Zadaná cesta není adresář: " + rootDir);
        }

        // 1. Připravíme transport, JSON parser a načteme client_secret.json
        JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
        var httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
                JSON_FACTORY,
                new FileReader(clientSecretPath)
        );

// 2. Vytvoříme flow se stejnou složkou, kde máme uložené StoredCredential
        var flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY,
                clientSecrets, SCOPES
        )
                .setDataStoreFactory(new FileDataStoreFactory(new File("tokens")))
                .setAccessType("offline")
                .build();

// 3. Načteme uložený Credential pro uživatele "user"
        Credential oldCredential = flow.loadCredential("user");
        if (oldCredential == null || oldCredential.getRefreshToken() == null) {
            throw new IllegalStateException("StoredCredential nenalezen nebo chybí refresh token");
        }


// 4. Vytvoříme AccessToken instanci
        Instant expiresAt = Instant.ofEpochMilli(oldCredential.getExpirationTimeMilliseconds());
        AccessToken initialToken = new AccessToken(oldCredential.getAccessToken(), Date.from(expiresAt));

// 5. Sestavíme UserCredentials
        UserCredentials userCredentials = UserCredentials.newBuilder()
                .setClientId(clientSecrets.getDetails().getClientId())
                .setClientSecret(clientSecrets.getDetails().getClientSecret())
                .setRefreshToken(oldCredential.getRefreshToken())
                .setAccessToken(initialToken)
                .build();

        if (userCredentials == null || userCredentials.getAccessToken() == null) {
            throw new IllegalStateException("Token nebyl nalezen. Spusť nejprve autorizaci.");
        }

        // 4️⃣ Inicializace Google Photos klienta
        PhotosLibrarySettings settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider(() -> userCredentials)
                .build();

        var uploadedFiles = readLines(UPLOADED_FILES);

        try (PhotosLibraryClient client = PhotosLibraryClient.initialize(settings)) {
            // Nacteme všechna existující alba jednou dopředu
            Map<String, Album> existingAlbums = new HashMap<>();
            client.listAlbums(ListAlbumsRequest.newBuilder().setPageSize(50).build())
                    .iterateAll()
                    .forEach(album -> existingAlbums.put(album.getTitle(), album));

            try (DirectoryStream<Path> albums = Files.newDirectoryStream(rootDir)) {
                for (Path albumDir : albums) {
                    if (!Files.isDirectory(albumDir)) continue;

                    String albumTitle = albumDir.getFileName().toString()
                            .replaceAll("_", " "); // bezpečný název alba

                    // Pokud už album existuje, použijeme ho, jinak ho vytvoříme
                    Album album = existingAlbums.get(albumTitle);
                    if (album == null) {
                        album = client.createAlbum(
                                CreateAlbumRequest.newBuilder()
                                        .setAlbum(Album.newBuilder().setTitle(albumTitle).build())
                                        .build());
                        System.out.println("📁 Vytvořeno nové album: " + albumTitle);

                        // Přidáme do mapy, aby bylo dostupné pro další kola
                        existingAlbums.put(albumTitle, album);
                    } else {
                        System.out.println("📁 Používám existující album: " + albumTitle);
                    }

                    List<NewMediaItem> items = new ArrayList<>();
                    try (DirectoryStream<Path> photos =
                                 Files.newDirectoryStream(albumDir, "*.{jpg,jpeg,png,mov,mp4}")) {
                        for (Path photo : photos) {
                            String relativePath = albumDir.getFileName() + "/" + photo.getFileName();
                            if (uploadedFiles.contains(relativePath)) {
                                System.out.println("  ⏭️ Přeskočeno (již nahráno): " + photo.getFileName());
                                continue;
                            }

                            try (RandomAccessFile raf = new RandomAccessFile(photo.toFile(), "r")) {
                                UploadMediaItemRequest uploadRequest = UploadMediaItemRequest.newBuilder()
                                        .setFileName(photo.getFileName().toString())
                                        .setMimeType(Files.probeContentType(photo))
                                        .setDataFile(raf)
                                        .build();

                                UploadMediaItemResponse uploadResponse = client.uploadMediaItem(uploadRequest);
                                String uploadToken = uploadResponse.getUploadToken().get();

                                items.add(NewMediaItem.newBuilder()
                                        .setSimpleMediaItem(SimpleMediaItem.newBuilder()
                                                .setUploadToken(uploadToken)
                                                .build())
                                        .build());

                                System.out.println("  🔄 Připraveno: " + photo.getFileName());
                            }
                        }
                    }

                    if (!items.isEmpty()) {
                        final int MAX_BATCH = 50;
                        for (int start = 0; start < items.size(); start += MAX_BATCH) {
                            int end = Math.min(items.size(), start + MAX_BATCH);
                            List<NewMediaItem> chunk = items.subList(start, end);

                            BatchCreateMediaItemsResponse resp = client.batchCreateMediaItems(
                                    BatchCreateMediaItemsRequest.newBuilder()
                                            .setAlbumId(album.getId())
                                            .addAllNewMediaItems(chunk)
                                            .build()
                            );

                            resp.getNewMediaItemResultsList().forEach(r -> {
                                if (r.getStatus().getCode() == 0) {
                                    System.out.println("    ✅ Nahráno: " +
                                            r.getMediaItem().getFilename());

                                    writeLine(UPLOADED_FILES,
                                            albumDir.getFileName() + "/" + r.getMediaItem().getFilename());
                                } else {
                                    System.err.println("    ❌ Chyba: " +
                                            r.getStatus().getMessage());
                                }
                            });
                        }
                    }
                }
            }
        }
    }
}
