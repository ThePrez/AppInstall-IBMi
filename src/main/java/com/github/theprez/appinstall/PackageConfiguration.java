package com.github.theprez.appinstall;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import com.github.theprez.jcmdutils.AppLogger;

public class PackageConfiguration {

    private final List<String> m_commands;
    private final List<String> m_files;
    private final AppLogger m_logger;

    public PackageConfiguration(final AppLogger _logger) throws IOException {
        m_logger = _logger;
        m_logger.println("Opening package manifest...");
        final InputStream yamlStream = PackageConfiguration.class.getClassLoader().getResourceAsStream("APPINSTALL-INF/manifest.yml");
        if (null == yamlStream) {
            throw new IOException("Package manifest not found!");
        }
        m_logger.println("Processing package manifest...");
        try {
            final Map<String, Object> yaml = new Yaml().load(yamlStream);
            m_files = (List<String>) yaml.remove("files");
            m_commands = (List<String>) yaml.remove("commands");
            m_logger.println_success("Successfully processed package manifest");
        } catch (final Exception e) {
            throw new IOException("Invalid package manifest: " + e.getLocalizedMessage(), e);
        }
    }

    public List<String> getCommands() {
        return m_commands;
    }

    public List<String> getFiles() {
        return m_files;
    }
}
