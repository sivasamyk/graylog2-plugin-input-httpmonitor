package org.graylog2.plugin.httpmonitor;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.ning.http.client.*;
import org.apache.commons.lang3.StringUtils;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.*;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.MisfireException;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.CodecAggregator;
import org.graylog2.plugin.inputs.transports.Transport;
import org.graylog2.plugin.journal.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Created on 17/6/15.
 */
public class HttpMonitorTransport implements Transport {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpMonitorTransport.class.getName());
    private static final String CK_CONFIG_URL = "configURL";
    private static final String CK_CONFIG_LABEL = "configLabel";
    private static final String CK_CONFIG_METHOD = "configMethod";
    private static final String CK_CONFIG_REQUEST_BODY = "configRequestBody";
    private static final String CK_CONFIG_HEADERS_TO_SEND = "configHeadersToSend";
    private static final String CK_CONFIG_USER_NAME = "configUsername";
    private static final String CK_CONFIG_PASSWORD = "configPassword";
    private static final String CK_CONFIG_TIMEOUT = "configTimeout";
    private static final String CK_CONFIG_TIMEOUT_UNIT = "configTimeoutUnit";
    private static final String CK_CONFIG_INTERVAL = "configInterval";
    private static final String CK_CONFIG_INTERVAL_UNIT = "configIntervalUnit";
    private static final String CK_CONFIG_HEADERS_TO_RECORD = "configHeadersToRecord";
    private static final String CK_CONFIG_LOG_RESPONSE_BODY = "configLogResponseBody";
    private static final String CK_CONFIG_HTTP_PROXY = "configHttpProxy";

    private static final String METHOD_POST = "POST";
    private static final String METHOD_HEAD = "HEAD";
    private static final String METHOD_PUT = "PUT";
    private static final String METHOD_GET = "GET";
    private final Configuration configuration;
    private final MetricRegistry metricRegistry;
    private ServerStatus serverStatus;
    private ScheduledExecutorService executorService;
    private ScheduledFuture future;
    private MessageInput messageInput;

    @AssistedInject
    public HttpMonitorTransport(@Assisted Configuration configuration,
                                MetricRegistry metricRegistry,
                                ServerStatus serverStatus) {
        this.configuration = configuration;
        this.metricRegistry = metricRegistry;
        this.serverStatus = serverStatus;
    }


    @Override
    public void setMessageAggregator(CodecAggregator codecAggregator) {

    }

    @Override
    public void launch(MessageInput messageInput) throws MisfireException {
        this.messageInput = messageInput;
        URLMonitorConfig urlMonitorConfig = new URLMonitorConfig();
        urlMonitorConfig.setUrl(configuration.getString(CK_CONFIG_URL));
        urlMonitorConfig.setLabel(configuration.getString(CK_CONFIG_LABEL));
        urlMonitorConfig.setMethod(configuration.getString(CK_CONFIG_METHOD));

        String proxyUri = configuration.getString(CK_CONFIG_HTTP_PROXY);
        if (proxyUri != null && !proxyUri.isEmpty()) {
            urlMonitorConfig.setHttpProxyUri(URI.create(proxyUri));
        }

        String requestHeaders = configuration.getString(CK_CONFIG_HEADERS_TO_SEND);
        if (StringUtils.isNotEmpty(requestHeaders)) {
            urlMonitorConfig.setRequestHeadersToSend(
                    requestHeaders.split(","));
        }
        urlMonitorConfig.setUsername(configuration.getString(CK_CONFIG_USER_NAME));
        urlMonitorConfig.setPassword(configuration.getString(CK_CONFIG_PASSWORD));
        urlMonitorConfig.setExecutionInterval(configuration.getInt(CK_CONFIG_INTERVAL));
        urlMonitorConfig.setTimeout(configuration.getInt(CK_CONFIG_TIMEOUT));
        urlMonitorConfig.setTimeoutUnit(TimeUnit.valueOf(configuration.getString(CK_CONFIG_TIMEOUT_UNIT)));
        urlMonitorConfig.setIntervalUnit(TimeUnit.valueOf(configuration.getString(CK_CONFIG_INTERVAL_UNIT)));

//        long timoutInMs = TimeUnit.MILLISECONDS.convert(urlMonitorConfig.getTimeout(), urlMonitorConfig.getTimeoutUnit());
//        long intervalInMs = TimeUnit.MILLISECONDS.convert(urlMonitorConfig.getExecutionInterval(), urlMonitorConfig.getIntervalUnit());
//
//        if (intervalInMs <= timoutInMs) {
//            String message = MessageFormat.format("Timeout {0} {1} should be smaller than interval {2} {3}",
//                    urlMonitorConfig.getTimeout(),urlMonitorConfig.getTimeoutUnit(),
//                    urlMonitorConfig.getExecutionInterval(), urlMonitorConfig.getIntervalUnit());
//            throw new MisfireException(message);
//        }

        urlMonitorConfig.setRequestBody(configuration.getString(CK_CONFIG_REQUEST_BODY));
        urlMonitorConfig.setLogResponseBody(configuration.getBoolean(CK_CONFIG_LOG_RESPONSE_BODY));

        String responseHeaders = configuration.getString(CK_CONFIG_HEADERS_TO_RECORD);
        if (StringUtils.isNotEmpty(responseHeaders)) {
            urlMonitorConfig.setResponseHeadersToRecord(
                    responseHeaders.split(","));
        }

        startMonitoring(urlMonitorConfig);
    }

    @Override
    public void stop() {

        if (future != null) {
            future.cancel(true);
        }

        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private void startMonitoring(URLMonitorConfig config) {
        executorService = Executors.newSingleThreadScheduledExecutor();
        long initalDelayMs = TimeUnit.MILLISECONDS.convert(Math.round(Math.random() * 60), TimeUnit.SECONDS);
        long executionIntervalMs = TimeUnit.MILLISECONDS.convert(config.getExecutionInterval(), config.getIntervalUnit());
        future = executorService.scheduleAtFixedRate(new MonitorTask(config, messageInput), initalDelayMs,
                executionIntervalMs, TimeUnit.MILLISECONDS);
    }


    @Override
    public MetricSet getMetricSet() {
        return null;
    }

    private static class MonitorTask implements Runnable {
        private URLMonitorConfig config;
        private MessageInput messageInput;
        private ObjectMapper mapper;
        private AsyncHttpClient httpClient;
        private AsyncHttpClient.BoundRequestBuilder requestBuilder;

        public MonitorTask(URLMonitorConfig config, MessageInput messageInput) {
            this.config = config;
            this.messageInput = messageInput;
            this.mapper = new ObjectMapper();
            AsyncHttpClientConfig.Builder configBuilder = new AsyncHttpClientConfig.Builder();
            configBuilder.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.1", "TLSv1"});
            configBuilder.setSSLContext(getSSLContext());
            httpClient = new AsyncHttpClient(configBuilder.build());
            buildRequest();
        }

        //Accept all certficates
        private SSLContext getSSLContext() {
            try {
                SSLContext context = SSLContext.getInstance("SSL");
                context.init(null, new TrustManager[]{
                        new X509TrustManager() {

                            @Override
                            public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {

                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {

                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return null;
                            }
                        }
                }, null);
                return context;
            } catch (GeneralSecurityException e) {
                LOGGER.debug("Exception while creating certs ",e);
            }
            return null;
        }

        @Override
        public void run() {
            //send to http server
            try {

                long startTime = System.currentTimeMillis();
                long time;
                Map<String, Object> eventdata = Maps.newHashMap();
                eventdata.put("version", "1.1");
                eventdata.put("_http_monitor_url", config.getUrl());
                eventdata.put("_label", config.getLabel());
                try {
                    Response response = requestBuilder.execute().get();
                    long endTime = System.currentTimeMillis();
                    time = endTime - startTime;
                    eventdata.put("host", response.getUri().getHost());
                    eventdata.put("_http_monitor_status", response.getStatusCode());
                    eventdata.put("_http_monitor_statusLine", response.getStatusText());
                    String responseBodyStr = new String(response.getResponseBodyAsBytes());
                    eventdata.put("_http_monitor_responseSize", responseBodyStr.length());
                    if (config.isLogResponseBody()) {
                        eventdata.put("full_message", responseBodyStr);
                    }
                    String shortMessage = responseBodyStr.length() > 50 ? responseBodyStr.substring(0, 50) :
                            responseBodyStr;
                    if (shortMessage.isEmpty()) {
                        shortMessage = "no_response";
                    }
                    eventdata.put("short_message", shortMessage);


                    if (config.getResponseHeadersToRecord() != null) {
                        for (String header : config.getResponseHeadersToRecord()) {
                            eventdata.put("_" + header, response.getHeader(header));
                        }
                    }
                } catch (ExecutionException e) {
                    eventdata.put("host", new URL(config.getUrl()).getHost());
                    eventdata.put("short_message", "Request failed :" + e.getMessage());
                    eventdata.put("_http_monitor_responseSize", 0);
                    long endTime = System.currentTimeMillis();
                    time = endTime - startTime;
                    //In case of connection timeout we get an execution exception with root cause as timeoutexception
                    if (e.getCause() instanceof TimeoutException) {
                        LOGGER.debug("Timeout while executing request for URL " + config.getUrl(), e);
                        eventdata.put("_http_monitor_status", 998);
                    } else if (e.getCause() instanceof ConnectException) {
                        //In case of connect exception we get an execution exception with root cause as connectexception
                        LOGGER.debug("Exception while executing request for URL " + config.getUrl(), e);
                        eventdata.put("_http_monitor_status", 999);
                    } else {
                        //Any other exception..
                        LOGGER.debug("Exception while executing request for URL " + config.getUrl(), e);
                        eventdata.put("_http_monitor_status", 997);
                    }
                }
                eventdata.put("_http_monitor_responseTime", time);

                //publish to graylog server
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                mapper.writeValue(byteStream, eventdata);
                messageInput.processRawMessage(new RawMessage(byteStream.toByteArray()));
                byteStream.close();

            } catch (InterruptedException | IOException e) {
                LOGGER.error("Exception while executing request for URL " + config.getUrl(), e);
            }
        }

        private void buildRequest() {

            //Build request object
            if (METHOD_POST.equals(config.getMethod())) {
                requestBuilder = httpClient.preparePost(config.getUrl());
            } else if (METHOD_PUT.equals(config.getMethod())) {
                requestBuilder = httpClient.preparePut(config.getUrl());
            } else if (METHOD_HEAD.equals(config.getMethod())) {
                requestBuilder = httpClient.prepareHead(config.getUrl());
            } else {
                requestBuilder = httpClient.prepareGet(config.getUrl());
            }

            if (StringUtils.isNotEmpty(config.getRequestBody())) {
                requestBuilder.setBody(config.getRequestBody());
            }

            if (config.getRequestHeadersToSend() != null) {
                for (String header : config.getRequestHeadersToSend()) {
                    String tokens[] = header.split(":");
                    requestBuilder.setHeader(tokens[0], tokens[1]);
                }
            }

            if (StringUtils.isNotEmpty(config.getUsername()) &&
                    StringUtils.isNotEmpty(config.getPassword())) {
                Realm realm = new Realm.RealmBuilder()
                        .setPrincipal(config.getUsername())
                        .setPassword(config.getPassword())
                        .setScheme(Realm.AuthScheme.BASIC).build();
                requestBuilder.setRealm(realm);
            }

            int timeoutInMs = (int) TimeUnit.MILLISECONDS.convert(config.getTimeout(), config.getTimeoutUnit());
            requestBuilder.setRequestTimeout(timeoutInMs);

            if (config.getHttpProxyUri() != null) {
                ProxyServer proxyServer = new ProxyServer(config.getHttpProxyUri().getHost(), config.getHttpProxyUri().getPort());
                requestBuilder.setProxyServer(proxyServer);
            }
        }
    }

    @FactoryClass
    public interface Factory extends Transport.Factory<HttpMonitorTransport> {

        @Override
        HttpMonitorTransport create(Configuration configuration);

        @Override
        Config getConfig();

    }

    @ConfigClass
    public static class Config implements Transport.Config {
        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            final ConfigurationRequest cr = new ConfigurationRequest();
            cr.addField(new TextField(CK_CONFIG_URL,
                    "URL to monitor",
                    "",
                    ""));
            cr.addField(new TextField(CK_CONFIG_LABEL,
                    "Label",
                    "",
                    "Label to identify this HTTP monitor"));

            Map<String, String> httpMethods = new HashMap<>();
            httpMethods.put(METHOD_GET, METHOD_GET);
            httpMethods.put(METHOD_POST, METHOD_POST);
            httpMethods.put(METHOD_PUT, METHOD_PUT);
            cr.addField(new DropdownField(CK_CONFIG_METHOD,
                    "HTTP Method",
                    "GET",
                    httpMethods,
                    "Label to identify this HTTP monitor",
                    ConfigurationField.Optional.NOT_OPTIONAL));

            cr.addField(new TextField(CK_CONFIG_REQUEST_BODY,
                    "Request Body",
                    "",
                    "Request Body to send",
                    ConfigurationField.Optional.OPTIONAL,
                    TextField.Attribute.TEXTAREA));

            cr.addField(new TextField(CK_CONFIG_HEADERS_TO_SEND,
                    "Additional HTTP headers",
                    "",
                    "Add a comma separated list of additional HTTP headers to send. For example: Accept: application/json, X-Requester: Graylog2",
                    ConfigurationField.Optional.OPTIONAL));

            cr.addField(new TextField(CK_CONFIG_USER_NAME,
                    "HTTP Basic Auth Username",
                    "",
                    "Username for HTTP Basic Authentication",
                    ConfigurationField.Optional.OPTIONAL));
            cr.addField(new TextField(CK_CONFIG_PASSWORD,
                    "HTTP Basic Auth Password",
                    "",
                    "Password for HTTP Basic Authentication",
                    ConfigurationField.Optional.OPTIONAL,
                    TextField.Attribute.IS_PASSWORD));

            cr.addField(new NumberField(CK_CONFIG_INTERVAL,
                    "Interval",
                    1,
                    "Time between between requests",
                    ConfigurationField.Optional.NOT_OPTIONAL));

            Map<String, String> timeUnits = DropdownField.ValueTemplates.timeUnits();
            //Do not add nano seconds and micro seconds
            timeUnits.remove(TimeUnit.NANOSECONDS.toString());
            timeUnits.remove(TimeUnit.MICROSECONDS.toString());

            cr.addField(new DropdownField(
                    CK_CONFIG_INTERVAL_UNIT,
                    "Interval time unit",
                    TimeUnit.MINUTES.toString(),
                    timeUnits,
                    ConfigurationField.Optional.NOT_OPTIONAL
            ));


            cr.addField(new NumberField(CK_CONFIG_TIMEOUT,
                    "Timeout",
                    20,
                    "Timeout for requests",
                    ConfigurationField.Optional.NOT_OPTIONAL));

            cr.addField(new DropdownField(
                    CK_CONFIG_TIMEOUT_UNIT,
                    "Timeout time unit",
                    TimeUnit.SECONDS.toString(),
                    timeUnits,
                    ConfigurationField.Optional.NOT_OPTIONAL
            ));


            cr.addField(new TextField(CK_CONFIG_HTTP_PROXY,
                    "HTTP Proxy URI",
                    "",
                    "URI of HTTP Proxy to be used if required e.g. http://myproxy:8888",
                    ConfigurationField.Optional.OPTIONAL));


            cr.addField(new TextField(CK_CONFIG_HEADERS_TO_RECORD,
                    "Response headers to log",
                    "",
                    "Comma separated response headers to log. For example: Accept,Server,Expires",
                    ConfigurationField.Optional.OPTIONAL));

            cr.addField(new BooleanField(CK_CONFIG_LOG_RESPONSE_BODY,
                    "Log full response body",
                    false,
                    "Select if the complete response body needs to be logged as part of message"));

            return cr;
        }
    }

    public static void main(String args[]) {
        URLMonitorConfig config = new URLMonitorConfig();
        config.setUrl("https://www.skipper18.com");
        config.setMethod("GET");
        MonitorTask monitorTask = new MonitorTask(config,null);
        monitorTask.run();
    }
}
