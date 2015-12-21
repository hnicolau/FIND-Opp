package ul.fcul.lasige.find.utils;

import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;

/**
 * Class with utility methods to convert encode byte arrays to hexadecimal strings.
 * This class is not instantiable.
 *
 * Created by hugonicolau on 05/11/2015.
 */
public class ByteUtils {
    private final static BaseEncoding HEX = BaseEncoding.base16();

    /**
     * Constructor.
     */
    private ByteUtils() { }

    /**
     * Encodes a given byte array and returns an hexadecimal string.
     * @param bytes Byte array.
     * @return Hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes) {
        return HEX.encode(bytes);
    }

    /**
     * Encodes a given byte array with a specified range and returns an hexadecimal string.
     * @param bytes Byte array.
     * @param length Specified range in the byte array.
     * @return Hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes, int length) {
        return HEX.encode(bytes, 0, length);
    }

    /**
     * Encodes a given {@link ByteString} and returns an hexadecimal string.
     * @param bytes ByteString.
     * @return Hexadecimal string.
     */
    public static String bytesToHex(ByteString bytes) {
        return bytesToHex(bytes.toByteArray());
    }

    /**
     * Encodes a given {@link ByteString} with a specified range and returns an hexadecimal string.
     * @param bytes ByteString.
     * @param length Specified range in the bytes.
     * @return Hexadecimal string.
     */
    public static String bytesToHex(ByteString bytes, int length) {
        return bytesToHex(bytes.toByteArray(), length);
    }
}
