package ul.fcul.lasige.find.apps;

import java.util.Random;

/**
 * Created by hugonicolau on 04/11/2015.
 */
public class TokenGenerator {
    private static final String POOL = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int POOL_LENGTH = POOL.length();
    private static final Random RND = new Random();

    private TokenGenerator() {
    }

    public static final String generateToken(int length) {
        final StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(POOL.charAt(RND.nextInt(POOL_LENGTH)));
        }
        return token.toString();
    }
}
