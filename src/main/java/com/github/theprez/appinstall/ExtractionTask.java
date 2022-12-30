package com.github.theprez.appinstall;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

import com.github.theprez.jcmdutils.AppLogger;

public class ExtractionTask {

    private final PackageConfiguration m_config;
    private final File m_installDir;
    private final AppLogger m_logger;

    public ExtractionTask(final AppLogger _logger, final PackageConfiguration _config) {
        m_logger = _logger;
        m_config = _config;
        final String installId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        final File homeDir = new File(System.getProperty("user.home", "~"));
        final File dotDir = new File(homeDir, ".appinstall");
        final File allInstallsDir = new File(dotDir, "installs");
        m_installDir = new File(allInstallsDir, installId);
    }

    public File run() throws IOException {
        m_logger.println("Creating temporary processing directory...");
        m_installDir.mkdirs();
        if (!m_installDir.isDirectory()) {
            throw new IOException("Could not create temporary installation directory: " + m_installDir);
        }
        final int bufferSize = 1024 * 512;
        for (final String fileStr : m_config.getFiles()) {
            final File destFile = new File(m_installDir, fileStr);
            m_logger.printfln("Extracting %s...", fileStr);
            try (BufferedInputStream in = new BufferedInputStream(m_logger.getClass().getClassLoader().getResourceAsStream("APPINSTALL-DATA/" + fileStr), bufferSize)) {
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(destFile), bufferSize)) {
                    final byte[] buf = new byte[bufferSize];
                    int bytesRead = -1;
                    while (0 < (bytesRead = in.read(buf))) {
                        out.write(buf, 0, bytesRead);
                    }
                }
            }
        }
        m_logger.println_success("Extraction phase complete");
        m_logger.println_verbose("Install directory is "+m_installDir.getAbsolutePath());
        return m_installDir;
    }

}
