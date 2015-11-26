/**
 * Copyright 2013 Bruno Oliveira, and individual contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.abstractj.kalium.crypto;

import org.abstractj.kalium.Sodium;
import org.abstractj.kalium.SodiumConstants;
import org.abstractj.kalium.encoders.Encoder;

public class SecretBox {

    private byte[] key;

    public SecretBox(byte[] key) {
        this.key = key;
        Util.checkLength(key, SodiumConstants.XSALSA20_POLY1305_SECRETBOX_KEYBYTES);
    }

    public SecretBox(String key, Encoder encoder) {
        this(encoder.decode(key));
    }

    public byte[] encrypt(byte[] nonce, byte[] message) {
        Util.checkLength(nonce, SodiumConstants.XSALSA20_POLY1305_SECRETBOX_NONCEBYTES);
        byte[] msg = Util.prependZeros(SodiumConstants.ZERO_BYTES, message);
        byte[] ct = Util.zeros(msg.length);
        Util.isValid(Sodium.crypto_secretbox_xsalsa20poly1305(
                ct, msg, msg.length, nonce, key), "Encryption failed");
        return Util.removeZeros(SodiumConstants.BOXZERO_BYTES, ct);
    }

    public byte[] decrypt(byte[] nonce, byte[] ciphertext) {
        Util.checkLength(nonce, SodiumConstants.XSALSA20_POLY1305_SECRETBOX_NONCEBYTES);
        byte[] ct = Util.prependZeros(SodiumConstants.BOXZERO_BYTES, ciphertext);
        byte[] message = Util.zeros(ct.length);
        Util.isValid(Sodium.crypto_secretbox_xsalsa20poly1305_open(
                        message, ct, ct.length, nonce, key),
                "Decryption failed. Ciphertext failed verification");
        return Util.removeZeros(SodiumConstants.ZERO_BYTES, message);
    }
}
