package android.provider;

import android.net.Uri;

public final class MediaStore {
    public static final String ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE", ACTION_VIDEO_CAPTURE = "android.media.action.VIDEO_CAPTURE",
            EXTRA_OUTPUT = "output", ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES", VOLUME_EXTERNAL = "external", VOLUME_EXTERNAL_PRIMARY = "external_primary";

    public interface MediaColumns {
        String DISPLAY_NAME = "_display_name", MIME_TYPE = "mime_type", RELATIVE_PATH = "relative_path", IS_PENDING = "is_pending", SIZE = "_size",
                DATE_ADDED = "date_added", DATE_MODIFIED = "date_modified", DATA = "_data", TITLE = "title", WIDTH = "width", HEIGHT = "height";
    }

    public static final class Images {
        public static final class Media implements MediaColumns {
            public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/images/media");
            public static Uri getContentUri(String volume) { return EXTERNAL_CONTENT_URI; }
        }
    }

    public static final class Video {
        public static final class Media implements MediaColumns {
            public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/video/media");
        }
    }

    public static final class Files {
        public static Uri getContentUri(String volume) { return Uri.parse("content://media/" + volume + "/file"); }
        public interface FileColumns extends MediaColumns {}
    }

    public static final class Downloads implements MediaColumns {
        public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/downloads");
        public static Uri getContentUri(String volume) { return EXTERNAL_CONTENT_URI; }
    }

    private MediaStore() {}
}
