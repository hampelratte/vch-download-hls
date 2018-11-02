package de.berlios.vch.download.hls;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Invalidate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.annotations.Validate;
import org.osgi.service.log.LogService;

import de.berlios.vch.download.Download;
import de.berlios.vch.download.DownloadFactory;
import de.berlios.vch.download.PlaylistFileFoundException;
import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.IVideoPage;

@Component
@Provides
public class HlsDownloadFactory implements DownloadFactory {

    @Requires
    private LogService logger;

    private boolean valid = false;

    private static final String[] ACCEPTED_MIME_TYPES = {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/x-mpegurl"
    };

    public HlsDownloadFactory(LogService logger) {
        this.logger = logger;
    }

    @Override
    public boolean accept(IVideoPage video) {
        if(valid && video.getVideoUri() != null) {
            String scheme = video.getVideoUri().getScheme();
            if("http".equals(scheme) || "https".equals(scheme)) {
                try {
                    Map<String, List<String>> header = HttpUtils.head(video.getVideoUri().toString(), null, "utf-8");
                    if(header.containsKey("Location")) {
                        video.setVideoUri(new URI(header.get("Location").get(0)));
                        return accept(video);
                    }
                    if(header.containsKey("Content-Type")) {
                        String contentType = header.get("Content-Type").get(0).toLowerCase();
                        for (String mimeType : ACCEPTED_MIME_TYPES) {
                            if(contentType.contains(mimeType)) {
                                return true;
                            }
                        }
                        return false;
                    }
                } catch (IOException e) {
                    logger.log(LogService.LOG_ERROR, "Couldn't execute HEAD request", e);
                } catch (URISyntaxException e) {
                    logger.log(LogService.LOG_ERROR, "Redirect failed", e);
                }
            }
        }
        return false;
    }

    @Override
    public Download createDownload(IVideoPage page) throws IOException, URISyntaxException, PlaylistFileFoundException {
        return new HlsDownload(page, logger);
    }

    @Validate
    public void start() {
        valid = true;
    }

    @Invalidate
    public void stop() {
        valid = false;
    }
}
