package ul.fcul.lasige.find.apps;

import java.util.Random;

/**
 *
 * Utility class to generate alphanumeric tokens
 *
 * Created by hugonicolau on 04/11/2015.
 */
public class TokenGenerator {
    // pool of alphanumeric characters
    private static final String POOL = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    // pool length
    private static final int POOL_LENGTH = POOL.length();
    // random object
    private static final Random RND = new Random();

    /**
     * Constructor
     */
    private TokenGenerator() {}

    /**
     * Utility method that generates an alphanumeric token.
     * @param length Length of requested token.
     * @return Generated token.
     */
    public static String generateToken(int length) {
        // build an empty string with given length
        final StringBuilder token = new StringBuilder(length);
        // for each position of the string
        for (int i = 0; i < length; i++) {
            // randomly chooses a character from the pool
            token.append(POOL.charAt(RND.nextInt(POOL_LENGTH)));
        }
        return token.toString();
    }
}
