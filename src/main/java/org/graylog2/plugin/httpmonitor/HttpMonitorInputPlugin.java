package org.graylog2.plugin.httpmonitor;

import org.graylog2.plugin.Plugin;
import org.graylog2.plugin.PluginMetaData;
import org.graylog2.plugin.PluginModule;

import java.util.Arrays;
import java.util.Collection;

/**
 * Implement the Plugin interface here.
 */
public class HttpMonitorInputPlugin implements Plugin {
    @Override
    public PluginMetaData metadata() {
        return new HttpMonitorInputMetaData();
    }

    @Override
    public Collection<PluginModule> modules () {
        return Arrays.<PluginModule>asList(new HttpMonitorInputModule());
    }
}
