package me.devsaki.hentoid.services;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.CheckResult;
import android.support.annotation.Nullable;

import com.annimon.stream.Stream;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import me.devsaki.hentoid.database.HentoidDB;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ContentV1;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.events.ImportEvent;
import me.devsaki.hentoid.model.DoujinBuilder;
import me.devsaki.hentoid.model.URLBuilder;
import me.devsaki.hentoid.notification.import_.ImportCompleteNotification;
import me.devsaki.hentoid.notification.import_.ImportStartNotification;
import me.devsaki.hentoid.util.AttributeException;
import me.devsaki.hentoid.util.Consts;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.JsonHelper;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.notification.NotificationManager;
import me.devsaki.hentoid.util.notification.ServiceNotificationManager;
import timber.log.Timber;

import static com.annimon.stream.Collectors.toList;

/**
 * Service responsible for importing an existing library.
 *
 * @see UpdateCheckService
 */
public class ImportService extends IntentService {

    private static final int NOTIFICATION_ID = 1;

    private static boolean running;

    private ServiceNotificationManager notificationManager;

    // TODO - Discuss with senpai of the opportunity of keeping it as an IntentService (easier to make it run as a worker thread)
    // TODO - clean folder names according to prefs rules

    public ImportService() {
        super(ImportService.class.getName());
    }

    public static Intent makeIntent(Context context) {
        return new Intent(context, ImportService.class);
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        running = true;
        notificationManager = new ServiceNotificationManager(this, NOTIFICATION_ID);
        notificationManager.cancel();

        Timber.w("Service created");
    }

    @Override
    public void onDestroy() {
        running = false;
        Timber.w("Service destroyed");

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        startImport();
    }

/*
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startImport();
        return START_NOT_STICKY;
    }
*/

    private void eventProgress(Content content, int nbBooks, int booksOK, int booksKO)
    {
        EventBus.getDefault().post(new ImportEvent(ImportEvent.EV_PROGRESS, content, booksOK, booksKO, nbBooks));
    }

    private void eventComplete(int nbBooks, int booksOK, int booksKO)
    {
        EventBus.getDefault().post(new ImportEvent(ImportEvent.EV_COMPLETE, booksOK, booksKO, nbBooks));
    }

    private void startImport()
    {
        int booksOK = 0;
        int booksKO = 0;

        notificationManager.startForeground(new ImportStartNotification());

        List<File> files = Stream.of(Site.values())
                .map(site -> FileHelper.getSiteDownloadDir(this, site))
                .map(File::listFiles)
                .flatMap(Stream::of)
                .filter(File::isDirectory)
                .collect(toList());

        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);

            Content content = importJson(file);
            if (content != null)
            {
                HentoidDB.getInstance(this).insertContent(content);
                booksOK++;
            } else {
                booksKO++;
            }

