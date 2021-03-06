/*
 * Copyright (c) 2020. Eugen Covaci
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.kpax.winfoom.config;

import com.fasterxml.jackson.annotation.*;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.*;
import org.apache.commons.configuration2.builder.*;
import org.apache.commons.configuration2.builder.fluent.*;
import org.apache.commons.configuration2.ex.*;
import org.apache.commons.lang3.*;
import org.apache.http.*;
import org.kpax.winfoom.api.json.*;
import org.kpax.winfoom.exception.*;
import org.kpax.winfoom.proxy.*;
import org.kpax.winfoom.util.*;
import org.kpax.winfoom.util.jna.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.*;

import javax.annotation.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * The proxy facade configuration.
 *
 * @author Eugen Covaci
 */
@JsonPropertyOrder({"proxyType", "proxyHost", "proxyPort", "proxyUsername", "proxyPassword", "proxyStorePassword",
        "proxyPacFileLocation", "blacklistTimeout",
        "localPort", "proxyTestUrl", "autostart", "autodetect"})
@Component
@PropertySource(value = "file:${user.home}/" + SystemConfig.APP_HOME_DIR_NAME + "/" + ProxyConfig.FILENAME,
        ignoreResourceNotFound = true)
public class ProxyConfig {

    public static final String FILENAME = "proxy.properties";

    private final Logger logger = LoggerFactory.getLogger(ProxyConfig.class);

    @Value("${app.version}")
    private String appVersion;

    @Value("${api.port:9999}")
    private Integer apiPort;

    /**
     * default admin:winfoom, base64 encoded
     */
    @Value("${api.userPassword:YWRtaW46d2luZm9vbQ==}")
    private String apiToken;


    @Value("${proxy.type:DIRECT}")
    private Type proxyType;

    @Value("${local.port:3129}")
    private Integer localPort;

    @Value("${proxy.http.host:}")
    private String proxyHttpHost;

    @Value("${proxy.socks5.host:}")
    private String proxySocks5Host;

    @Value("${proxy.socks4.host:}")
    private String proxySocks4Host;

    @Value("${proxy.http.port:0}")
    private Integer proxyHttpPort;

    @Value("${proxy.socks5.port:0}")
    private Integer proxySocks5Port;

    @Value("${proxy.socks4.port:0}")
    private Integer proxySocks4Port;

    @Value("${proxy.test.url:http://example.com}")
    private String proxyTestUrl;

    @Value("${proxy.socks5.username:#{null}}")
    private String proxySocks5Username;

    @Value("${proxy.socks5.password:#{null}}")
    private String proxySocks5Password;

    /**
     * DOMAIN\\username or username
     */
    @Value("${proxy.http.username:#{null}}")
    private String proxyHttpUsername;

    @Value("${proxy.http.password:#{null}}")
    private String proxyHttpPassword;

    @Value("${proxy.storePassword:false}")
    private boolean proxyStorePassword;

    @Value("${proxy.pac.fileLocation:#{null}}")
    private String proxyPacFileLocation;

    @Value("${blacklist.timeout:30}")// minutes
    private Integer blacklistTimeout;

    @Value("${autostart:false}")
    private boolean autostart;

    @Value("${autodetect:false}")
    private boolean autodetect;

    @Value("${http.auth.protocol:#{null}}")
    private HttpAuthProtocol httpAuthProtocol;

    @Value("${KRB5_CONFIG:/etc/krb5.conf}")
    private String krb5ConfFilepath;

    private Path tempDirectory;

    @PostConstruct
    public void init() throws IOException, ConfigurationException {
        File userProperties = Paths.get(System.getProperty("user.home"), SystemConfig.APP_HOME_DIR_NAME,
                ProxyConfig.FILENAME).toFile();

        // Make sure the file exists.
        // If not, create a new one and write the app.version
        if (!userProperties.exists()) {
            userProperties.createNewFile();
            FileBasedConfigurationBuilder<PropertiesConfiguration> propertiesBuilder = new Configurations()
                    .propertiesBuilder(userProperties);
            Configuration config = propertiesBuilder.getConfiguration();
            config.setProperty("app.version", appVersion);
            propertiesBuilder.save();
        }

        logger.info("Check temp directory");
        if (!Files.exists(tempDirectory)) {
            logger.info("Create temp directory {}", tempDirectory);
            Files.createDirectories(tempDirectory);
        } else if (!Files.isDirectory(tempDirectory)) {
            throw new IllegalStateException(
                    String.format("The file [%s] should be a directory, not a regular file", tempDirectory));
        } else {
            logger.info("Using temp directory {}", tempDirectory);
        }
    }


