import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;

/**
 * 把手环 RPK 用的 private.pem + certificate.pem 转成 Android 签名用的 PKCS12 keystore.
 * 用法: java MakeKey <private.pem> <certificate.pem> <out.jks> <storepass> <alias>
 */
public class MakeKey {
    public static void main(String[] args) throws Exception {
        String privPath = args[0];
        String certPath = args[1];
        String outPath = args[2];
        String pass = args[3];
        String alias = args[4];

        // 私钥: PKCS#8
        String privPem = new String(Files.readAllBytes(Paths.get(privPath)));
        String privB64 = stripPem(privPem);
        byte[] privDer = Base64.getMimeDecoder().decode(privB64);
        PrivateKey priv = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(privDer));

        // 证书
        String certPem = new String(Files.readAllBytes(Paths.get(certPath)));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (InputStream in = new ByteArrayInputStream(certPem.getBytes())) {
            cert = (X509Certificate) cf.generateCertificate(in);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, priv, pass.toCharArray(), new java.security.cert.Certificate[]{cert});
        try (FileOutputStream fos = new FileOutputStream(outPath)) {
            ks.store(fos, pass.toCharArray());
        }
        System.out.println("OK: " + outPath + " alias=" + alias);
    }

    static String stripPem(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String line : pem.split("\n")) {
            String l = line.trim();
            if (l.startsWith("-----")) continue;
            sb.append(l);
        }
        return sb.toString();
    }
}
