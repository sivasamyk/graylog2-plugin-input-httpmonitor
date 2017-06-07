package org.graylog2.plugin.httpmonitor;

import org.graylog2.plugin.PluginMetaData;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.Version;

import java.net.URI;
import java.util.Collections;
import java.util.Set;

/**
 * Implement the PluginMetaData interface here.
 */
public class HttpMonitorInputMetaData implements PluginMetaData {
    @Override
    public String getUniqueId() {
        return "org.graylog2.plugin.httpmonitor.HttpMonitorInputPlugin";
    }

    @Override
    public String getName() {
        return "HttpMonitorInput";
    }

    @Override
    public String getAuthor() {
        return "Sivasamy Kaliappan";
    }

    @Override
    public URI getURL() {
        return URI.create("https://www.graylog.org/");
    }

    @Override
    public Version getVersion() {
        return new Version(1, 0, 5);
    }

    @Override
    public String getDescription() {
        return "HTTP Monitor Plugin";
    }

    @Override
    public Version getRequiredVersion() {
        return new Version(2, 0, 0);
    }

    @Override
    public Set<ServerStatus.Capability> getRequiredCapabilities() {
        return Collections.emptySet();
    }
}