    public boolean isAutoDetectNeeded() {
        return autodetect ||
                ((proxyType.isHttp() || proxyType.isSocks()) && StringUtils.isEmpty(getProxyHost())) ||
                (proxyType.isPac() && StringUtils.isEmpty(proxyPacFileLocation));
    }


    public void validate() throws InvalidProxySettingsException {
        if (proxyType.isHttp() || proxyType.isSocks()) {
            if (StringUtils.isEmpty(getProxyHost())) {
                throw new InvalidProxySettingsException("Missing proxy host");
            }
            if (!HttpUtils.isValidPort(getProxyPort())) {
                throw new InvalidProxySettingsException("Invalid proxy port");
            }
            if (!SystemContext.IS_OS_WINDOWS && proxyType.isHttp()) {
                if (httpAuthProtocol == null) {
                    throw new InvalidProxySettingsException("Missing HTTP proxy authentication protocol");
                }

                if (StringUtils.isEmpty(proxyHttpUsername)) {
                    throw new InvalidProxySettingsException("Missing proxy username");
                } else {
                    int backslashIndex = proxyHttpUsername.indexOf('\\');

                    if (httpAuthProtocol.isKerberos() && backslashIndex < 0) {
                        throw new InvalidProxySettingsException("The proxy username is invalid: should contain the domain in the form DOMAIN\\username");
                    }

                    // Check whether it begins or ends with '\' character
                    if (backslashIndex == 0 ||
                            backslashIndex == proxyHttpUsername.length() - 1) {
                        throw new InvalidProxySettingsException("The proxy username is invalid");
                    }
                }

                if (StringUtils.isEmpty(proxyHttpPassword)) {
                    throw new InvalidProxySettingsException("Missing proxy password");
                }

                if (httpAuthProtocol.isKerberos()) {
                    Path krb5ConfPath = Paths.get(krb5ConfFilepath);
                    if (!Files.exists(krb5ConfPath)) {
                        throw new InvalidProxySettingsException("File not found: " + krb5ConfFilepath);
                    } else if (!Files.isReadable(krb5ConfPath)) {
                        throw new InvalidProxySettingsException("File not readable: " + krb5ConfFilepath);
                    }
                }
            }
        } else if (proxyType.isPac()) {
            if (StringUtils.isEmpty(proxyPacFileLocation)) {
                throw new InvalidProxySettingsException("Missing PAC file location");
            }
        }
    }

    public boolean autoDetect() throws IOException {
        logger.info("Detecting IE proxy settings");
        IEProxyConfig ieProxyConfig = WinHttpHelpers.readIEProxyConfig();
        logger.info("IE settings {}", ieProxyConfig);
        if (ieProxyConfig != null) {
            String pacUrl = WinHttpHelpers.findPacFileLocation(ieProxyConfig);
            if (pacUrl != null) {
                logger.info("Proxy Auto Config file location: {}", pacUrl);
                proxyType = Type.PAC;
                proxyPacFileLocation = pacUrl;
                return true;
            } else {// Manual case
                String proxySettings = ieProxyConfig.getProxy();
                logger.info("Manual proxy settings: [{}]", proxySettings);
                if (proxySettings != null) {
                    if (proxySettings.indexOf('=') == -1) {
                        setProxy(Type.HTTP, proxySettings);
                        return true;
                    } else {
                        Properties properties = new Properties();
                        properties.load(
                                new ByteArrayInputStream(proxySettings.replace(';', '\n').
                                        getBytes(StandardCharsets.ISO_8859_1)));
                        String httpProxy = properties.getProperty("http");
                        if (httpProxy != null) {
                            setProxy(Type.HTTP, httpProxy);
                            return true;
                        } else {
                            String socksProxy = properties.getProperty("socks");
                            if (socksProxy != null) {
                                setProxy(Type.SOCKS5, socksProxy);
                                return true;
                            }
                        }
                    }
                }
            }
        } else {
            logger.warn("Cannot retrieve IE settings");
        }
        return false;
    }

