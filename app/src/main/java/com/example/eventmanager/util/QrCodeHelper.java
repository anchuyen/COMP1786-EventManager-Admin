package com.example.eventmanager.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.NonNull;

import com.example.eventmanager.model.Event;
import com.example.eventmanager.model.Session;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Generates check-in QR codes for sessions - coursework feature (f), using the
 * ZXing ("Zebra Crossing") library.
 * <p>
 * The QR code contains a small JSON text, for example:
 * <pre>
 * {"type":"session-checkin","eventId":3,"eventName":"Tech Summit","date":"25/12/2026",
 *  "sessionId":7,"sessionName":"Opening Keynote","time":"09:30","room":"Hall 1"}
 * </pre>
 * The IDs let a check-in scanner find the exact session in the cloud database;
 * the names and times make the code readable by any normal QR scanner app too.
 */
public final class QrCodeHelper {

    /** Identifies our QR codes, so a scanner can ignore unrelated ones. */
    private static final String PAYLOAD_TYPE = "session-checkin";

    /** Private constructor: utility class, should not be instantiated. */
    private QrCodeHelper() {
    }

    /** Builds the JSON text stored inside the QR code of one session. */
    @NonNull
    public static String buildSessionPayload(@NonNull Event event, @NonNull Session session) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", PAYLOAD_TYPE);
            json.put("eventId", event.getId());
            json.put("eventName", event.getName());
            json.put("date", DateUtils.formatForInput(event.getDateMillis()));
            json.put("sessionId", session.getId());
            json.put("sessionName", session.getName());
            json.put("time", session.getTime());
            if (session.getRoom() != null) {
                json.put("room", session.getRoom());
            }
            return json.toString();
        } catch (JSONException e) {
            // Only happens with invalid numbers (NaN/Infinity), which we never put in
            return PAYLOAD_TYPE + ":" + session.getId();
        }
    }

    /**
     * Draws the QR code as a square black-and-white bitmap.
     *
     * @param content text to encode (UTF-8, so Vietnamese characters work)
     * @param sizePx  width and height of the bitmap in pixels
     * @throws WriterException if the text is too long to fit in a QR code
     */
    @NonNull
    public static Bitmap generate(@NonNull String content, int sizePx) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        // Medium error correction: the code still scans if ~15% of it is damaged or blurred
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1); // thin white border ("quiet zone")

        // BitMatrix = a grid of true (black) / false (white) squares
        BitMatrix matrix = new QRCodeWriter()
                .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                pixels[rowStart + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
