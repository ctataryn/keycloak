package org.keycloak.services.resources.admin;

import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.jboss.resteasy.spi.BadRequestException;
import org.jboss.resteasy.spi.NotAcceptableException;
import org.jboss.resteasy.spi.NotFoundException;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.ForbiddenException;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resources.flows.Flows;
import org.keycloak.util.JsonSerialization;
import org.keycloak.util.PemUtils;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class ClientCertificateResource {
    protected RealmModel realm;
    private RealmAuth auth;
    protected ClientModel client;
    protected KeycloakSession session;

    public ClientCertificateResource(RealmModel realm, RealmAuth auth, ClientModel client, KeycloakSession session) {
        this.realm = realm;
        this.auth = auth;
        this.client = client;
        this.session = session;
    }

    public static class ClientKeyPairInfo {
        protected String privateKey;
        protected String publicKey;
        protected String certificate;

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getCertificate() {
            return certificate;
        }

        public void setCertificate(String certificate) {
            this.certificate = certificate;
        }
    }

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public ClientKeyPairInfo getKeyInfo() {
        ClientKeyPairInfo info = new ClientKeyPairInfo();
        info.setCertificate(client.getAttribute(ClientModel.X509CERTIFICATE));
        info.setPrivateKey(client.getAttribute(ClientModel.PRIVATE_KEY));
        info.setPublicKey(client.getAttribute(ClientModel.PUBLIC_KEY));
        return info;
    }


    @POST
    @NoCache
    @Path("generate")
    @Produces(MediaType.APPLICATION_JSON)
    public ClientKeyPairInfo generate() {
        auth.requireManage();

        KeycloakModelUtils.generateClientKeyPairCertificate(client);
        ClientKeyPairInfo info = new ClientKeyPairInfo();
        info.setCertificate(client.getAttribute(ClientModel.X509CERTIFICATE));
        info.setPrivateKey(client.getAttribute(ClientModel.PRIVATE_KEY));
        info.setPublicKey(client.getAttribute(ClientModel.PUBLIC_KEY));
        return info;
    }

    @POST
    @Path("upload/jks")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public ClientKeyPairInfo uploadJks(@Context final UriInfo uriInfo, MultipartFormDataInput input) throws IOException {
        auth.requireManage();
        Map<String, List<InputPart>> uploadForm = input.getFormDataMap();
        List<InputPart> inputParts = uploadForm.get("file");

        String keystoreFormat = uploadForm.get("keystoreFormat").get(0).getBodyAsString();
        String keyAlias = uploadForm.get("keyAlias").get(0).getBodyAsString();
        String keyPassword = uploadForm.get("keyPassword").get(0).getBodyAsString();
        String storePassword = uploadForm.get("storePassword").get(0).getBodyAsString();
        System.out.println("format = '" + keystoreFormat + "'");
        PrivateKey privateKey = null;
        X509Certificate certificate = null;
        try {
            KeyStore keyStore = null;
            if (keystoreFormat.equals("JKS")) keyStore = KeyStore.getInstance("JKS");
            else keyStore = KeyStore.getInstance(keystoreFormat, "BC");
            keyStore.load(inputParts.get(0).getBody(InputStream.class, null), storePassword.toCharArray());
            privateKey = (PrivateKey)keyStore.getKey(keyAlias, keyPassword.toCharArray());
            certificate = (X509Certificate)keyStore.getCertificate(keyAlias);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String privateKeyPem = KeycloakModelUtils.getPemFromKey(privateKey);
        String publicKeyPem = KeycloakModelUtils.getPemFromKey(certificate.getPublicKey());
        String certPem = KeycloakModelUtils.getPemFromCertificate(certificate);
        client.setAttribute(ClientModel.PRIVATE_KEY, privateKeyPem);
        client.setAttribute(ClientModel.PUBLIC_KEY, publicKeyPem);
        client.setAttribute(ClientModel.X509CERTIFICATE, certPem);

        ClientKeyPairInfo info = new ClientKeyPairInfo();
        info.setCertificate(client.getAttribute(ClientModel.X509CERTIFICATE));
        info.setPrivateKey(client.getAttribute(ClientModel.PRIVATE_KEY));
        info.setPublicKey(client.getAttribute(ClientModel.PUBLIC_KEY));


        return info;
    }


    public static class KeyStoreConfig {
        protected Boolean realmCertificate;
        protected String storePassword;
        protected String keyPassword;
        protected String keyAlias;
        protected String realmAlias;
        protected String format;

        public Boolean isRealmCertificate() {
            return realmCertificate;
        }

        public void setRealmCertificate(Boolean realmCertificate) {
            this.realmCertificate = realmCertificate;
        }

        public String getStorePassword() {
            return storePassword;
        }

        public void setStorePassword(String storePassword) {
            this.storePassword = storePassword;
        }

        public String getKeyPassword() {
            return keyPassword;
        }

        public void setKeyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
        }

        public String getKeyAlias() {
            return keyAlias;
        }

        public void setKeyAlias(String keyAlias) {
            this.keyAlias = keyAlias;
        }

        public String getRealmAlias() {
            return realmAlias;
        }

        public void setRealmAlias(String realmAlias) {
            this.realmAlias = realmAlias;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }
    }

    @POST
    @NoCache
    @Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Consumes(MediaType.APPLICATION_JSON)
    public byte[] getKeystore(final KeyStoreConfig config) {
        auth.requireView();
        if (config.getFormat() != null && !config.getFormat().equals("JKS") && !config.getFormat().equals("PKCS12")) {
            throw new NotAcceptableException("Only support jks format.");
        }
        String format = config.getFormat();
        if (client.getAttribute(ClientModel.PRIVATE_KEY) == null) {
            throw new NotFoundException("keypair not generated for client");
        }
        if (config.getKeyPassword() == null) {
            throw new BadRequestException("Need to specify a key password for jks download");
        }
        if (config.getStorePassword() == null) {
            throw new BadRequestException("Need to specify a store password for jks download");
        }
        final KeyStore keyStore;
        try {
            if (format.equals("JKS")) keyStore = KeyStore.getInstance("JKS");
            else keyStore = KeyStore.getInstance(format, "BC");
            keyStore.load(null, null);
            String keyAlias = config.getKeyAlias();
            if (keyAlias == null) keyAlias = client.getClientId();
            PrivateKey privateKey = PemUtils.decodePrivateKey(client.getAttribute(ClientModel.PRIVATE_KEY));
            X509Certificate clientCert = PemUtils.decodeCertificate(client.getAttribute(ClientModel.X509CERTIFICATE));


            Certificate[] chain =  {clientCert};

            keyStore.setKeyEntry(keyAlias, privateKey, config.getKeyPassword().trim().toCharArray(), chain);

            if (config.isRealmCertificate() == null || config.isRealmCertificate().booleanValue()) {
                X509Certificate certificate = realm.getCertificate();
                if (certificate == null) {
                    KeycloakModelUtils.generateRealmCertificate(realm);
                    certificate = realm.getCertificate();
                }
                String certificateAlias = config.getRealmAlias();
                if (certificateAlias == null) certificateAlias = realm.getName();
                keyStore.setCertificateEntry(certificateAlias, certificate);

            }
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            keyStore.store(stream, config.getStorePassword().trim().toCharArray());
            stream.flush();
            stream.close();
            byte[] rtn = stream.toByteArray();
            return rtn;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}
