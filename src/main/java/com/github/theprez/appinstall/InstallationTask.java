package com.github.theprez.appinstall;

import java.io.File;
import java.io.IOException;

import com.github.theprez.jcmdutils.AppLogger;
import com.github.theprez.jcmdutils.AppLogger.DefaultLogger;
import com.github.theprez.jcmdutils.ProcessLauncher;
import com.github.theprez.jcmdutils.StringUtils;
import com.ibm.as400.access.AS400;

public class InstallationTask {

    private final PackageConfiguration m_config;
    private final File m_dir;
    private final AppLogger m_logger;

    public InstallationTask(final AppLogger _logger, final PackageConfiguration _config, final File _sourceDir) {
        m_logger = _logger;
        m_dir = _sourceDir;
        m_config = _config;
    }

    public void run() throws IOException, InterruptedException {
        final DefaultLogger childLogger = new DefaultLogger(true);
        {
            final File preinstall = new File(m_dir, ".preinstall");
            if (preinstall.exists()) {
                m_logger.println("Executing pre-installation tasks...");
                preinstall.setExecutable(true);
                final Process p = Runtime.getRuntime().exec(preinstall.getName(), null, m_dir);
                ProcessLauncher.pipeStreamsToCurrentProcess("PREINSTALL", p, childLogger);
                p.waitFor();
                if (0 != p.exitValue()) {
                    throw new IOException("Pre-installation tasks failed");
                }
                m_logger.println_success("Successfully executed pre-installation tasks");
            }
        }
        final AS400 as400 = new AS400("localhost", "*CURRENT", "*CURRENT");
        try {
            m_logger.println("Performing installation...");
            for (final String cmd : m_config.getCommands()) {
                if (StringUtils.isEmpty(cmd)) {
                    continue;
                }
                if (Character.isUpperCase(cmd.trim().charAt(0))) { // CL command
                    final String doctoredCmd = cmd.replace("$PWD", m_dir.getAbsolutePath());
                    InstallPackageBuilder.runCommand(m_logger, as400, doctoredCmd);
                } else {
                    m_logger.printfln_verbose("Running command '%s'", cmd);
                    final Process p = Runtime.getRuntime().exec(new String[] { "/QOpenSys/usr/bin/sh", "-c", cmd }, null, m_dir);
                    ProcessLauncher.pipeStreamsToCurrentProcess("INSTALL", p, childLogger);
                    p.waitFor();
                    if (0 != p.exitValue()) {
                        throw new IOException("Installation tasks failed");
                    }
                }
            }
        } finally {
            if (null != as400) {
                as400.disconnectAllServices();
            }
        }
        final File postinstall = new File(m_dir, ".postinstall");

        if (postinstall.exists()) {
            m_logger.println("Executing post-installation tasks...");
            postinstall.setExecutable(true);
            final Process p = Runtime.getRuntime().exec(postinstall.getName(), null, m_dir);
            ProcessLauncher.pipeStreamsToCurrentProcess("POSTINSTALL", p, childLogger);
            p.waitFor();
            if (0 != p.exitValue()) {
                throw new IOException("Pre-installation tasks failed");
            }
            m_logger.println_success("Successfully executed post-installation tasks");
        }
        m_logger.println_success("Installation complete");
    }

}