            eventProgress(content, files.size(), booksOK, booksKO);
        }
        eventComplete(files.size(), booksOK, booksKO);

        notificationManager.notify(new ImportCompleteNotification(booksOK, booksKO));

        stopForeground(true);
        stopSelf();
    }

    private static Content importJson(File folder) {
        File json = new File(folder, Consts.JSON_FILE_NAME_V2); // (v2) JSON file format
        if (json.exists()) return importJsonV2(json);

        json = new File(folder, Consts.JSON_FILE_NAME); // (v1) JSON file format
        if (json.exists()) return importJsonV1(json, folder);

        json = new File(folder, Consts.OLD_JSON_FILE_NAME); // (old) JSON file format (legacy and/or FAKKUDroid App)
        if (json.exists()) return importJsonLegacy(json, folder);

        return null;
    }

    @SuppressWarnings("deprecation")
    private static List<Attribute> from(List<URLBuilder> urlBuilders) {
        List<Attribute> attributes = null;
        if (urlBuilders == null) {
            return null;
        }
        if (urlBuilders.size() > 0) {
            attributes = new ArrayList<>();
            for (URLBuilder urlBuilder : urlBuilders) {
                Attribute attribute = from(urlBuilder, AttributeType.TAG);
                if (attribute != null) {
                    attributes.add(attribute);
                }
            }
        }

        return attributes;
    }

    @SuppressWarnings("deprecation")
    private static Attribute from(URLBuilder urlBuilder, AttributeType type) {
        if (urlBuilder == null) {
            return null;
        }
        try {
            if (urlBuilder.getDescription() == null) {
                throw new AttributeException("Problems loading attribute v2.");
            }

            return new Attribute()
                    .setName(urlBuilder.getDescription())
                    .setUrl(urlBuilder.getId())
                    .setType(type);
        } catch (Exception e) {
            Timber.e(e, "Parsing URL to attribute");
            return null;
        }
    }

    @Nullable
    @CheckResult
    @SuppressWarnings("deprecation")
    private static Content importJsonLegacy(File json, File file) {
        try {
            DoujinBuilder doujinBuilder =
                    JsonHelper.jsonToObject(json, DoujinBuilder.class);
            //noinspection deprecation
            ContentV1 content = new ContentV1();
            content.setUrl(doujinBuilder.getId());
            content.setHtmlDescription(doujinBuilder.getDescription());
            content.setTitle(doujinBuilder.getTitle());
            content.setSeries(from(doujinBuilder.getSeries(),
                    AttributeType.SERIE));
            Attribute artist = from(doujinBuilder.getArtist(),
                    AttributeType.ARTIST);
            List<Attribute> artists = null;
            if (artist != null) {
                artists = new ArrayList<>(1);
                artists.add(artist);
            }

            content.setArtists(artists);
            content.setCoverImageUrl(doujinBuilder.getUrlImageTitle());
            content.setQtyPages(doujinBuilder.getQtyPages());
            Attribute translator = from(doujinBuilder.getTranslator(),
                    AttributeType.TRANSLATOR);
            List<Attribute> translators = null;
            if (translator != null) {
                translators = new ArrayList<>(1);
                translators.add(translator);
            }
            content.setTranslators(translators);
            content.setTags(from(doujinBuilder.getLstTags()));
            content.setLanguage(from(doujinBuilder.getLanguage(),AttributeType.LANGUAGE));

            content.setMigratedStatus();
            content.setDownloadDate(new Date().getTime());
            Content contentV2 = content.toV2Content();

            String fileRoot = Preferences.getRootFolderName();
            contentV2.setStorageFolder(json.getAbsoluteFile().getParent().substring(fileRoot.length()));
            try {
                JsonHelper.saveJson(contentV2, file);
            } catch (IOException e) {
                Timber.e(e,
                        "Error converting JSON (old) to JSON (v2): %s", content.getTitle());
            }

            return contentV2;
        } catch (Exception e) {
            Timber.e(e, "Error reading JSON (old) file");
        }
        return null;
    }

    @Nullable
    @CheckResult
    private static Content importJsonV1(File json, File file) {
        try {
            //noinspection deprecation
            ContentV1 content = JsonHelper.jsonToObject(json, ContentV1.class);
            if (content.getStatus() != StatusContent.DOWNLOADED
                    && content.getStatus() != StatusContent.ERROR) {
                content.setMigratedStatus();
            }
            Content contentV2 = content.toV2Content();

            String fileRoot = Preferences.getRootFolderName();
            contentV2.setStorageFolder(json.getAbsoluteFile().getParent().substring(fileRoot.length()));
            try {
                JsonHelper.saveJson(contentV2, file);
            } catch (IOException e) {
                Timber.e(e, "Error converting JSON (v1) to JSON (v2): %s", content.getTitle());
            }

            return contentV2;
        } catch (Exception e) {
            Timber.e(e, "Error reading JSON (v1) file");
        }
        return null;
    }

    @Nullable
    @CheckResult
    private static Content importJsonV2(File json) {
        try {
            Content content = JsonHelper.jsonToObject(json, Content.class);

            if (null == content.getAuthor()) content.populateAuthor();

            String fileRoot = Preferences.getRootFolderName();
            content.setStorageFolder(json.getAbsoluteFile().getParent().substring(fileRoot.length()));

            if (content.getStatus() != StatusContent.DOWNLOADED
                    && content.getStatus() != StatusContent.ERROR) {
                content.setStatus(StatusContent.MIGRATED);
            }

            return content;
        } catch (Exception e) {
            Timber.e(e, "Error reading JSON (v2) file");
        }
        return null;
    }
}
