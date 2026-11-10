package com.tv.memcleaner;

/*
 * Derived from the AndroidTvMouse project (AdbKey.java), licensed GPL-3.0.
 * https://github.com/naswww/AndroidTvMouse
 *
 * ADB auth RSA key. Prefers assets/adbkey + adbkey.pub (a key the TV already
 * trusts, so no authorization popup ever). Falls back to a runtime-generated
 * key saved in SharedPreferences (may require re-authorizing on some ROMs).
 */
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

final class AdbKey {

    private static final String PREF = "adbkey";
    private static final String NAME = "tvcleaner@tv";

    private final KeyPair kp;
    private final byte[] embeddedPayload;

    AdbKey(Context ctx) throws Exception {
        byte[] payload = null;
        KeyPair pair = null;
        try {
            pair = loadPrivate(ctx);
            payload = loadPublicBlob(ctx);
        } catch (Throwable ignored) {
            pair = null;
            payload = null;
        }

        if (pair != null && payload != null) {
            kp = pair;
            embeddedPayload = payload;
            return;
        }

        embeddedPayload = null;
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String priv = sp.getString("priv", null);
        String pub = sp.getString("pub", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv != null && pub != null) {
            PrivateKey sk = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
            java.security.PublicKey pk = kf.generatePublic(
                    new java.security.spec.X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)));
            kp = new KeyPair(pk, sk);
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            kp = g.generateKeyPair();
            sp.edit()
                    .putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                    .apply();
        }
    }

    boolean isEmbedded() { return embeddedPayload != null; }

    /**
     * ADB treats the 20-byte token as an already-computed SHA-1 digest, so we
     * hand-build PKCS#1 v1.5 DigestInfo instead of using "SHA1withRSA".
     */
    byte[] sign(byte[] token) throws Exception {
        byte[] prefix = {
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        byte[] info = new byte[prefix.length + token.length];
        System.arraycopy(prefix, 0, info, 0, prefix.length);
        System.arraycopy(token, 0, info, prefix.length, token.length);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, kp.getPrivate());
        return c.doFinal(info);
    }

    /** AUTH RSAPUBLICKEY payload: base64(RSAPublicKey struct) + " name" + NUL. */
    byte[] publicKeyPayload() {
        if (embeddedPayload != null) {
            byte[] out = new byte[embeddedPayload.length + 1];
            System.arraycopy(embeddedPayload, 0, out, 0, embeddedPayload.length);
            out[embeddedPayload.length] = 0;
            return out;
        }
        return buildPayload((RSAPrivateKey) kp.getPrivate());
    }

    private static byte[] buildPayload(RSAPrivateKey priv) {
        BigInteger n = priv.getModulus();
        BigInteger e = BigInteger.valueOf(65537);
        final int MOD = 256;
        byte[] mod = toLE(n, MOD);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.modInverse(r32).negate().mod(r32);
        BigInteger R = BigInteger.ONE.shiftLeft(MOD * 8);
        BigInteger rr = R.multiply(R).mod(n);
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + MOD + MOD + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(64);
        buf.putInt(n0inv.intValue());
        buf.put(mod);
        buf.put(toLE(rr, MOD));
        buf.putInt(e.intValue());
        String b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        byte[] s = (b64 + " " + NAME).getBytes();
        byte[] out = new byte[s.length + 1];
        System.arraycopy(s, 0, out, 0, s.length);
        out[s.length] = 0;
        return out;
    }

    private static KeyPair loadPrivate(Context ctx) throws Exception {
        String pem = readAsset(ctx, "adbkey")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        PrivateKey sk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        return new KeyPair(null, sk);
    }

    private static byte[] loadPublicBlob(Context ctx) throws Exception {
        return readAsset(ctx, "adbkey.pub").trim().getBytes("UTF-8");
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static byte[] toLE(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        int n = Math.min(be.length, len);
        for (int i = 0; i < n; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