    private void setProxy(Type type, String proxy) {
        logger.info("Set proxy type: {}, value: {}", type, proxy);
        proxyType = type;
        HttpHost httpHost = HttpHost.create(proxy);
        setProxyHost(httpHost.getHostName());
        setProxyPort(httpHost.getPort());
    }

    @JsonView(value = {Views.Settings.class})
    public String getAppVersion() {
        return appVersion;
    }

    @JsonView(value = {Views.Common.class})
    public Integer getLocalPort() {
        return localPort;
    }

    public void setLocalPort(Integer localPort) {
        this.localPort = localPort;
    }

    @JsonView(value = {Views.Http.class, Views.Socks4.class})
    public String getProxyHost() {
        switch (proxyType) {
            case HTTP:
                return proxyHttpHost;
            case SOCKS4:
                return proxySocks4Host;
            case SOCKS5:
                return proxySocks5Host;
        }
        return null;
    }

    public void setProxyHost(String proxyHost) {
        switch (proxyType) {
            case HTTP:
                this.proxyHttpHost = proxyHost;
                break;
            case SOCKS4:
                this.proxySocks4Host = proxyHost;
                break;
            case SOCKS5:
                this.proxySocks5Host = proxyHost;
                break;
        }
    }

    @JsonView(value = {Views.Http.class, Views.Socks4.class})
    public Integer getProxyPort() {
        switch (proxyType) {
            case HTTP:
                return proxyHttpPort;
            case SOCKS4:
                return proxySocks4Port;
            case SOCKS5:
                return proxySocks5Port;
        }
        return 0;
    }

    public void setProxyPort(Integer proxyPort) {
        switch (proxyType) {
            case HTTP:
                this.proxyHttpPort = proxyPort;
                break;
            case SOCKS4:
                this.proxySocks4Port = proxyPort;
                break;
            case SOCKS5:
                this.proxySocks5Port = proxyPort;
                break;
        }
    }

    @JsonView(value = {Views.Common.class})
    public String getProxyTestUrl() {
        return proxyTestUrl;
    }

    public void setProxyTestUrl(String proxyTestUrl) {
        this.proxyTestUrl = proxyTestUrl;
    }


    public Path getTempDirectory() {
        return tempDirectory;
    }

    @JsonView(value = {Views.Common.class})
    public Type getProxyType() {
        return proxyType;
    }

    public void setProxyType(Type proxyType) {
        this.proxyType = proxyType;
    }

    @JsonView(value = {Views.Socks5.class, Views.HttpNonWindows.class})
    public String getProxyUsername() {
        switch (proxyType) {
            case HTTP:
                return proxyHttpUsername;
            case SOCKS5:
                return proxySocks5Username;
        }
        return null;
    }

    public void setProxyUsername(String proxyUsername) {
        switch (proxyType) {
            case HTTP:
                proxyHttpUsername = proxyUsername;
                break;
            case SOCKS5:
                proxySocks5Username = proxyUsername;
                break;
        }
    }

    @Asterisk
    @JsonView(value = {Views.Socks5.class, Views.HttpNonWindows.class})
    public String getProxyPassword() {
        switch (proxyType) {
            case HTTP:
                return proxyHttpPassword;
            case SOCKS5:
                return proxySocks5Password;
        }
        return null;
    }

    public void setProxyPassword(String proxyPassword) {
        switch (proxyType) {
            case HTTP:
                proxyHttpPassword = proxyPassword;
                break;
            case SOCKS5:
                proxySocks5Password = proxyPassword;
                break;
        }
    }

    @JsonView(value = {Views.Socks5.class})
    public boolean isProxyStorePassword() {
        return proxyStorePassword;
    }

    public void setProxyStorePassword(boolean proxyStorePassword) {
        this.proxyStorePassword = proxyStorePassword;
    }

    public String getProxySocks5Username() {
        return proxySocks5Username;
    }

    public String getProxySocks5Password() {
        return proxySocks5Password;
    }

    public String getProxyHttpUsername() {
        return proxyHttpUsername;
    }

    public String getProxyKrbPrincipal() {
        if (proxyHttpUsername != null) {
            int backslashIndex = proxyHttpUsername.indexOf('\\');
            String username = backslashIndex > -1 ? proxyHttpUsername.substring(backslashIndex + 1) : proxyHttpUsername;
            String domain = backslashIndex > -1 ? proxyHttpUsername.substring(0, backslashIndex) : null;
            return username + "@" + domain.toUpperCase(Locale.ROOT);
        }
        return null;
    }

