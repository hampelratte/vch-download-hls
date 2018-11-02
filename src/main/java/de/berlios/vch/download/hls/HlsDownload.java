package de.berlios.vch.download.hls;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.osgi.service.log.LogService;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.Format;
import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.ParsingMode;
import com.iheartradio.m3u8.PlaylistException;
import com.iheartradio.m3u8.PlaylistParser;
import com.iheartradio.m3u8.data.MasterPlaylist;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.PlaylistData;
import com.iheartradio.m3u8.data.StreamInfo;
import com.iheartradio.m3u8.data.TrackData;

import de.berlios.vch.download.AbstractDownload;
import de.berlios.vch.download.SpeedometerInputStream;
import de.berlios.vch.parser.IVideoPage;

public class HlsDownload extends AbstractDownload {

    private LogService logger;

    private File file;

    private float speed;

    private IVideoPage video;

    int currentSegment = 0;

    public HlsDownload(IVideoPage video, LogService logger) {
        super(video);
        this.video = video;
        this.logger = logger;
    }

    private int progress = -1;

    @Override
    public int getProgress() {
        return progress;
    }

    @Override
    public void run() {
        HttpURLConnection con = null;
        RandomAccessFile out = null;
        SpeedometerInputStream in = null;
        try {
            file = new File(getLocalFile());

            setStatus(Status.DOWNLOADING);
            String segments = parseMaster(video.getVideoUri().toString());
            if(segments != null) {
                out = new RandomAccessFile(file, "rw");
                out.seek(out.length());
                LiveStreamingPlaylist lsp = parseSegments(segments);
                double totalSegments = lsp.segments.size();
                for (int i = currentSegment; i < lsp.segments.size(); i++) {
                    String segment = lsp.segments.get(currentSegment);
                    if(Thread.currentThread().isInterrupted() || getStatus() == Status.STOPPED) {
                        setStatus(Status.STOPPED);
                        return;
                    }

                    // try to download segment. at max 2 retries
                    for (int j = 0; j < 3; j++) {
                        try {
                            URL url = new URL(segment);
                            in = new SpeedometerInputStream(url.openStream());
                            byte[] b = new byte[1024];
                            int length = -1;
                            while( (length = in.read(b)) >= 0 ) {
                                out.write(b, 0, length);
                                speed = in.getSpeed();
                            }
                            break;
                        } catch(Exception e) {
                            if(j == 2) {
                                logger.log(LogService.LOG_ERROR, "Downloading segment " + currentSegment + " finally failed", e);
                            } else {
                                logger.log(LogService.LOG_DEBUG, "Downloading segment " + currentSegment + " failed. Retrying...");
                            }
                        } finally {
                            in.close();
                        }
                    }
                    currentSegment++;
                    progress = (int) (currentSegment / totalSegments * 100);
                }
                setStatus(Status.FINISHED);
            } else {
                throw new IOException("No segments found");
            }
        } catch (MalformedURLException e) {
            error("Not a valid URL " + getVideoPage().getVideoUri().toString(), e);
            setStatus(Status.FAILED);
            setException(e);
        } catch (IOException e) {
            error("Couldn't download file from " + getVideoPage().getVideoUri().toString(), e);
            setStatus(Status.FAILED);
            setException(e);
        } catch (ParseException e) {
            error("Couldn't parse playlist file " + getVideoPage().getVideoUri().toString(), e);
            setStatus(Status.FAILED);
            setException(e);
        } catch (PlaylistException e) {
            error("Couldn't parse playlist file " + getVideoPage().getVideoUri().toString(), e);
            setStatus(Status.FAILED);
            setException(e);
        } finally {
            if(in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    logger.log(LogService.LOG_WARNING, "Couldn't close HTTP stream", e);
                }
            }

            if(con != null) {
                con.disconnect();
            }

            if(out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    logger.log(LogService.LOG_WARNING, "Couldn't close file", e);
                }
            }
        }
    }

    private LiveStreamingPlaylist parseSegments(String segments) throws IOException, ParseException, PlaylistException {
        logger.log(LogService.LOG_DEBUG, "Downloading segment playlist " + segments);
        URL segmentsUrl = new URL(segments);
        InputStream inputStream = segmentsUrl.openStream();
        PlaylistParser parser = new PlaylistParser(inputStream, Format.EXT_M3U, Encoding.UTF_8);
        Playlist playlist = parser.parse();
        if(playlist.hasMediaPlaylist()) {
            MediaPlaylist mediaPlaylist = playlist.getMediaPlaylist();
            LiveStreamingPlaylist lsp = new LiveStreamingPlaylist();
            lsp.seq = mediaPlaylist.getMediaSequenceNumber();
            lsp.targetDuration = mediaPlaylist.getTargetDuration();
            List<TrackData> tracks = mediaPlaylist.getTracks();
            for (TrackData trackData : tracks) {
                String uri = trackData.getUri();
                lsp.totalDuration += trackData.getTrackInfo().duration;
                lsp.lastSegDuration = trackData.getTrackInfo().duration;
                if(!uri.startsWith("http")) {
                    String _url = segmentsUrl.toString();
                    _url = _url.substring(0, _url.lastIndexOf('/') + 1);
                    String segmentUri = _url + uri;
                    lsp.segments.add(segmentUri);
                } else {
                    lsp.segments.add(uri);
                }
            }
            return lsp;
        }
        return null;
    }

    private String parseMaster(String url) throws IOException, ParseException, PlaylistException {
        logger.log(LogService.LOG_DEBUG, "Downloading master playlist " + url);
        URL masterUrl = new URL(url);
        InputStream inputStream = masterUrl.openStream();
        PlaylistParser parser = new PlaylistParser(inputStream, Format.EXT_M3U, Encoding.UTF_8, ParsingMode.LENIENT);
        Playlist playlist = parser.parse();
        if(playlist.hasMasterPlaylist()) {
            MasterPlaylist master = playlist.getMasterPlaylist();
            System.out.println(master.getPlaylists());
            PlaylistData bestQuality = getBestQualityStream(master.getPlaylists());
            String uri = bestQuality.getUri();
            if(!uri.startsWith("http")) {
                String _masterUrl = masterUrl.toString();
                _masterUrl = _masterUrl.substring(0, _masterUrl.lastIndexOf('/') + 1);
                String segmentUri = _masterUrl + uri;
                return segmentUri;
            } else {
                return uri;
            }
        }
        return null;
    }

    private PlaylistData getBestQualityStream(List<PlaylistData> playlists) {
        PlaylistData best = playlists.get(0);
        int bestWidth = 0;
        int bestBandwidth = 0;
        for (PlaylistData playlistData : playlists) {
            if(playlistData.hasStreamInfo()) {
                StreamInfo info = playlistData.getStreamInfo();
                if(info.hasResolution()) {
                    int width = info.getResolution().width;
                    if(width > bestWidth) {
                        best = playlistData;
                        bestWidth = info.getResolution().width;
                        bestBandwidth = info.getBandwidth();
                    } else if(width == bestWidth) {
                        int bandwidth = info.getBandwidth();
                        if(bandwidth > bestBandwidth) {
                            best = playlistData;
                            bestBandwidth = info.getBandwidth();
                        }
                    }
                } else if(info.getBandwidth() > bestBandwidth) {
                    best = playlistData;
                    bestBandwidth = info.getBandwidth();
                }
            }
        }
        return best;
    }

    public static class LiveStreamingPlaylist {
        public int seq = 0;
        public float totalDuration = 0;
        public float lastSegDuration = 0;
        public float targetDuration = 0;
        public List<String> segments = new ArrayList<String>();
    }

    @Override
    public void cancel() {
        // delete the video file
        if(file != null && file.exists()) {
            boolean deleted = file.delete();
            if(!deleted) {
                logger.log(LogService.LOG_WARNING, "Couldn't delete file " + file.getAbsolutePath());
            }
        }
    }

    @Override
    public boolean isPauseSupported() {
        return true;
    }

    @Override
    public float getSpeed() {
        if(getStatus() == Status.DOWNLOADING) {
            return speed;
        } else {
            return -1;
        }
    }

    @Override
    public void stop() {
        setStatus(Status.STOPPED);
    }

    @Override
    public String getLocalFile() {
        URI uri = getVideoPage().getVideoUri();
        String path = uri.getPath();
        String _file = path.substring(path.lastIndexOf('/') + 1);
        String title = getVideoPage().getTitle().replaceAll("[^a-zA-z0-9]", "_");
        return getDestinationDir() + File.separator + title + "_" + _file + ".merged.ts";
    }
}