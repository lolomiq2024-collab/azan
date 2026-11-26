package com.loai.azanhome;

import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * أندرويد 7 وأقدم لا يعرف جذور Let's Encrypt (ISRG) التي يستخدمها GitHub لملفاته،
 * فيفشل تحميل القوائم. نضيف هذين الجذرين إلى جذور النظام لاتصالات التطبيق فقط،
 * ويبقى التحقق من الشهادة ومن اسم الموقع كاملاً.
 */
final class Tls {

    private static final String[] ROOTS = {"certs/isrg_root_x1.pem", "certs/isrg_root_x2.pem"};

    private static SSLSocketFactory factory;

    private Tls() {
    }

    /** يُستدعى قبل الاتصال. على الأجهزة الأحدث لا يغيّر شيئاً. */
    static void apply(Context context, URLConnection connection) {
        if (Build.VERSION.SDK_INT > 25 || !(connection instanceof HttpsURLConnection)) return;
        SSLSocketFactory f = factory(context);
        if (f != null) ((HttpsURLConnection) connection).setSSLSocketFactory(f);
    }

    private static synchronized SSLSocketFactory factory(Context context) {
        if (factory != null) return factory;
        InputStream[] pems = new InputStream[ROOTS.length];
        try {
            for (int i = 0; i < ROOTS.length; i++) pems[i] = context.getAssets().open(ROOTS[i]);
            factory = socketFactory(combine(manager(null), manager(keyStore(pems))));
        } catch (IOException | GeneralSecurityException ignored) {
            // يبقى الاتصال بجذور النظام
        } finally {
            for (InputStream in : pems) {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return factory;
    }

    static KeyStore keyStore(InputStream[] pems) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < pems.length; i++) {
            store.setCertificateEntry("root" + i, certificates.generateCertificate(pems[i]));
        }
        return store;
    }

    /** مدير ثقة لمخزن شهادات معيّن، أو لجذور النظام إن كان null. */
    static X509TrustManager manager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(store);
        for (TrustManager m : f.getTrustManagers()) {
            if (m instanceof X509TrustManager) return (X509TrustManager) m;
        }
        throw new GeneralSecurityException("no X509TrustManager");
    }

    /** تُقبل الشهادة إن وثق بها النظام، وإلا فيجب أن تنتهي سلسلتها بأحد الجذور المضافة. */
    static X509TrustManager combine(final X509TrustManager system, final X509TrustManager extra) {
        return new X509TrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, type);
                } catch (CertificateException bySystem) {
                    try {
                        extra.checkServerTrusted(chain, type);
                    } catch (CertificateException byExtra) {
                        throw bySystem;
                    }
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                system.checkClientTrusted(chain, type);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return system.getAcceptedIssuers();
            }
        };
    }

    static SSLSocketFactory socketFactory(X509TrustManager manager) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{manager}, null);
        return context.getSocketFactory();
    }
}