    public String getProxyHttpPassword() {
        return proxyHttpPassword;
    }

    @JsonView(value = {Views.Pac.class})
    public String getProxyPacFileLocation() {
        return proxyPacFileLocation;
    }

    public void setProxyPacFileLocation(String proxyPacFileLocation) {
        this.proxyPacFileLocation = proxyPacFileLocation;
    }

    @JsonView(value = {Views.Pac.class})
    public Integer getBlacklistTimeout() {
        return blacklistTimeout;
    }

    public void setBlacklistTimeout(Integer blacklistTimeout) {
        this.blacklistTimeout = blacklistTimeout;
    }


    public URL getProxyPacFileLocationAsURL() throws MalformedURLException {
        if (StringUtils.isNotEmpty(proxyPacFileLocation)) {
            if (HttpUtils.containsSchema(proxyPacFileLocation)) {
                return new URL(proxyPacFileLocation);
            } else {
                return new URL("file:///" + proxyPacFileLocation);
            }
        }
        return null;
    }


    public boolean isAutoConfig() {
        return this.proxyType.isPac();
    }

    public void setAutostart(boolean autostart) {
        this.autostart = autostart;
    }

    @JsonView(value = {Views.Settings.class})
    public boolean isAutostart() {
        return autostart;
    }

    @JsonView(value = {Views.WindowsSettings.class})
    public boolean isAutodetect() {
        return autodetect;
    }

    public void setAutodetect(boolean autodetect) {
        this.autodetect = autodetect;
    }

    public boolean isKerberos() {
        return !SystemContext.IS_OS_WINDOWS &&
                proxyType.isHttp() && httpAuthProtocol != null && httpAuthProtocol.isKerberos();
    }

    public boolean isNtlm() {
        return !SystemContext.IS_OS_WINDOWS &&
                proxyType.isHttp() && httpAuthProtocol != null && httpAuthProtocol.isNtlm();
    }

    @Autowired
    private void setTempDirectory(@Value("${user.home}") String userHome) {
        tempDirectory = Paths.get(userHome, SystemConfig.APP_HOME_DIR_NAME, "temp");
    }

    @JsonView(value = {Views.Settings.class})
    public Integer getApiPort() {
        return apiPort;
    }

    public void setApiPort(Integer apiPort) {
        this.apiPort = apiPort;
    }

    public String getApiToken() {
        return apiToken;
    }

    @JsonView(value = {Views.HttpNonWindows.class})
    public HttpAuthProtocol getHttpAuthProtocol() {
        return httpAuthProtocol;
    }

    public void setHttpAuthProtocol(HttpAuthProtocol httpAuthProtocol) {
        this.httpAuthProtocol = httpAuthProtocol;
    }

    @JsonView(value = {Views.HttpNonWindows.class})
    public String getKrb5ConfFilepath() {
        return krb5ConfFilepath;
    }

    /**
     * Save the current settings to the home application directory, overwriting the existing values.
     *
     * @throws ConfigurationException
     */
    @PreDestroy
    void save() throws ConfigurationException {
        logger.info("Save proxy settings");
        File userProperties = Paths.get(System.getProperty("user.home"), SystemConfig.APP_HOME_DIR_NAME,
                ProxyConfig.FILENAME).toFile();
        FileBasedConfigurationBuilder<PropertiesConfiguration> propertiesBuilder = new Configurations()
                .propertiesBuilder(userProperties);
        Configuration config = propertiesBuilder.getConfiguration();
        setProperty(config, "app.version", appVersion);
        setProperty(config, "api.port", apiPort);
        setProperty(config, "proxy.type", proxyType);
        setProperty(config, "proxy.http.host", proxyHttpHost);
        setProperty(config, "proxy.http.port", proxyHttpPort);
        setProperty(config, "proxy.socks4.host", proxySocks4Host);
        setProperty(config, "proxy.socks4.port", proxySocks4Port);
        setProperty(config, "proxy.socks5.host", proxySocks5Host);
        setProperty(config, "proxy.socks5.port", proxySocks5Port);
        setProperty(config, "local.port", localPort);
        setProperty(config, "proxy.test.url", proxyTestUrl);
        setProperty(config, "proxy.http.username", proxyHttpUsername);
        setProperty(config, "proxy.socks5.username", proxySocks5Username);
        setProperty(config, "proxy.storePassword", proxyStorePassword);

        if (StringUtils.isNotEmpty(proxyHttpPassword)) {
            setProperty(config, "proxy.http.password", "encoded(" + Base64.getEncoder().encodeToString(proxyHttpPassword.getBytes()) + ")");
        } else {
            config.clearProperty("proxy.http.password");
        }

        if (proxyStorePassword && StringUtils.isNotEmpty(proxySocks5Password)) {
            setProperty(config, "proxy.socks5.password", "encoded(" + Base64.getEncoder().encodeToString(proxySocks5Password.getBytes()) + ")");
        } else {
            config.clearProperty("proxy.socks5.password");
        }

        setProperty(config, "proxy.pac.fileLocation", proxyPacFileLocation);
        setProperty(config, "blacklist.timeout", blacklistTimeout);
        setProperty(config, "http.auth.protocol", httpAuthProtocol);
        setProperty(config, "autostart", autostart);
        setProperty(config, "autodetect", autodetect);
        propertiesBuilder.save();
    }

