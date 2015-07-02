package org.graylog2.plugin.httpmonitor;

import java.util.List;

/**
 * Created on 17/6/15.
 */
public class URLMonitorConfig {
    private String url,label,type,method,requestBody;
    private String[] requestHeadersToSend;
    //Basic Auth
    private String username,password;
    private int timeout, executionInterval;
    private String[] responseHeadersToRecord;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String[] getRequestHeadersToSend() {
        return requestHeadersToSend;
    }

    public void setRequestHeadersToSend(String[] requestHeadersToSend) {
        this.requestHeadersToSend = requestHeadersToSend;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getExecutionInterval() {
        return executionInterval;
    }

    public void setExecutionInterval(int executionInterval) {
        this.executionInterval = executionInterval;
    }

    public String[] getResponseHeadersToRecord() {
        return responseHeadersToRecord;
    }

    public void setResponseHeadersToRecord(String[] responseHeadersToRecord) {
        this.responseHeadersToRecord = responseHeadersToRecord;
    }
}