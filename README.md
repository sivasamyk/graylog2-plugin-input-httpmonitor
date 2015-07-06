# Graylog Http Monitor Input Plugin

An input monitor plugin for monitoring HTTP URLs (websites and REST APIs). 
Works by periodically polling the URLs and recording the responses as messages. 

This plugin provides support for monitoring following parameters

* Response time in milliseconds
* HTTP Status Code
* HTTP Status Text
* HTTP Response Body
* HTTP Response size in bytes
* Timeouts and connection failures
* Custom Response Headers

Getting started
---------------

To start using this plugin place this jar in the plugins directory of graylog server. 

Following parameters can be configured while launching the plugin

* URL to monitor ( supports HTTPS URLs with self-signed certificates also)
* Polling interval - Interval in minutes to execute the HTTP methods
* Timeout - Time in seconds to wait before declaring the request as timed out
* HTTP Method - GET/POST/PUT method to be executed
* Additional HTTP headers to send - Comma separated list of HTTP request headers to be sent as part of request. e.g. CAccept:application/json, X-Requester:Graylog2
* Additional HTTP headers to log - Command separated list of HTTP response headers to log as part of message. e.g. Expires,Date
* HTTP Basic Authentication username and password

The status code will be 999 on connection failures and 998 on connection timeouts. 