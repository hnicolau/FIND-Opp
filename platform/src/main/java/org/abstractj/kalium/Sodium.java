/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 2.0.10
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.abstractj.kalium;

public class Sodium {

  static {
    System.loadLibrary("sodium");
  }

  public static String sodium_version_string() {
    return SodiumJNI.sodium_version_string();
  }

  public static int crypto_hash_sha256(byte[] out, byte[] in, int inlen) {
    return SodiumJNI.crypto_hash_sha256(out, in, inlen);
  }

  public static int crypto_hash_sha512(byte[] out, byte[] in, int inlen) {
    return SodiumJNI.crypto_hash_sha512(out, in, inlen);
  }

  public static int crypto_generichash_blake2b(byte[] out, long outlen, byte[] in, int inlen, byte[] key, long keylen) {
    return SodiumJNI.crypto_generichash_blake2b(out, outlen, in, inlen, key, keylen);
  }

  public static int crypto_box_curve25519xsalsa20poly1305_keypair(byte[] pk, byte[] sk) {
    return SodiumJNI.crypto_box_curve25519xsalsa20poly1305_keypair(pk, sk);
  }

  public static void randombytes(byte[] buf, int size) {
    SodiumJNI.randombytes(buf, size);
  }

  public static int crypto_box_easy(byte[] c, byte[] m, int mlen, byte[] n, byte[] pk, byte[] sk) {
    return SodiumJNI.crypto_box_easy(c, m, mlen, n, pk, sk);
  }

  public static int crypto_box_open_easy(byte[] m, byte[] c, int clen, byte[] n, byte[] pk, byte[] sk) {
    return SodiumJNI.crypto_box_open_easy(m, c, clen, n, pk, sk);
  }

  public static int crypto_box_curve25519xsalsa20poly1305(byte[] c, byte[] m, int mlen, byte[] n, byte[] pk, byte[] sk) {
    return SodiumJNI.crypto_box_curve25519xsalsa20poly1305(c, m, mlen, n, pk, sk);
  }

  public static int crypto_box_curve25519xsalsa20poly1305_open(byte[] m, byte[] c, int clen, byte[] n, byte[] pk, byte[] sk) {
    return SodiumJNI.crypto_box_curve25519xsalsa20poly1305_open(m, c, clen, n, pk, sk);
  }

  public static int crypto_scalarmult_curve25519(byte[] q, byte[] n, byte[] p) {
    return SodiumJNI.crypto_scalarmult_curve25519(q, n, p);
  }

  public static int crypto_secretbox_xsalsa20poly1305(byte[] c, byte[] m, int mlen, byte[] n, byte[] k) {
    return SodiumJNI.crypto_secretbox_xsalsa20poly1305(c, m, mlen, n, k);
  }

  public static int crypto_secretbox_xsalsa20poly1305_open(byte[] m, byte[] c, int clen, byte[] n, byte[] k) {
    return SodiumJNI.crypto_secretbox_xsalsa20poly1305_open(m, c, clen, n, k);
  }

  public static int crypto_sign_ed25519_convert_key(byte[] curvepk, byte[] edpk) {
    return SodiumJNI.crypto_sign_ed25519_convert_key(curvepk, edpk);
  }

  public static int crypto_sign_ed25519_seed_keypair(byte[] pk, byte[] sk, byte[] seed) {
    return SodiumJNI.crypto_sign_ed25519_seed_keypair(pk, sk, seed);
  }

  public static int crypto_sign_ed25519(byte[] sm, int[] smlen, byte[] m, int mlen, byte[] sk) {
    return SodiumJNI.crypto_sign_ed25519(sm, smlen, m, mlen, sk);
  }

  public static int crypto_sign_ed25519_open(byte[] m, int[] mlen, byte[] sm, int smlen, byte[] pk) {
    return SodiumJNI.crypto_sign_ed25519_open(m, mlen, sm, smlen, pk);
  }

}
