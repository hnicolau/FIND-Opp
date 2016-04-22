package ul.fcul.lasige.find.lib.data;

import android.os.Bundle;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Created by hugonicolau on 03/11/2015.
 *
 * This class is responsible for reading the protocols' XML file
 */
public class ProtocolDefinitionParser {
    private static final String TAG = ProtocolDefinitionParser.class.getSimpleName();

    // parser object
    private final XmlPullParser mParser;
    // indicates whether we started to read protocols
    private boolean inProtocolsSection = false;
    // indicates whether all protocols were already read
    private boolean allProtocolsParsed = false;

    // constructor
    public ProtocolDefinitionParser(XmlPullParser parser) {
        mParser = parser;
    }

    /**
     *
     * @return Returns true if more protocols need to be parsed
     */
    private boolean findProtocols() {
        try {
            int eventType = mParser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && mParser.getName().equals("protocols")) {
                    if (!mParser.isEmptyElementTag()) {
                        inProtocolsSection = true;
                        return true;
                    }
                    break;
                }
                eventType = mParser.next();
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Error while looking for <protocols> start tag", e);
        }

        allProtocolsParsed = true;
        return false;
    }

    /**
     *
     * @return Returns next protocol description in a Bundle. Identifiers are described in FindContract
     *
     * @see ul.fcul.lasige.find.lib.data.FindContract.Protocols
     */
    public Bundle nextProtocol() {
        if (allProtocolsParsed || (!inProtocolsSection && !findProtocols())) {
            return null;
        }

        try {
            if (mParser.nextTag() == XmlPullParser.END_TAG) {
                allProtocolsParsed = true;
                return null;
            }

            Log.v(TAG, "Parsing protocol:");
            Bundle protocolDefinition = new Bundle();
            while (mParser.nextTag() == XmlPullParser.START_TAG) {
                String tagName = mParser.getName();
                String content = mParser.nextText();

                switch (tagName) {
                    case "name": {
                        protocolDefinition.putString(
                                FindContract.Protocols.COLUMN_IDENTIFIER, content);
                        Log.v(TAG, "\tname: " + content);
                        break;
                    }

                    case "encrypted": {
                        boolean isEncrypted = Boolean.parseBoolean(content);
                        protocolDefinition.putBoolean(
                                FindContract.Protocols.COLUMN_ENCRYPTED, isEncrypted);
                        Log.v(TAG, "\tencrypted: " + isEncrypted);
                        break;
                    }

                    case "authenticated": {
                        boolean isAuthenticated = Boolean.parseBoolean(content);
                        protocolDefinition.putBoolean(
                                FindContract.Protocols.COLUMN_SIGNED, isAuthenticated);
                        Log.v(TAG, "\tauthenticated: " + isAuthenticated);
                        break;
                    }
                    case "endpoint": {
                        protocolDefinition.putString(
                                FindContract.Protocols.COLUMN_ENDPOINT, content);
                        Log.v(TAG, "\tendpoint: " + content);
                        break;
                    }
                    case "download_endpoint": {
                        protocolDefinition.putString(
                                FindContract.Protocols.COLUMN_DOWNLOAD_ENDPOINT, content);
                        Log.v(TAG, "\tendpoint: " + content);
                        break;
                    }
                    case "defaultTTL": {
                        int ttl = Integer.parseInt(content);
                        protocolDefinition.putInt(
                                FindContract.Protocols.COLUMN_DEFAULT_TTL, ttl);
                        Log.v(TAG, "\tdefaultTTL: " + ttl);
                        break;
                    }

                    default: {
                        Log.d(TAG, String.format(
                                "Unknown tag in protocol definition: <%1$s>%2$s</%1$s>",
                                tagName, content));
                    }
                }

                if (mParser.getEventType() != XmlPullParser.END_TAG) {
                    mParser.next();
                }
            }
            return protocolDefinition;
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Error while parsing <protocol> tag", e);
        }

        return null;
    }

}