    private void setProperty(final Configuration config, final String key, final Object value) {
        if (value != null &&
                (!(value instanceof String) ||
                        StringUtils.isNotEmpty((String) value))) {
            config.setProperty(key, value);
        } else {
            config.clearProperty(key);
        }
    }

    /**
     * Check whether a {@link Configuration} instance is compatible with the current {@link ProxyConfig} structure.
     *
     * @param proxyConfig the {@link Configuration} instance
     * @return {@code true} if each {@link Configuration} key is among
     * the {@link ProxyConfig}'s {@link Value} annotated fields.
     */
    public static boolean isCompatible(Configuration proxyConfig) {
        List<String> keys = new ArrayList<>();
        for (Field field : ProxyConfig.class.getDeclaredFields()) {
            Value valueAnnotation = field.getAnnotation(Value.class);
            if (valueAnnotation != null) {
                keys.add(valueAnnotation.value().replaceAll("[${}]", "").split(":")[0]);
            }
        }

        for (Iterator<String> itr = proxyConfig.getKeys(); itr.hasNext(); ) {
            String key = itr.next();
            if (!keys.contains(key)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public String toString() {
        return "ProxyConfig{" +
                "appVersion=" + appVersion +
                ", apiPort=" + apiPort +
                ", apiToken=" + apiToken +
                ", proxyType=" + proxyType +
                ", localPort=" + localPort +
                ", proxyHttpHost=" + proxyHttpHost +
                ", proxySocks5Host=" + proxySocks5Host +
                ", proxySocks4Host=" + proxySocks4Host +
                ", proxyHttpPort=" + proxyHttpPort +
                ", proxySocks5Port=" + proxySocks5Port +
                ", proxySocks4Port=" + proxySocks4Port +
                ", proxyTestUrl=" + proxyTestUrl +
                ", proxySocks5Username=" + proxySocks5Username +
                ", proxyHttpUsername=" + proxyHttpUsername +
                ", proxyStorePassword=" + proxyStorePassword +
                ", proxyPacFileLocation=" + proxyPacFileLocation +
                ", blacklistTimeout=" + blacklistTimeout +
                ", autostart=" + autostart +
                ", autodetect=" + autodetect +
                ", httpAuthProtocol=" + httpAuthProtocol +
                ", krb5ConfFilepath=" + krb5ConfFilepath +
                ", tempDirectory=" + tempDirectory +
                '}';
    }

    public enum Type implements ProxyType {
        HTTP, SOCKS4, SOCKS5, PAC, DIRECT;

        public boolean isPac() {
            return this == PAC;
        }

        @Override
        public boolean isSocks4() {
            return this == SOCKS4;
        }

        @Override
        public boolean isSocks5() {
            return this == SOCKS5;
        }

        @Override
        public boolean isHttp() {
            return this == HTTP;
        }

        @Override
        public boolean isDirect() {
            return this == DIRECT;
        }

    }

    public enum HttpAuthProtocol {
        NTLM, KERBEROS, BASIC;

        public boolean isNtlm() {
            return this == NTLM;
        }

        public boolean isKerberos() {
            return this == KERBEROS;
        }

        public boolean isBasic() {
            return this == BASIC;
        }

    }

}
