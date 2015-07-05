package org.graylog2.plugin.httpmonitor;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Realm;
import com.ning.http.client.Response;
import org.apache.commons.lang3.StringUtils;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.DropdownField;
import org.graylog2.plugin.configuration.fields.NumberField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.MisfireException;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.CodecAggregator;
import org.graylog2.plugin.inputs.transports.Transport;
import org.graylog2.plugin.journal.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
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
    private static final String CK_CONFIG_INTERVAL = "configInterval";
    private static final String CK_CONFIG_HEADERS_TO_RECORD = "configHeadersToRecord";

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

        String requestHeaders = configuration.getString(CK_CONFIG_HEADERS_TO_SEND);
        if (StringUtils.isNotEmpty(requestHeaders)) {
            urlMonitorConfig.setRequestHeadersToSend(
                    requestHeaders.split(","));
        }
        urlMonitorConfig.setUsername(configuration.getString(CK_CONFIG_USER_NAME));
        urlMonitorConfig.setPassword(configuration.getString(CK_CONFIG_PASSWORD));
        urlMonitorConfig.setExecutionInterval(configuration.getInt(CK_CONFIG_INTERVAL));
        urlMonitorConfig.setTimeout(configuration.getInt(CK_CONFIG_TIMEOUT));
        urlMonitorConfig.setRequestBody(configuration.getString(CK_CONFIG_REQUEST_BODY));

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
        long initalDelay = Math.round(Math.random() * 60);
        future = executorService.scheduleAtFixedRate(new MonitorTask(config, messageInput), initalDelay,
                config.getExecutionInterval() * 60, TimeUnit.SECONDS);
    }


    @Override
    public MetricSet getMetricSet() {
        return null;
    }

    private static class MonitorTask implements Runnable {
        private URLMonitorConfig config;
        private MessageInput messageInput;
        private ObjectMapper mapper;

        public MonitorTask(URLMonitorConfig config, MessageInput messageInput) {
            this.config = config;
            this.messageInput = messageInput;
            this.mapper = new ObjectMapper();
        }

        @Override
        public void run() {
            AsyncHttpClient client = new AsyncHttpClient();
            AsyncHttpClient.BoundRequestBuilder builder = buildRequest(client);

            //send to http server
            try {

                long startTime = System.currentTimeMillis();
                long time = -1;

                Map<String, Object> eventdata = Maps.newHashMap();
                eventdata.put("version", "1.1");
                eventdata.put("_url", config.getUrl());
                eventdata.put("_label", config.getLabel());
                try {
                    Response response = builder.execute().get(config.getTimeout(), TimeUnit.SECONDS);
                    long endTime = System.currentTimeMillis();
                    time = endTime - startTime;
                    eventdata.put("host", response.getUri().getHost());
                    eventdata.put("_status", response.getStatusCode());
                    eventdata.put("_statusLine", response.getStatusText());
                    String responseBodyStr = new String(response.getResponseBodyAsBytes());
                    eventdata.put("full_message", responseBodyStr);
                    String shortMessage = responseBodyStr.length() > 50 ? responseBodyStr.substring(0, 50) :
                            responseBodyStr;
                    eventdata.put("short_message", shortMessage);
                    if (config.getResponseHeadersToRecord() != null) {
                        for (String header : config.getResponseHeadersToRecord()) {
                            eventdata.put("_" + header, response.getHeader(header));
                        }
                    }
                } catch (IOException e) {
                    LOGGER.debug("Exception while executing request for URL " + config.getUrl(), e);
                    eventdata.put("host", new URL(config.getUrl()).getHost());
                    eventdata.put("short_message", "Request failed :" + e.getMessage());
                    eventdata.put("_status", 999);
                } catch (TimeoutException e) {
                    LOGGER.debug("Timeout while executing request for URL " + config.getUrl(), e);
                    eventdata.put("host", new URL(config.getUrl()).getHost());
                    eventdata.put("short_message", "Request failed :" + e.getMessage());
                    eventdata.put("_status", 998);
                    long endTime = System.currentTimeMillis();
                    time = endTime - startTime;
                }
                eventdata.put("_time", time);

                //publish to graylog server
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                mapper.writeValue(byteStream, eventdata);
                messageInput.processRawMessage(new RawMessage(byteStream.toByteArray()));
                byteStream.close();

            } catch (InterruptedException | ExecutionException | IOException e ) {
                LOGGER.error("Exception while executing request for URL " + config.getUrl(), e);
            }
        }

        private AsyncHttpClient.BoundRequestBuilder buildRequest(AsyncHttpClient client) {
            AsyncHttpClient.BoundRequestBuilder builder = null;

            //Build request object
            if (METHOD_POST.equals(config.getMethod())) {
                builder = client.preparePost(config.getUrl());
            } else if (METHOD_PUT.equals(config.getMethod())) {
                builder = client.preparePut(config.getUrl());
            } else if (METHOD_HEAD.equals(config.getMethod())) {
                builder = client.prepareHead(config.getUrl());
            } else {
                builder = client.prepareGet(config.getUrl());
            }

            if (StringUtils.isNotEmpty(config.getRequestBody())) {
                builder.setBody(config.getRequestBody());
            }

            if (config.getRequestHeadersToSend() != null) {
                for (String header : config.getRequestHeadersToSend()) {
                    String tokens[] = header.split(":");
                    builder.setHeader(tokens[0], tokens[1]);
                }
            }

            if (StringUtils.isNotEmpty(config.getUsername()) &&
                    StringUtils.isNotEmpty(config.getPassword())) {
                Realm realm = new Realm.RealmBuilder()
                        .setPrincipal(config.getUsername())
                        .setPassword(config.getPassword())
                        .setScheme(Realm.AuthScheme.BASIC).build();
                builder.setRealm(realm);
            }

            return builder;
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
                    "Time in minutes between requests",
                    ConfigurationField.Optional.NOT_OPTIONAL));
            cr.addField(new NumberField(CK_CONFIG_TIMEOUT,
                    "Timeout",
                    20,
                    "Timeout in seconds for requests",
                    ConfigurationField.Optional.NOT_OPTIONAL));

            cr.addField(new TextField(CK_CONFIG_HEADERS_TO_RECORD,
                    "Response headers to log",
                    "",
                    "Comma separated response headers to log. For example: Accept,Server,Expires",
                    ConfigurationField.Optional.OPTIONAL));

            return cr;
        }
    }
}
